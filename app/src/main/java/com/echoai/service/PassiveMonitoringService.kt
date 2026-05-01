package com.echoai.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.echoai.R
import com.echoai.audio.AudioCaptureManager
import com.echoai.audio.AudioWindow
import com.echoai.domain.ClassificationResult
import com.echoai.domain.DevicePosition
import com.echoai.domain.LagSample
import com.echoai.domain.PinnedAlertTracker
import com.echoai.domain.ProfileManager
import com.echoai.domain.SoundEvent
import com.echoai.domain.SoundHistoryManager
import com.echoai.domain.Urgency
import com.echoai.domain.UrgencyClassifier
import com.echoai.ml.StubSoundClassifier
import com.echoai.ml.YamnetClassifier
import com.echoai.pipeline.ClassificationStage
import com.echoai.sensor.NullWorldOrientation
import com.echoai.ui.MainActivity
import com.echoai.util.AppForegroundTracker
import com.echoai.util.HapticManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Android-compliant passive microphone monitor. Runs only as a foreground service, so
 * users always see an active notification while EchoAI is listening in the background.
 */
class PassiveMonitoringService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var monitorJob: Job? = null
    private var lastClassification: ClassificationResult? = null
    private var classificationFrameCounter = 0

    private val captureManager by lazy {
        AudioCaptureManager(applicationContext, NullWorldOrientation)
    }
    private val urgencyClassifier by lazy { UrgencyClassifier(applicationContext) }
    private val classificationStage by lazy {
        ClassificationStage(YamnetClassifier.create(applicationContext) ?: StubSoundClassifier())
    }
    private val profileManager by lazy { ProfileManager(applicationContext) }
    private val hapticManager by lazy { HapticManager(applicationContext) }
    private val pinnedAlertTracker by lazy { PinnedAlertTracker(applicationContext) }
    private val historyManager by lazy { SoundHistoryManager(applicationContext) }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val foregroundStarted = runCatching {
            startForeground(NOTIFICATION_ID_MONITORING, monitoringNotification())
            true
        }.getOrElse {
            Log.w(TAG, "Unable to promote background listening service", it)
            stopSelf()
            false
        }
        if (!foregroundStarted) return START_NOT_STICKY

        runCatching {
            startMonitoring()
        }.onFailure {
            Log.w(TAG, "Unable to start background microphone monitoring", it)
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        captureManager.stop()
        monitorJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    private fun startMonitoring() {
        if (monitorJob != null) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            stopSelf()
            return
        }

        runCatching {
            captureManager.start(scope)
        }.onFailure {
            Log.w(TAG, "Unable to open background microphone capture", it)
            stopSelf()
            return
        }
        monitorJob = scope.launch {
            try {
                captureManager.windows.collectLatest { window ->
                    val shouldClassify = (classificationFrameCounter % CLASSIFY_EVERY_N) == 0
                    classificationFrameCounter++
                    val classification = if (shouldClassify) {
                        val fresh = classificationStage.classify(window)
                        lastClassification = fresh
                        fresh
                    } else {
                        lastClassification ?: ClassificationResult(window.frameNumber, emptyList())
                    }
                    val events = urgentEventsFromClassification(classification, window)
                    handleEvents(events)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Background monitoring stopped after pipeline failure", e)
                stopSelf()
            }
        }
    }

    private fun urgentEventsFromClassification(
        classification: ClassificationResult,
        window: AudioWindow,
    ): List<SoundEvent> {
        val now = window.captureTimestampNanos
        val neutralPosition = DevicePosition(
            frontBackBias = 0f,
            bottomIld = 0f,
            backIld = 0f,
            crossPairLag = LagSample(samples = 0, confidence = 0f),
            withinPairBottom = LagSample(samples = 0, confidence = 0f),
            withinPairBack = LagSample(samples = 0, confidence = 0f),
            sampleRate = window.sampleRate,
        )

        return classification.topK.mapNotNull { score ->
            if (score.confidence < URGENT_CONFIDENCE_THRESHOLD) return@mapNotNull null
            val urgency = resolveUrgency(score.label)
            if (urgency.ordinalRank < Urgency.HIGH.ordinalRank) return@mapNotNull null

            SoundEvent(
                label = score.label,
                confidence = score.confidence,
                urgency = urgency,
                firstSeenTimestampNanos = now,
                lastSeenTimestampNanos = now,
                devicePosition = neutralPosition,
                worldOrientation = null,
                isPrioritized = true,
            )
        }
    }

    private fun resolveUrgency(label: String): Urgency {
        val overrides = profileManager.activeProfile.value.urgencyOverrides
        return overrides[label]
            ?: overrides.entries.firstOrNull {
                label.contains(it.key, ignoreCase = true) || it.key.contains(label, ignoreCase = true)
            }?.value
            ?: urgencyClassifier.classify(label)
    }

    private fun handleEvents(events: List<SoundEvent>) {
        val urgentEvents = events.filter { it.urgency.ordinalRank >= Urgency.HIGH.ordinalRank }
        if (urgentEvents.isEmpty()) return

        pinnedAlertTracker.onEvents(urgentEvents)
        historyManager.logHighUrgencyEvents(urgentEvents, getString(R.string.passive_monitoring_profile))
        hapticManager.vibrateForHighest(urgentEvents)
        showUrgentNotification(urgentEvents)
    }

    private fun monitoringNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_MONITORING)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(getString(R.string.passive_monitoring_title))
            .setContentText(getString(R.string.passive_monitoring_body))
            .setContentIntent(mainActivityIntent())
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun showUrgentNotification(events: List<SoundEvent>) {
        if (AppForegroundTracker.isInForeground()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val top = events.maxByOrNull { it.urgency.ordinalRank } ?: return
        val text = getString(R.string.passive_urgent_alert_body, top.label, top.urgency.name)
        val notification = NotificationCompat.Builder(this, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(getString(R.string.passive_urgent_alert_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(mainActivityIntent())
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        notificationManager().notify(NOTIFICATION_ID_ALERT, notification)
    }

    private fun mainActivityIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        notificationManager().createNotificationChannel(
            NotificationChannel(
                CHANNEL_MONITORING,
                getString(R.string.passive_monitoring_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.passive_monitoring_channel_description)
            },
        )
        notificationManager().createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERTS,
                getString(R.string.passive_alerts_channel),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = getString(R.string.passive_alerts_channel_description)
            },
        )
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(NotificationManager::class.java)

    companion object {
        private const val ACTION_STOP = "com.echoai.service.PassiveMonitoringService.STOP"
        private const val CHANNEL_MONITORING = "passive_monitoring"
        private const val CHANNEL_ALERTS = "passive_alerts"
        private const val NOTIFICATION_ID_MONITORING = 1001
        private const val NOTIFICATION_ID_ALERT = 1002
        private const val CLASSIFY_EVERY_N = 4
        private const val URGENT_CONFIDENCE_THRESHOLD = 0.20f

        fun start(context: Context): Boolean {
            val intent = Intent(context, PassiveMonitoringService::class.java)
            return runCatching {
                ContextCompat.startForegroundService(context, intent)
                true
            }.getOrElse {
                Log.w(TAG, "Unable to start background listening service", it)
                false
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PassiveMonitoringService::class.java))
        }

        private const val TAG = "PassiveMonitoringService"
    }
}

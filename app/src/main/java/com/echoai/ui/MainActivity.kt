package com.echoai.ui

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.Html
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.echoai.R
import com.echoai.audio.AudioCaptureManager
import com.echoai.audio.AudioWindow
import com.echoai.databinding.ActivityMainBinding
import com.echoai.diagnostics.DiagnosticsLogger
import com.echoai.domain.BeliefDistribution
import com.echoai.domain.ClassificationResult
import com.echoai.domain.EventTracker
import com.echoai.domain.PinnedAlertTracker
import com.echoai.domain.ProfileManager
import com.echoai.domain.SoundEvent
import com.echoai.domain.SoundProfile
import com.echoai.domain.SoundHistoryManager
import com.echoai.domain.UrgencyClassifier
import com.echoai.ml.SoundClassifier
import com.echoai.ml.StubSoundClassifier
import com.echoai.ml.YamnetClassifier
import com.echoai.pipeline.ClassificationStage
import com.echoai.pipeline.FusionStage
import com.echoai.pipeline.LocalizationStage
import com.echoai.sensor.RotationVectorProvider
import com.echoai.util.HapticManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var pinnedAdapter: PinnedAlertAdapter
    private lateinit var sceneChipAdapter: SceneChipAdapter

    private var pendingStart = false
    private val requestMic = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && pendingStart) startLive()
        else if (!granted) binding.statusText.text = getString(R.string.mic_permission_denied)
        pendingStart = false
    }

    // --- Pipeline (localization + IMU stack from imu-bayesian-belief, profile-aware fusion from main) ---

    private val orientationProvider by lazy { RotationVectorProvider(applicationContext) }
    private val captureManager by lazy {
        AudioCaptureManager(applicationContext, orientationProvider)
    }
    private val classifier: SoundClassifier by lazy {
        YamnetClassifier.create(applicationContext) ?: StubSoundClassifier()
    }
    private val classificationStage by lazy { ClassificationStage(classifier) }
    private val localizationStage = LocalizationStage()
    private val urgencyClassifier by lazy { UrgencyClassifier(applicationContext) }
    private val hapticManager by lazy { HapticManager(applicationContext) }
    private val fusionStage by lazy {
        FusionStage(EventTracker(urgencyClassifier = urgencyClassifier))
    }
    private val profileManager by lazy { ProfileManager(applicationContext) }
    private val historyManager by lazy { SoundHistoryManager(applicationContext) }
    private val pinnedAlertTracker by lazy { PinnedAlertTracker(applicationContext) }

    // BeliefDistribution is fed `bot_ild` (the strong within-pair ILD signal that captures
    // long-axis source direction). Positive bot_ild = source toward BOTTOM of phone
    // (deviceAngle ≈ 180°), so biasScale is negative so that cos(180°)*biasScale > 0.
    // decayRate = 0.01 per update at 8 Hz ≈ 0.08/s effective decay.
    private val belief = BeliefDistribution(
        biasScale = -0.5f,
        measurementSigma = 0.25f,
        decayRate = 0.01f,
    )

    private var pipelineJob: Job? = null
    private var liveActive = false
    private var pinnedSectionVisible = false
    private var diagnosticsLogger: DiagnosticsLogger? = null

    // Classification cache: at 8 Hz localization / 2 Hz classification, every 4th window
    // runs YAMNet and the others reuse the most-recent classification result. The reused
    // result is at most 3 hops (~375 ms) old — well within EventTracker.staleAfterNanos (3 s).
    private var lastClassification: ClassificationResult? = null
    private var classificationFrameCounter = 0

    // Yaw history for rotation-rate tracking (recent samples, capped by time window).
    private val yawHistory = ArrayDeque<Pair<Long, Float>>(24)
    private var rotateHintFrames = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        installSystemBarInsets()

        renderWordmark()

        pinnedAdapter = PinnedAlertAdapter(
            onDismiss = { alert ->
                pinnedAlertTracker.acknowledge(alert.label, alert.urgency)
                refreshPinnedSection()
            },
            onTap = { label ->
                startActivity(Intent(this, HistoryActivity::class.java).apply {
                    putExtra(HistoryActivity.EXTRA_HIGHLIGHT_LABEL, label)
                })
            },
        )
        binding.pinnedAlertsList.layoutManager = LinearLayoutManager(this)
        binding.pinnedAlertsList.adapter = pinnedAdapter

        binding.liveToggle.setOnClickListener { onLiveToggle() }
        binding.historyButton.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
        binding.clearAllButton.setOnClickListener {
            pinnedAlertTracker.acknowledgeAll()
            refreshPinnedSection()
        }
        binding.tabListening.setOnClickListener { /* already here */ }
        binding.tabProfile.setOnClickListener {
            startActivity(
                Intent(this, ProfileActivity::class.java).apply {
                    putExtra(ProfileActivity.EXTRA_PROFILE_ID, profileManager.activeProfile.value.id)
                }
            )
        }

        setupSceneChips()

        // No alerts on launch — start with the expanded button state immediately (no animation)
        applyLiveToggleState(expanded = true, animate = false)
        refreshPinnedSection(animate = false)

        observeProfiles()
    }

    private fun setupSceneChips() {
        sceneChipAdapter = SceneChipAdapter(
            onChipClick = { profileManager.setActiveProfileId(it.id) },
            onChipLongClick = { showDeleteProfileDialog(it) },
            onAddClick = {
                CreateProfileSheet.show(this) { name ->
                    val profile = profileManager.createProfile(name)
                    profileManager.setActiveProfileId(profile.id)
                }
            },
            onDragStart = { holder -> chipTouchHelper.startDrag(holder) },
        )

        binding.sceneChipRow.layoutManager =
            androidx.recyclerview.widget.LinearLayoutManager(
                this, androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL, false
            )
        binding.sceneChipRow.adapter = sceneChipAdapter

        chipTouchHelper.attachToRecyclerView(binding.sceneChipRow)
    }

    private val chipTouchHelper = androidx.recyclerview.widget.ItemTouchHelper(
        object : androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback(
            androidx.recyclerview.widget.ItemTouchHelper.LEFT or
                androidx.recyclerview.widget.ItemTouchHelper.RIGHT, 0
        ) {
            override fun isLongPressDragEnabled() = false

            override fun getMovementFlags(
                rv: androidx.recyclerview.widget.RecyclerView,
                holder: androidx.recyclerview.widget.RecyclerView.ViewHolder,
            ) = if (holder.itemViewType == SceneChipAdapter.TYPE_ADD) 0
                else makeMovementFlags(
                    androidx.recyclerview.widget.ItemTouchHelper.LEFT or
                        androidx.recyclerview.widget.ItemTouchHelper.RIGHT, 0
                )

            override fun onMove(
                rv: androidx.recyclerview.widget.RecyclerView,
                from: androidx.recyclerview.widget.RecyclerView.ViewHolder,
                to: androidx.recyclerview.widget.RecyclerView.ViewHolder,
            ): Boolean {
                if (to.itemViewType == SceneChipAdapter.TYPE_ADD) return false
                sceneChipAdapter.moveItem(from.adapterPosition, to.adapterPosition)
                return true
            }

            override fun onSwiped(
                holder: androidx.recyclerview.widget.RecyclerView.ViewHolder, dir: Int
            ) {}

            override fun onSelectedChanged(
                holder: androidx.recyclerview.widget.RecyclerView.ViewHolder?,
                actionState: Int,
            ) {
                super.onSelectedChanged(holder, actionState)
                if (actionState == androidx.recyclerview.widget.ItemTouchHelper.ACTION_STATE_DRAG) {
                    holder?.itemView?.alpha = 0.75f
                }
            }

            override fun clearView(
                rv: androidx.recyclerview.widget.RecyclerView,
                holder: androidx.recyclerview.widget.RecyclerView.ViewHolder,
            ) {
                super.clearView(rv, holder)
                holder.itemView.alpha = 1f
                profileManager.reorderProfiles(sceneChipAdapter.orderedIds())
            }
        }
    )

    private fun installSystemBarInsets() {
        val rootStartTop = binding.root.paddingTop
        val rootStartLeft = binding.root.paddingLeft
        val rootStartRight = binding.root.paddingRight
        val tabStartBottom = binding.bottomTabBar.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(
                left = rootStartLeft + systemBars.left,
                top = rootStartTop + systemBars.top,
                right = rootStartRight + systemBars.right,
            )
            binding.bottomTabBar.updatePadding(
                bottom = tabStartBottom + systemBars.bottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    override fun onStop() {
        super.onStop()
        if (liveActive) stopLive()
    }

    override fun onResume() {
        super.onResume()
        profileManager.refreshFromStorage()
    }

    private fun renderWordmark() {
        val raw = getString(R.string.app_name_html)
        binding.wordmark.text = if (Build.VERSION.SDK_INT >= 24)
            Html.fromHtml(raw, Html.FROM_HTML_MODE_LEGACY)
        else
            @Suppress("DEPRECATION") Html.fromHtml(raw)
    }

    // --- Pinned alerts ---

    private fun refreshPinnedSection(animate: Boolean = true) {
        val alerts = pinnedAlertTracker.snapshot()
        pinnedAdapter.submitList(alerts)
        val nowVisible = alerts.isNotEmpty()
        binding.pinnedAlertsSection.visibility = if (nowVisible) View.VISIBLE else View.GONE
        if (nowVisible != pinnedSectionVisible) {
            pinnedSectionVisible = nowVisible
            applyLiveToggleState(expanded = !nowVisible, animate = animate)
        }
    }

    private fun applyLiveToggleState(expanded: Boolean, animate: Boolean) {
        val dp = resources.displayMetrics.density
        val targetBtnPadH = ((if (expanded) 46 else 24) * dp).toInt()
        val targetBtnPadV = ((if (expanded) 21 else 12) * dp).toInt()
        val targetContainerPadBottom = ((if (expanded) 32 else 2) * dp).toInt()

        if (animate) {
            val fromPadH = binding.liveToggle.paddingLeft
            val fromPadV = binding.liveToggle.paddingTop
            val fromContainerPad = binding.liveToggleContainer.paddingBottom

            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 320
                interpolator = DecelerateInterpolator()
                addUpdateListener { anim ->
                    val t = anim.animatedFraction
                    val padH = (fromPadH + (targetBtnPadH - fromPadH) * t).toInt()
                    val padV = (fromPadV + (targetBtnPadV - fromPadV) * t).toInt()
                    val contPad = (fromContainerPad + (targetContainerPadBottom - fromContainerPad) * t).toInt()
                    binding.liveToggle.setPadding(padH, padV, padH, padV)
                    binding.liveToggleContainer.setPadding(
                        binding.liveToggleContainer.paddingLeft,
                        binding.liveToggleContainer.paddingTop,
                        binding.liveToggleContainer.paddingRight,
                        contPad,
                    )
                }
                start()
            }
        } else {
            binding.liveToggle.setPadding(targetBtnPadH, targetBtnPadV, targetBtnPadH, targetBtnPadV)
            binding.liveToggleContainer.setPadding(
                binding.liveToggleContainer.paddingLeft,
                binding.liveToggleContainer.paddingTop,
                binding.liveToggleContainer.paddingRight,
                targetContainerPadBottom,
            )
        }
    }

    // --- Profile chips ---

    private fun observeProfiles() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                profileManager.allProfiles.collect { profiles ->
                    sceneChipAdapter.submitProfiles(profiles, profileManager.activeProfile.value.id)
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                profileManager.activeProfile.collect { profile ->
                    fusionStage.applyProfile(profile)
                    sceneChipAdapter.setActiveId(profile.id)
                }
            }
        }
    }

    private fun showDeleteProfileDialog(profile: SoundProfile) {
        AlertDialog.Builder(this)
            .setTitle("Delete \"${profile.name}\"?")
            .setMessage("This will remove the profile and all its custom settings.")
            .setPositiveButton("Delete") { _, _ -> profileManager.deleteProfile(profile.id) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // --- Live pipeline ---

    private fun onLiveToggle() {
        if (liveActive) {
            stopLive()
        } else {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) startLive()
            else { pendingStart = true; requestMic.launch(Manifest.permission.RECORD_AUDIO) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLive() {
        if (liveActive) return
        liveActive = true

        binding.liveToggle.text = getString(R.string.stop_live)
        binding.liveToggle.setCompoundDrawablesRelativeWithIntrinsicBounds(
            R.drawable.ic_pause, 0, 0, 0
        )
        binding.statusText.text = getString(R.string.live_starting)
        binding.radarView.setListening(true)

        diagnosticsLogger = DiagnosticsLogger.start(applicationContext)
        belief.reset()
        lastClassification = null
        classificationFrameCounter = 0
        yawHistory.clear()
        rotateHintFrames = 0
        orientationProvider.start()
        binding.radarView.setOrientationProvider(orientationProvider)

        captureManager.start(lifecycleScope)

        pipelineJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                captureManager.windows.collectLatest { window ->
                    val frame = processWindow(window)
                    binding.radarView.setData(
                        events = frame.events,
                        belief = frame.beliefSnapshot,
                        phoneYawDegrees = frame.phoneYaw,
                        peakWorldAngle = frame.beliefPeakAngle,
                    )
                    binding.rotateHint.visibility =
                        if (frame.showRotateHint) View.VISIBLE else View.GONE
                    hapticManager.vibrateForHighest(frame.events)
                    updateStatus(frame.events)
                    pinnedAlertTracker.onEvents(frame.events)
                    historyManager.logHighUrgencyEvents(
                        frame.events, profileManager.activeProfile.value.name
                    )
                    withContext(Dispatchers.Main) { refreshPinnedSection() }
                }
            }
        }
    }

    private fun stopLive() {
        liveActive = false
        captureManager.stop()
        binding.radarView.setOrientationProvider(null)
        orientationProvider.stop()
        diagnosticsLogger?.close()
        diagnosticsLogger = null
        pipelineJob?.cancel()
        pipelineJob = null

        binding.liveToggle.text = getString(R.string.start_live)
        binding.liveToggle.setCompoundDrawablesRelativeWithIntrinsicBounds(
            R.drawable.ic_mic, 0, 0, 0
        )
        binding.statusText.text = getString(R.string.live_idle)
        binding.radarView.setListening(false)
        binding.radarView.setEvents(emptyList())
        binding.rotateHint.visibility = View.GONE

        val err = captureManager.lastErrorMessage()
        if (err != null) binding.statusText.text = "Stopped — error: $err"
    }

    private data class Frame(
        val events: List<SoundEvent>,
        val beliefSnapshot: FloatArray,
        val phoneYaw: Float,
        val beliefPeakAngle: Float,
        val beliefIntensity: Float,
        val showRotateHint: Boolean,
    )

    private suspend fun processWindow(window: AudioWindow): Frame = coroutineScope {
        // YAMNet runs every Nth window; localization runs every window. Between
        // classifications we reuse the cached result (the dominant source rarely
        // changes within ~250 ms, so this is a safe assumption for stage-(a) attribution).
        val localizeJob = async(Dispatchers.Default) { localizationStage.localizeMultiScale(window) }
        val shouldClassify = (classificationFrameCounter % CLASSIFY_EVERY_N) == 0
        classificationFrameCounter++
        val classification = if (shouldClassify) {
            val fresh = withContext(Dispatchers.Default) { classificationStage.classify(window) }
            lastClassification = fresh
            fresh
        } else {
            lastClassification ?: ClassificationResult(window.frameNumber, emptyList())
        }
        val multi = localizeJob.await()
        val events = withContext(Dispatchers.Default) {
            fusionStage.process(classification, multi.full)
        }
        val devicePos = events.firstOrNull()?.devicePosition
        val azX = devicePos?.azimuthFromBottomIld()
        val azLag = devicePos?.azimuthFromLag()

        // Rotational-aperture localizer update. Only feed the belief when (a) the IMU has
        // produced a sample and (b) YAMNet detected a sound with reasonable confidence —
        // updating during silence pushes phantom peaks toward the broadside cone.
        // Use the 250 ms peak-energy sub-window's ILD: it changes more between consecutive
        // emissions than the full-window ILD (consecutive 1 s windows share 87.5% of audio
        // at 8 Hz, so their full-window ILDs are highly correlated).
        val yaw = orientationProvider.yawDegrees()
        // YAMNet has a literal "Silence" class (index 494) that dominates quiet windows
        // with confidence ~0.95; the stub classifier emits the same label. Treat it as the
        // absence of a real sound — scan topK for the first non-Silence label above the
        // confidence floor instead of just looking at top-1.
        val activeLabel = classification.topK.firstOrNull {
            it.confidence > 0.3f && !it.label.equals(SILENCE_LABEL, ignoreCase = true)
        }
        val hasConfidentAudio = activeLabel != null
        if (yaw != null && hasConfidentAudio) {
            belief.update(multi.sub.bottomIld, yaw)
        } else {
            // Silence (or no IMU sample yet) — fade the belief faster toward uniform so a
            // stale peak doesn't linger.
            belief.decayOnly(SILENCE_DECAY_RATE)
        }

        val peakAngle = belief.argmaxDegrees()
        val intensity = belief.maxBelief()

        // Rotation hint: only nudges the user when there's an active sound but the phone
        // isn't moving — without rotation the rotational-aperture localizer can't pin down
        // a world-frame direction. Stays hidden during silence.
        recordYaw(window.captureTimestampNanos, yaw)
        val lowRotation = cumulativeRotationDegrees() < LOW_ROTATION_DEG
        if (hasConfidentAudio && lowRotation) rotateHintFrames++ else rotateHintFrames = 0
        val showRotateHint = rotateHintFrames >= ROTATE_HINT_DEBOUNCE_FRAMES

        diagnosticsLogger?.log(
            window = window,
            topLabel = classification.topK.firstOrNull(),
            full = multi.full,
            sub = multi.sub,
            azimuthIldDeg = azX,
            azimuthLagDeg = azLag,
            phoneYawDeg = yaw,
            beliefPeakDeg = peakAngle,
            beliefIntensity = intensity,
        )

        Frame(
            events = events,
            beliefSnapshot = belief.snapshot(),
            phoneYaw = yaw ?: 0f,
            beliefPeakAngle = peakAngle,
            beliefIntensity = intensity,
            showRotateHint = showRotateHint,
        )
    }

    private fun recordYaw(timestampNanos: Long, yawDeg: Float?) {
        if (yawDeg == null) return
        yawHistory.addLast(timestampNanos to yawDeg)
        val cutoff = timestampNanos - YAW_WINDOW_NANOS
        while (yawHistory.isNotEmpty() && yawHistory.first().first < cutoff) yawHistory.removeFirst()
    }

    /** Sum of absolute yaw deltas across the retained history, with shortest-arc wrapping. */
    private fun cumulativeRotationDegrees(): Float {
        if (yawHistory.size < 2) return 0f
        var total = 0f
        val iter = yawHistory.iterator()
        var prev = iter.next().second
        while (iter.hasNext()) {
            val cur = iter.next().second
            var d = cur - prev
            if (d > 180f) d -= 360f
            if (d < -180f) d += 360f
            total += kotlin.math.abs(d)
            prev = cur
        }
        return total
    }

    private fun updateStatus(events: List<SoundEvent>) {
        binding.statusText.text = when {
            events.isEmpty() -> getString(R.string.listening_no_sounds)
            events.size == 1 -> getString(R.string.listening_one_sound, events.first().label)
            else -> getString(R.string.listening_n_sounds, events.size)
        }
    }

    companion object {
        /** Run YAMNet on every Nth pipeline window. With HOP_FRAMES = 2000 (8 Hz), N=4
         *  gives 2 Hz classification while localization + belief update runs at full 8 Hz. */
        private const val CLASSIFY_EVERY_N = 4
        /** Decay rate applied per silent window. 10× the normal 0.01 in BeliefDistribution
         *  → ~57%/s convergence to uniform at 8 Hz, so the halo fades within ~2 s of silence. */
        private const val SILENCE_DECAY_RATE = 0.10f
        /** YAMNet class 494 / stub classifier label that means "no sound to localize". */
        private const val SILENCE_LABEL = "Silence"
        /** Sliding window over which cumulative yaw change is summed for the rotate hint. */
        private const val YAW_WINDOW_NANOS = 2_000_000_000L
        /** Below this cumulative rotation over [YAW_WINDOW_NANOS], the phone counts as "still". */
        private const val LOW_ROTATION_DEG = 15f
        /** Consecutive frames the (audio + still) condition must hold before showing the hint
         *  (~1.5 s at 8 Hz). Hides immediately when either condition flips. */
        private const val ROTATE_HINT_DEBOUNCE_FRAMES = 12
    }
}

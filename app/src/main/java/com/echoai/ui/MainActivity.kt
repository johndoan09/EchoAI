package com.echoai.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.echoai.R
import com.echoai.audio.AudioCaptureManager
import com.echoai.audio.AudioWindow
import com.echoai.audio.MicCapabilityProbe
import com.echoai.audio.MultiStreamProbe
import com.echoai.audio.StereoMicTest
import com.echoai.databinding.ActivityMainBinding
import com.echoai.diagnostics.DiagnosticsLogger
import com.echoai.domain.BeliefDistribution
import com.echoai.domain.ClassificationResult
import com.echoai.domain.LocalizationResult
import com.echoai.domain.SoundEvent
import com.echoai.ml.SoundClassifier
import com.echoai.ml.StubSoundClassifier
import com.echoai.ml.YamnetClassifier
import com.echoai.pipeline.ClassificationStage
import com.echoai.pipeline.FusionStage
import com.echoai.pipeline.LocalizationStage
import com.echoai.sensor.RotationVectorProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private enum class PendingAction { STEREO_TEST, MIC_PROBE, MULTI_PROBE, LIVE_START }

    private var pending: PendingAction? = null
    private val requestMic = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) pending?.let(::dispatch)
        else binding.results.text = getString(R.string.mic_permission_denied)
        pending = null
    }

    // Pipeline. YamnetClassifier is the real model; falls back to StubSoundClassifier
    // only if the .tflite asset can't be loaded.
    private val orientationProvider by lazy { RotationVectorProvider(applicationContext) }
    private val captureManager by lazy { AudioCaptureManager(applicationContext, orientationProvider) }
    private val classifier: SoundClassifier by lazy {
        YamnetClassifier.create(applicationContext) ?: StubSoundClassifier()
    }
    private val classifierBackend: String by lazy {
        (classifier as? YamnetClassifier)?.backend ?: "STUB"
    }
    private val classificationStage by lazy { ClassificationStage(classifier) }
    private val localizationStage = LocalizationStage()
    private val fusionStage = FusionStage()
    // BeliefDistribution is fed `bot_ild` (the strong within-pair ILD signal that captures
    // long-axis source direction). Positive bot_ild = source toward BOTTOM of phone
    // (deviceAngle ≈ 180°), so biasScale is negative so that cos(180°)*biasScale > 0.
    // decayRate = 0.01 per update at 8 Hz ≈ 0.08/s effective decay (time-domain behavior
    // preserved across the 2 → 4 → 8 Hz cadence bumps).
    private val belief = BeliefDistribution(
        biasScale = -0.5f,
        measurementSigma = 0.25f,
        decayRate = 0.01f,
    )

    private var pipelineJob: Job? = null
    private var liveActive = false
    private var diagnosticsLogger: DiagnosticsLogger? = null
    private var diagnosticsFilePath: String? = null

    // Classification cache: at 4 Hz localization / 2 Hz classification, every other window
    // skips YAMNet and reuses the most-recent classification result. The reused result is
    // at most one hop (~250 ms) old — well within EventTracker.staleAfterNanos (3 s).
    private var lastClassification: ClassificationResult? = null
    private var classificationFrameCounter = 0

    // Yaw history for rotation-rate tracking (recent samples, capped by time window).
    private val yawHistory = ArrayDeque<Pair<Long, Float>>(24)
    private var rotateHintFrames = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.liveToggle.setOnClickListener { onLiveToggle() }
        binding.resetBelief.setOnClickListener { onResetBelief() }
        binding.runTest.setOnClickListener { withMic(PendingAction.STEREO_TEST) }
        binding.probeMics.setOnClickListener { withMic(PendingAction.MIC_PROBE) }
        binding.sweepMatrix.setOnClickListener { withMic(PendingAction.MULTI_PROBE) }
        binding.results.text = getString(R.string.live_idle)
    }

    override fun onStop() {
        super.onStop()
        if (liveActive) stopLive()
    }

    private fun withMic(action: PendingAction) {
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) dispatch(action)
        else {
            pending = action
            requestMic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun dispatch(action: PendingAction) = when (action) {
        PendingAction.STEREO_TEST -> runStereoTest()
        PendingAction.MIC_PROBE -> runMicProbe()
        PendingAction.MULTI_PROBE -> runMultiProbe()
        PendingAction.LIVE_START -> startLive()
    }

    private fun onLiveToggle() {
        if (liveActive) stopLive() else withMic(PendingAction.LIVE_START)
    }

    private fun onResetBelief() {
        if (!liveActive) return
        belief.reset()
        binding.radar.setBelief(belief.snapshot(), orientationProvider.yawDegrees() ?: 0f, 0f)
    }

    @SuppressLint("MissingPermission")
    private fun startLive() {
        if (liveActive) return
        liveActive = true
        binding.liveToggle.text = getString(R.string.stop_live)
        binding.runTest.isEnabled = false
        binding.probeMics.isEnabled = false
        binding.sweepMatrix.isEnabled = false
        binding.results.text = getString(R.string.live_starting)

        diagnosticsLogger = DiagnosticsLogger.start(applicationContext).also {
            diagnosticsFilePath = it.file.absolutePath
        }
        belief.reset()
        lastClassification = null
        classificationFrameCounter = 0
        yawHistory.clear()
        rotateHintFrames = 0
        binding.resetBelief.isEnabled = true
        orientationProvider.start()
        binding.radar.setOrientationProvider(orientationProvider)
        captureManager.start(lifecycleScope)

        pipelineJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                captureManager.windows.collectLatest { window ->
                    val frame = processWindow(window)
                    binding.results.text = frame.text
                    binding.radar.setData(
                        events = frame.events,
                        belief = frame.beliefSnapshot,
                        phoneYawDegrees = frame.phoneYaw,
                        peakWorldAngle = frame.beliefPeakAngle,
                    )
                    binding.rotateHint.visibility =
                        if (frame.showRotateHint) View.VISIBLE else View.GONE
                }
            }
        }
    }

    private fun stopLive() {
        liveActive = false
        captureManager.stop()
        binding.radar.setOrientationProvider(null)
        orientationProvider.stop()
        pipelineJob?.cancel()
        pipelineJob = null
        diagnosticsLogger?.close()
        diagnosticsLogger = null
        binding.liveToggle.text = getString(R.string.start_live)
        binding.runTest.isEnabled = true
        binding.probeMics.isEnabled = true
        binding.sweepMatrix.isEnabled = true
        binding.radar.setEvents(emptyList())
        binding.radar.setBelief(FloatArray(0), 0f, 0f)
        binding.resetBelief.isEnabled = false
        binding.rotateHint.visibility = View.GONE
        val err = captureManager.lastErrorMessage()
        binding.results.text = when {
            err != null -> "Capture stopped — error: $err"
            diagnosticsFilePath != null -> "Capture stopped.\nDiagnostics CSV: $diagnosticsFilePath\n\nPull with:\n  adb pull \"$diagnosticsFilePath\""
            else -> getString(R.string.live_idle)
        }
    }

    private data class Frame(
        val text: String,
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
        val topLabel = classification.topK.firstOrNull()
        val activeLabel = classification.topK.firstOrNull {
            it.confidence > 0.3f && !it.label.equals(SILENCE_LABEL, ignoreCase = true)
        }
        val hasConfidentAudio = activeLabel != null
        if (yaw != null && hasConfidentAudio) {
            belief.update(multi.sub.bottomIld, yaw)
        } else {
            // Silence (or no IMU sample yet) — fade the belief faster toward uniform so a
            // stale peak doesn't linger. update() reverts to the normal decayRate when
            // sound returns, so no flag-flipping is needed.
            belief.decayOnly(SILENCE_DECAY_RATE)
        }

        // Render the radar arrow at the raw belief argmax so it tracks measurement updates
        // in real time (no rate-limited EMA). The Bayesian decay/likelihood step inside
        // belief.update already provides temporal smoothing of the underlying distribution;
        // an extra display-side EMA only added perceptible lag between the halo and arrow.
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
            text = renderLive(
                window, classification.topK, multi.full, multi.sub, events, yaw,
                peakAngle, intensity,
            ),
            events = events,
            beliefSnapshot = belief.snapshot(),
            phoneYaw = yaw ?: 0f,
            beliefPeakAngle = peakAngle,
            beliefIntensity = intensity,
            showRotateHint = showRotateHint,
        )
    }

    private fun renderLive(
        window: AudioWindow,
        topK: List<com.echoai.ml.LabeledScore>,
        localization: LocalizationResult,
        sub: LocalizationResult,
        events: List<SoundEvent>,
        yawDegrees: Float?,
        beliefPeakAngle: Float,
        beliefIntensity: Float,
    ): String = buildString {
        appendLine("DIAGNOSTIC  win #${window.frameNumber}  ${window.sampleRate}Hz  $classifierBackend")
        appendLine("Hold flat, ROTATE the phone slowly while a sound source is active.")
        appendLine("Belief halo around the radar shows world-frame source direction.")
        appendLine()

        appendLine("IMU / belief:")
        appendLine("  phone yaw  ${yawDegrees?.let { "%6.1f°".format(it) } ?: "    ?  "}  (world heading of TOP edge)")
        appendLine("  belief peak  ${"%6.1f°".format(beliefPeakAngle)}  i=${"%.3f".format(beliefIntensity)}")
        appendLine()

        appendLine("Per-channel RMS (max ${RMS_BAR_MAX.toInt()}):")
        appendLine("  bot L  ${bar(localization.bottomLeftRms, RMS_BAR_MAX)}  ${"%5.0f".format(localization.bottomLeftRms)}")
        appendLine("  bot R  ${bar(localization.bottomRightRms, RMS_BAR_MAX)}  ${"%5.0f".format(localization.bottomRightRms)}")
        appendLine("  bk  L  ${bar(localization.backLeftRms, RMS_BAR_MAX)}  ${"%5.0f".format(localization.backLeftRms)}")
        appendLine("  bk  R  ${bar(localization.backRightRms, RMS_BAR_MAX)}  ${"%5.0f".format(localization.backRightRms)}")
        appendLine()

        appendLine("Within-pair ILD  (R−L)/(R+L):")
        appendLine("  bot   ${centeredBar(localization.bottomIld)}  ${"%+.3f".format(localization.bottomIld)}  → radar X (L/R)")
        appendLine("  back  ${centeredBar(localization.backIld)}  ${"%+.3f".format(localization.backIld)}  → radar Y (TOP/BOT)?")
        appendLine()

        appendLine("Within-pair lag (samples, ±${LAG_RANGE}):")
        appendLine(
            "  bot   ${centeredBar(localization.withinPairBottom.samples / LAG_RANGE.toFloat())}  ${"%+3d".format(localization.withinPairBottom.samples)}  c=${"%.2f".format(localization.withinPairBottom.confidence)}"
        )
        appendLine(
            "  back  ${centeredBar(localization.withinPairBack.samples / LAG_RANGE.toFloat())}  ${"%+3d".format(localization.withinPairBack.samples)}  c=${"%.2f".format(localization.withinPairBack.confidence)}"
        )
        appendLine()

        appendLine("Cross-pair (front/back, weak signal — AGC-suppressed):")
        appendLine("  fb_bias    ${centeredBar(localization.frontBackBias)}  ${"%+.3f".format(localization.frontBackBias)}")
        appendLine(
            "  cross lag  ${centeredBar(localization.crossPairLag.samples / 100f)}  ${"%+4d".format(localization.crossPairLag.samples)}  c=${"%.2f".format(localization.crossPairLag.confidence)}"
        )
        appendLine()

        // Top event azimuth — both axes side-by-side for radar interpretation.
        val devicePos = events.firstOrNull()?.devicePosition
        val azX = devicePos?.azimuthFromBottomIld()
        val azY = devicePos?.yAxisFromBackIld()
        val azLag = devicePos?.azimuthFromLag()
        appendLine("Top-event radar position:")
        appendLine("  X (L/R)        ${azX?.let { "%+6.1f°".format(it) } ?: "  ?   "}  bottom_ild")
        appendLine("  Y (TOP/BOT)    ${azY?.let { "%+6.1f°".format(it) } ?: "  ?   "}  back_ild")
        appendLine("  diag: lag-az   ${azLag?.let { "%+6.1f°".format(it) } ?: "  ?   "}  bot/back lag")
        appendLine()

        appendLine("Top-${topK.size} classification:")
        if (topK.isEmpty()) appendLine("  (no labels)")
        for (s in topK) {
            appendLine("  %-16s ${bar(s.confidence, 1f)}  %.2f".format(s.label.take(16), s.confidence))
        }
        appendLine()

        appendLine("Active events (${events.size}):")
        if (events.isEmpty()) appendLine("  (none)")
        for (e in events) {
            val ageMs = (window.captureTimestampNanos - e.firstSeenTimestampNanos) / 1_000_000
            val xa = e.devicePosition.azimuthFromBottomIld()
            val ya = e.devicePosition.yAxisFromBackIld()
            val xText = xa?.let { "X=%+5.1f°".format(it) } ?: "X=  ?  "
            val yText = ya?.let { "Y=%+5.1f°".format(it) } ?: "Y=  ?  "
            appendLine(
                "  %-16s ${bar(e.confidence, 1f)}  $xText  $yText  age=%4dms".format(
                    e.label.take(16), ageMs
                )
            )
        }
    }

    private fun bar(value: Float, max: Float, width: Int = 12): String {
        val frac = (value / max).coerceIn(0f, 1f)
        val full = (frac * width).toInt()
        val sb = StringBuilder(width)
        repeat(full) { sb.append('▆') }
        repeat(width - full) { sb.append('▁') }
        return sb.toString()
    }

    /** value in [-1, +1] → 13-cell bar with center marker | and current marker *. */
    private fun centeredBar(value: Float, width: Int = 13): String {
        val v = value.coerceIn(-1f, 1f)
        val center = width / 2
        val pos = (center + v * center).toInt().coerceIn(0, width - 1)
        val sb = StringBuilder(width + 2)
        sb.append('[')
        for (i in 0 until width) {
            sb.append(when {
                i == pos -> '*'
                i == center -> '|'
                else -> '─'
            })
        }
        sb.append(']')
        return sb.toString()
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

    companion object {
        /** RMS bar full-scale. CAMCORDER's AGC keeps speech around 1000–3000; 4000 gives
         *  visible response without saturating in normal use. */
        private const val RMS_BAR_MAX = 4000f
        /** Within-pair lag search range; matches MAX_LAG_WITHIN in LocalizationStage. */
        private const val LAG_RANGE = 16
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

    @SuppressLint("MissingPermission")
    private fun runStereoTest() {
        binding.runTest.isEnabled = false
        binding.results.text = getString(R.string.recording_in_progress)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { StereoMicTest.run() }
            binding.results.text = result.message
            binding.runTest.isEnabled = true
        }
    }

    @SuppressLint("MissingPermission")
    private fun runMicProbe() {
        setDiagBusy(R.string.probing_mics)
        lifecycleScope.launch {
            val report = withContext(Dispatchers.IO) { MicCapabilityProbe.run(applicationContext) }
            binding.results.text = report
            setDiagIdle()
        }
    }

    @SuppressLint("MissingPermission")
    private fun runMultiProbe() {
        setDiagBusy(R.string.sweeping_matrix)
        lifecycleScope.launch {
            val report = withContext(Dispatchers.IO) { MultiStreamProbe.run(applicationContext) }
            binding.results.text = report
            setDiagIdle()
        }
    }

    private fun setDiagBusy(messageRes: Int) {
        binding.liveToggle.isEnabled = false
        binding.runTest.isEnabled = false
        binding.probeMics.isEnabled = false
        binding.sweepMatrix.isEnabled = false
        binding.results.text = getString(messageRes)
    }

    private fun setDiagIdle() {
        binding.liveToggle.isEnabled = true
        binding.runTest.isEnabled = true
        binding.probeMics.isEnabled = true
        binding.sweepMatrix.isEnabled = true
    }
}

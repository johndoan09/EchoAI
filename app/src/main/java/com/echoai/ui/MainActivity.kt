package com.echoai.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
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
    // long-axis source direction). bot_ild swings ±0.6 in confident speech frames, so
    // biasScale = 0.5 matches the empirical range; sigma = 0.25 allows for noise without
    // collapsing belief on a single anomalous reading. decayRate = 0.20 gives ~1 s
    // half-life so the belief tracks the *current* dominant source rather than averaging
    // across past sources at different positions.
    private val belief = BeliefDistribution(
        biasScale = 0.5f,
        measurementSigma = 0.25f,
        decayRate = 0.20f,
    )

    private var pipelineJob: Job? = null
    private var liveActive = false
    private var diagnosticsLogger: DiagnosticsLogger? = null
    private var diagnosticsFilePath: String? = null

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
        // Push a flat snapshot to the radar immediately so the halo clears without
        // waiting for the next window emit.
        binding.radar.setBelief(belief.snapshot(), orientationProvider.yawDegrees() ?: 0f)
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
        binding.resetBelief.isEnabled = true
        orientationProvider.start()
        captureManager.start(lifecycleScope)

        pipelineJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                captureManager.windows.collectLatest { window ->
                    val frame = processWindow(window)
                    binding.results.text = frame.text
                    binding.radar.setEvents(frame.events)
                    binding.radar.setBelief(frame.beliefSnapshot, frame.phoneYaw)
                }
            }
        }
    }

    private fun stopLive() {
        liveActive = false
        captureManager.stop()
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
        binding.radar.setBelief(FloatArray(0), 0f)
        binding.resetBelief.isEnabled = false
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
    )

    private suspend fun processWindow(window: AudioWindow): Frame = coroutineScope {
        val classifyJob = async(Dispatchers.Default) { classificationStage.classify(window) }
        val localizeJob = async(Dispatchers.Default) { localizationStage.localizeMultiScale(window) }
        val classification = classifyJob.await()
        val multi = localizeJob.await()
        val events = withContext(Dispatchers.Default) {
            fusionStage.process(classification, multi.full)
        }
        val devicePos = events.firstOrNull()?.devicePosition
        val azX = devicePos?.azimuthFromBottomIld()
        val azLag = devicePos?.azimuthFromLag()

        // Rotational-aperture localizer update. Feed bot_ild (within-pair ILD = the long-
        // axis bias signal that survives AGC). Skip if IMU hasn't produced a sample yet.
        val yaw = orientationProvider.yawDegrees()
        if (yaw != null) {
            belief.update(multi.full.bottomIld, yaw)
        }

        diagnosticsLogger?.log(
            window = window,
            topLabel = classification.topK.firstOrNull(),
            full = multi.full,
            sub = multi.sub,
            azimuthIldDeg = azX,
            azimuthLagDeg = azLag,
            phoneYawDeg = yaw,
            beliefPeakDeg = belief.argmaxDegrees(),
            beliefIntensity = belief.maxBelief(),
        )

        Frame(
            text = renderLive(window, classification.topK, multi.full, multi.sub, events, yaw),
            events = events,
            beliefSnapshot = belief.snapshot(),
            phoneYaw = yaw ?: 0f,
        )
    }

    private fun renderLive(
        window: AudioWindow,
        topK: List<com.echoai.ml.LabeledScore>,
        localization: LocalizationResult,
        sub: LocalizationResult,
        events: List<SoundEvent>,
        yawDegrees: Float?,
    ): String = buildString {
        appendLine("DIAGNOSTIC  win #${window.frameNumber}  ${window.sampleRate}Hz  $classifierBackend")
        appendLine("Hold flat, ROTATE the phone slowly while a sound source is active.")
        appendLine("Belief halo around the radar shows world-frame source direction.")
        appendLine()

        appendLine("IMU / belief:")
        appendLine("  phone yaw  ${yawDegrees?.let { "%6.1f°".format(it) } ?: "    ?  "}  (world heading of TOP edge)")
        appendLine(
            "  belief peak ${"%6.1f°".format(belief.argmaxDegrees())}  intensity=${"%.3f".format(belief.maxBelief())}  (uniform=${"%.3f".format(1f / 36)})"
        )
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

    companion object {
        /** RMS bar full-scale. CAMCORDER's AGC keeps speech around 1000–3000; 4000 gives
         *  visible response without saturating in normal use. */
        private const val RMS_BAR_MAX = 4000f
        /** Within-pair lag search range; matches MAX_LAG_WITHIN in LocalizationStage. */
        private const val LAG_RANGE = 16
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

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
import com.echoai.domain.LocalizationResult
import com.echoai.domain.SoundEvent
import com.echoai.ml.SoundClassifier
import com.echoai.ml.StubSoundClassifier
import com.echoai.ml.YamnetClassifier
import com.echoai.pipeline.ClassificationStage
import com.echoai.pipeline.FusionStage
import com.echoai.pipeline.LocalizationStage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

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
    private val captureManager by lazy { AudioCaptureManager(applicationContext) }
    private val classifier: SoundClassifier by lazy {
        YamnetClassifier.create(applicationContext) ?: StubSoundClassifier()
    }
    private val classifierBackend: String by lazy {
        (classifier as? YamnetClassifier)?.backend ?: "STUB"
    }
    private val classificationStage by lazy { ClassificationStage(classifier) }
    private val localizationStage = LocalizationStage()
    private val fusionStage = FusionStage()

    private var pipelineJob: Job? = null
    private var liveActive = false
    private var diagnosticsLogger: DiagnosticsLogger? = null
    private var diagnosticsFilePath: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.liveToggle.setOnClickListener { onLiveToggle() }
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
        captureManager.start(lifecycleScope)

        pipelineJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                captureManager.windows.collectLatest { window ->
                    val frame = processWindow(window)
                    binding.results.text = frame.text
                    binding.radar.setEvents(frame.events)
                }
            }
        }
    }

    private fun stopLive() {
        liveActive = false
        captureManager.stop()
        pipelineJob?.cancel()
        pipelineJob = null
        diagnosticsLogger?.close()
        diagnosticsLogger = null
        binding.liveToggle.text = getString(R.string.start_live)
        binding.runTest.isEnabled = true
        binding.probeMics.isEnabled = true
        binding.sweepMatrix.isEnabled = true
        binding.radar.setEvents(emptyList())
        val err = captureManager.lastErrorMessage()
        binding.results.text = when {
            err != null -> "Capture stopped — error: $err"
            diagnosticsFilePath != null -> "Capture stopped.\nDiagnostics CSV: $diagnosticsFilePath\n\nPull with:\n  adb pull \"$diagnosticsFilePath\""
            else -> getString(R.string.live_idle)
        }
    }

    private data class Frame(val text: String, val events: List<SoundEvent>)

    private suspend fun processWindow(window: AudioWindow): Frame = coroutineScope {
        val classifyJob = async(Dispatchers.Default) { classificationStage.classify(window) }
        val localizeJob = async(Dispatchers.Default) { localizationStage.localizeMultiScale(window) }
        val classification = classifyJob.await()
        val multi = localizeJob.await()
        val events = withContext(Dispatchers.Default) {
            fusionStage.process(classification, multi.full)
        }
        // Compute per-channel RMS once for both render + diagnostics.
        val rmsBL = rmsOf(window.bottomLeft)
        val rmsBR = rmsOf(window.bottomRight)
        val rmsKL = rmsOf(window.backLeft)
        val rmsKR = rmsOf(window.backRight)
        val az = events.firstOrNull()?.devicePosition?.azimuthDegrees()

        diagnosticsLogger?.log(
            window = window,
            topLabel = classification.topK.firstOrNull(),
            rmsBl = rmsBL, rmsBr = rmsBR, rmsKl = rmsKL, rmsKr = rmsKR,
            full = multi.full,
            sub = multi.sub,
            azimuthDeg = az,
        )

        Frame(
            text = renderLive(
                window, classification.topK, multi.full, multi.sub, events,
                rmsBL, rmsBR, rmsKL, rmsKR,
            ),
            events = events,
        )
    }

    private fun renderLive(
        window: AudioWindow,
        topK: List<com.echoai.ml.LabeledScore>,
        localization: LocalizationResult,
        sub: LocalizationResult,
        events: List<SoundEvent>,
        rmsBL: Float, rmsBR: Float, rmsKL: Float, rmsKR: Float,
    ): String = buildString {
        appendLine("LIVE  window #${window.frameNumber}  rate=${window.sampleRate}Hz  backend=$classifierBackend")
        appendLine()

        appendLine("Channels (RMS, max=${RMS_BAR_MAX}):")
        appendLine("  bot L  ${bar(rmsBL, RMS_BAR_MAX)}  ${"%5.0f".format(rmsBL)}")
        appendLine("  bot R  ${bar(rmsBR, RMS_BAR_MAX)}  ${"%5.0f".format(rmsBR)}")
        appendLine("  bk  L  ${bar(rmsKL, RMS_BAR_MAX)}  ${"%5.0f".format(rmsKL)}")
        appendLine("  bk  R  ${bar(rmsKR, RMS_BAR_MAX)}  ${"%5.0f".format(rmsKR)}")
        appendLine()

        appendLine("Spatial:")
        appendLine("  F/B  ${centeredBar(localization.frontBackBias)}  ${"%+.2f".format(localization.frontBackBias)}")
        val approxAz = events.firstOrNull()?.devicePosition?.azimuthDegrees()
        val azStr = if (approxAz != null) "%+5.1f°".format(approxAz) else "  ?  "
        val azNorm = (approxAz ?: 0f) / 90f
        appendLine("  L/R  ${centeredBar(azNorm)}  ${azStr}  (top event)")
        appendLine("  scale 1s   |  scale 250ms (peak-energy slice)")
        appendLine(
            "  cross  lag=%+4d c=%.2f  |  lag=%+4d c=%.2f".format(
                localization.crossPairLag.samples, localization.crossPairLag.confidence,
                sub.crossPairLag.samples, sub.crossPairLag.confidence,
            )
        )
        appendLine(
            "  bot LR lag=%+3d c=%.2f   |  lag=%+3d c=%.2f".format(
                localization.withinPairBottom.samples, localization.withinPairBottom.confidence,
                sub.withinPairBottom.samples, sub.withinPairBottom.confidence,
            )
        )
        appendLine(
            "  bk  LR lag=%+3d c=%.2f   |  lag=%+3d c=%.2f".format(
                localization.withinPairBack.samples, localization.withinPairBack.confidence,
                sub.withinPairBack.samples, sub.withinPairBack.confidence,
            )
        )
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
            val az = e.devicePosition.azimuthDegrees()
            val azText = if (az != null) "az=%+5.1f°".format(az) else "az=  ?  "
            appendLine(
                "  %-16s ${bar(e.confidence, 1f)}  fb=%+.2f  %s  age=%4dms".format(
                    e.label.take(16), e.devicePosition.frontBackBias, azText, ageMs
                )
            )
        }
    }

    private fun rmsOf(s: ShortArray): Float {
        if (s.isEmpty()) return 0f
        var sumSq = 0.0
        for (v in s) sumSq += v.toDouble() * v.toDouble()
        return sqrt(sumSq / s.size).toFloat()
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

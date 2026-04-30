package com.echoai.audio

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

data class StereoMicResult(
    val ok: Boolean,
    val message: String,
    val audioSourceUsed: String,
    val sampleRate: Int,
    val framesRecorded: Int,
    val rmsLeftDbfs: Double,
    val rmsRightDbfs: Double,
    val ildDb: Double,
    val peakLeftAbs: Int,
    val peakRightAbs: Int,
    val identicalChannels: Boolean,
    val itdLagSamples: Int,
    val itdLagMicros: Double,
    val correlationAtPeak: Double,
)

object StereoMicTest {

    private const val SAMPLE_RATE = 16_000
    private const val DURATION_MS = 2_000

    // Phone mic spacing is at most a few cm, giving ITDs well under 500 µs.
    // ±16 samples @ 16 kHz = ±1 ms — generous headroom.
    private const val MAX_LAG_SAMPLES = 16

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun run(): StereoMicResult {
        val channelConfig = AudioFormat.CHANNEL_IN_STEREO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, channelConfig, encoding)
        if (minBuf <= 0) {
            return failure("AudioRecord.getMinBufferSize=$minBuf — CHANNEL_IN_STEREO not supported at 16kHz/PCM16.")
        }

        // UNPROCESSED disables AGC/NS — critical for ILD/ITD honesty. Falls back to MIC.
        val sources = listOf(
            MediaRecorder.AudioSource.UNPROCESSED to "UNPROCESSED",
            MediaRecorder.AudioSource.MIC to "MIC",
        )
        val attempts = StringBuilder()
        for ((src, name) in sources) {
            val recorder = try {
                AudioRecord(src, SAMPLE_RATE, channelConfig, encoding, minBuf * 4)
            } catch (e: IllegalArgumentException) {
                attempts.append("$name: IllegalArgumentException ${e.message}\n")
                continue
            }
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                attempts.append("$name: state != INITIALIZED\n")
                recorder.release()
                continue
            }
            return try { capture(recorder, name) } finally { recorder.release() }
        }
        return failure("No audio source initialized.\n$attempts")
    }

    private fun capture(recorder: AudioRecord, sourceName: String): StereoMicResult {
        recorder.startRecording()
        if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            return failure("recordingState != RECORDING after startRecording", sourceName)
        }

        val totalFrames = SAMPLE_RATE * DURATION_MS / 1000
        val interleaved = ShortArray(totalFrames * 2)
        var read = 0
        while (read < interleaved.size) {
            val n = recorder.read(interleaved, read, interleaved.size - read)
            if (n <= 0) break
            read += n
        }
        recorder.stop()

        val frames = read / 2
        if (frames == 0) return failure("Read 0 frames", sourceName)

        val left = ShortArray(frames)
        val right = ShortArray(frames)
        for (i in 0 until frames) {
            left[i] = interleaved[2 * i]
            right[i] = interleaved[2 * i + 1]
        }

        val rmsLDb = dbfs(rms(left))
        val rmsRDb = dbfs(rms(right))
        val ild = rmsLDb - rmsRDb
        val peakL = peakAbs(left)
        val peakR = peakAbs(right)
        val identical = channelsIdentical(left, right)
        val (lag, corr) = peakCrossCorrelation(left, right, MAX_LAG_SAMPLES)
        val lagMicros = lag * 1_000_000.0 / SAMPLE_RATE

        val verdict = when {
            identical ->
                "VERDICT: channels are bit-identical.\n" +
                    "HAL is delivering duplicated mono. GCC-PHAT will not work via\n" +
                    "AudioRecord — investigate multi-stream / AAudio routing or fall\n" +
                    "back to mono with energy-only directional hints."
            peakL == 0 && peakR == 0 ->
                "VERDICT: silence. Re-run while clapping or speaking near the phone."
            else ->
                "VERDICT: channels differ. Stereo separation is real.\n" +
                    "Tip: re-run while tapping near the top mic, then near the bottom\n" +
                    "mic — ILD sign and ITD lag should swing if true L/R routing exists."
        }

        val message = buildString {
            append("Source:  $sourceName\n")
            append("Frames:  $frames @ ${SAMPLE_RATE} Hz  (${frames * 1000 / SAMPLE_RATE} ms)\n")
            append("\n")
            append("L  RMS=${"%6.1f".format(rmsLDb)} dBFS   peak=$peakL\n")
            append("R  RMS=${"%6.1f".format(rmsRDb)} dBFS   peak=$peakR\n")
            append("ILD (L−R):  ${"%+.2f".format(ild)} dB\n")
            append("\n")
            append("Cross-correlation over ±$MAX_LAG_SAMPLES samples:\n")
            append("  peak lag = $lag samples  (${"%+.1f".format(lagMicros)} µs)\n")
            append("  corr     = ${"%.3f".format(corr)}\n")
            append("\n")
            append(verdict)
        }

        return StereoMicResult(
            ok = true,
            message = message,
            audioSourceUsed = sourceName,
            sampleRate = SAMPLE_RATE,
            framesRecorded = frames,
            rmsLeftDbfs = rmsLDb,
            rmsRightDbfs = rmsRDb,
            ildDb = ild,
            peakLeftAbs = peakL,
            peakRightAbs = peakR,
            identicalChannels = identical,
            itdLagSamples = lag,
            itdLagMicros = lagMicros,
            correlationAtPeak = corr,
        )
    }

    private fun rms(s: ShortArray): Double {
        if (s.isEmpty()) return 0.0
        var sum = 0.0
        for (v in s) {
            val d = v.toDouble()
            sum += d * d
        }
        return sqrt(sum / s.size)
    }

    private fun dbfs(rmsValue: Double): Double {
        if (rmsValue < 1e-9) return -120.0
        return 20.0 * log10(rmsValue / 32767.0)
    }

    private fun peakAbs(s: ShortArray): Int {
        var p = 0
        for (v in s) {
            val a = abs(v.toInt())
            if (a > p) p = a
        }
        return p
    }

    private fun channelsIdentical(l: ShortArray, r: ShortArray): Boolean {
        if (l.size != r.size) return false
        for (i in l.indices) if (l[i] != r[i]) return false
        return true
    }

    /**
     * Plain time-domain cross-correlation, normalized to [-1, 1] by the geometric mean
     * of channel energies. Positive lag = R is delayed vs L.
     */
    private fun peakCrossCorrelation(l: ShortArray, r: ShortArray, maxLag: Int): Pair<Int, Double> {
        val n = minOf(l.size, r.size)
        var sumL2 = 0.0
        var sumR2 = 0.0
        for (i in 0 until n) {
            sumL2 += l[i].toDouble() * l[i].toDouble()
            sumR2 += r[i].toDouble() * r[i].toDouble()
        }
        val denom = sqrt(sumL2 * sumR2)
        if (denom < 1e-9) return 0 to 0.0

        var bestLag = 0
        var bestCorr = Double.NEGATIVE_INFINITY
        for (lag in -maxLag..maxLag) {
            val start = if (lag >= 0) lag else 0
            val end = if (lag >= 0) n else n + lag
            var sum = 0.0
            for (i in start until end) {
                sum += l[i].toDouble() * r[i - lag].toDouble()
            }
            val corr = sum / denom
            if (corr > bestCorr) {
                bestCorr = corr
                bestLag = lag
            }
        }
        return bestLag to bestCorr
    }

    private fun failure(msg: String, src: String = "n/a") = StereoMicResult(
        ok = false, message = msg, audioSourceUsed = src,
        sampleRate = SAMPLE_RATE, framesRecorded = 0,
        rmsLeftDbfs = -120.0, rmsRightDbfs = -120.0, ildDb = 0.0,
        peakLeftAbs = 0, peakRightAbs = 0, identicalChannels = false,
        itdLagSamples = 0, itdLagMicros = 0.0, correlationAtPeak = 0.0,
    )
}

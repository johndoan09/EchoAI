package com.echoai.pipeline

import com.echoai.audio.AudioWindow
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Lightweight foreground detector for noisy rooms.
 *
 * This does not denoise the waveform fed to YAMNet. Instead it runs a parallel
 * detection path that tracks the room's rolling high-frequency floor and looks
 * for short onsets. Snaps, claps, knocks, and taps have much sharper high-band
 * energy than steady crowd speech, HVAC, or room rumble.
 */
class TransientNoiseGate {
    private var highFloor: Float? = null
    private var broadbandFloor: Float? = null

    fun analyze(window: AudioWindow): TransientGateResult {
        val mono = downmix4(
            window.bottomLeft, window.bottomRight,
            window.backLeft, window.backRight,
        )
        val frameSize = (window.sampleRate / FRAMES_PER_SECOND).coerceAtLeast(1)
        val frameCount = (mono.size / frameSize).coerceAtLeast(1)

        var peakHigh = 0f
        var sumHigh = 0f
        var sumBroad = 0f
        val highs = FloatArray(frameCount)

        for (frame in 0 until frameCount) {
            val start = frame * frameSize
            val end = minOf(start + frameSize, mono.size)
            val high = highFrequencyProxyRms(mono, start, end)
            val broad = rms(mono, start, end)
            highs[frame] = high
            if (high > peakHigh) peakHigh = high
            sumHigh += high
            sumBroad += broad
        }

        highs.sort()
        val medianHigh = highs[highs.size / 2]
        val meanHigh = sumHigh / frameCount
        val meanBroad = sumBroad / frameCount

        val floorHigh = highFloor ?: medianHigh.coerceAtLeast(MIN_FLOOR)
        val floorBroad = broadbandFloor ?: meanBroad.coerceAtLeast(MIN_FLOOR)
        val transientScore = peakHigh / floorHigh.coerceAtLeast(MIN_FLOOR)
        val foregroundScore = meanHigh / floorHigh.coerceAtLeast(MIN_FLOOR)
        val snrDb = 20f * log10((peakHigh / floorHigh.coerceAtLeast(MIN_FLOOR)).coerceAtLeast(1e-3f))

        val transient = transientScore >= TRANSIENT_SCORE_THRESHOLD && peakHigh > MIN_EVENT_HIGH_RMS
        val foreground = transient ||
            foregroundScore >= FOREGROUND_SCORE_THRESHOLD ||
            meanBroad >= floorBroad * BROADBAND_FOREGROUND_RATIO

        updateFloors(
            medianHigh = medianHigh,
            meanBroad = meanBroad,
            transient = transient,
        )

        return TransientGateResult(
            broadbandRms = meanBroad,
            highFrequencyRms = meanHigh,
            highFrequencyPeakRms = peakHigh,
            highFrequencyFloorRms = floorHigh,
            broadbandFloorRms = floorBroad,
            transientScore = transientScore,
            foregroundScore = foregroundScore,
            snrDb = snrDb,
            isTransient = transient,
            isForeground = foreground,
        )
    }

    private fun updateFloors(medianHigh: Float, meanBroad: Float, transient: Boolean) {
        val highAlpha = if (transient) SLOW_FLOOR_ALPHA else FAST_FLOOR_ALPHA
        val broadAlpha = if (transient) SLOW_FLOOR_ALPHA else FAST_FLOOR_ALPHA
        highFloor = ema(highFloor, medianHigh.coerceAtLeast(MIN_FLOOR), highAlpha)
        broadbandFloor = ema(broadbandFloor, meanBroad.coerceAtLeast(MIN_FLOOR), broadAlpha)
    }

    private fun ema(previous: Float?, current: Float, alpha: Float): Float =
        previous?.let { it * (1f - alpha) + current * alpha } ?: current

    private fun downmix4(a: ShortArray, b: ShortArray, c: ShortArray, d: ShortArray): ShortArray {
        val n = minOf(a.size, b.size, c.size, d.size)
        val out = ShortArray(n)
        for (i in 0 until n) {
            val sum = a[i].toInt() + b[i].toInt() + c[i].toInt() + d[i].toInt()
            out[i] = (sum / 4).toShort()
        }
        return out
    }

    /**
     * First-difference RMS is a cheap high-frequency proxy. Slow speech and rumble mostly
     * cancel; sharp attacks produce large sample-to-sample deltas.
     */
    private fun highFrequencyProxyRms(samples: ShortArray, start: Int, end: Int): Float {
        if (end - start < 2) return 0f
        var sumSq = 0.0
        for (i in start + 1 until end) {
            val diff = samples[i].toInt() - samples[i - 1].toInt()
            sumSq += diff.toDouble() * diff.toDouble()
        }
        return sqrt(sumSq / (end - start - 1)).toFloat()
    }

    private fun rms(samples: ShortArray, start: Int, end: Int): Float {
        if (end <= start) return 0f
        var sumSq = 0.0
        for (i in start until end) {
            val v = samples[i].toDouble()
            sumSq += v * v
        }
        return sqrt(sumSq / (end - start)).toFloat()
    }

    companion object {
        private const val FRAMES_PER_SECOND = 50 // 20 ms frames.
        private const val MIN_FLOOR = 1f
        private const val MIN_EVENT_HIGH_RMS = 80f
        private const val TRANSIENT_SCORE_THRESHOLD = 2.5f
        private const val FOREGROUND_SCORE_THRESHOLD = 1.35f
        private const val BROADBAND_FOREGROUND_RATIO = 1.6f
        private const val FAST_FLOOR_ALPHA = 0.08f
        private const val SLOW_FLOOR_ALPHA = 0.01f
    }
}

data class TransientGateResult(
    val broadbandRms: Float,
    val highFrequencyRms: Float,
    val highFrequencyPeakRms: Float,
    val highFrequencyFloorRms: Float,
    val broadbandFloorRms: Float,
    val transientScore: Float,
    val foregroundScore: Float,
    val snrDb: Float,
    val isTransient: Boolean,
    val isForeground: Boolean,
)

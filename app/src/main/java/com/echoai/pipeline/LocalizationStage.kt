package com.echoai.pipeline

import com.echoai.audio.AudioWindow
import com.echoai.audio.GccPhatLocalizer
import com.echoai.domain.LagSample
import com.echoai.domain.LocalizationResult
import kotlin.math.sqrt

/**
 * Per-window spatial computation. All math is on a single thread (cheap) — caller can
 * run this in parallel with [ClassificationStage] via async/await on the same window.
 *
 * Search ranges:
 *  - Cross-pair: ±100 samples (~6 ms) to absorb buffer-level sync jitter between recorders.
 *  - Within-pair: ±16 samples (~1 ms), generous for a few-cm mic spacing.
 */
class LocalizationStage(
    private val localizer: GccPhatLocalizer = GccPhatLocalizer(),
) {

    fun localize(window: AudioWindow): LocalizationResult = localizeRange(
        window, startFrame = 0, frameCount = window.frameCount,
    )

    /**
     * Multi-scale: full window + the highest-energy 250 ms subwindow within it.
     * Transient events tend to dominate one ~250 ms slice; running GCC-PHAT on just
     * that slice produces a sharper cross-correlation peak than the full 1 s, which
     * dilutes the direct-path arrival with reverberation tails and silence.
     */
    fun localizeMultiScale(window: AudioWindow): MultiScaleResult {
        val full = localize(window)
        val subFrames = window.sampleRate / 4 // 250 ms
        if (window.frameCount <= subFrames) return MultiScaleResult(full, full)

        // Energy-based pick, sliding by half a sub-window (eight overlapping candidates).
        val step = subFrames / 2
        var bestStart = 0
        var bestEnergy = -1.0
        var start = 0
        while (start + subFrames <= window.frameCount) {
            var energy = 0.0
            for (i in 0 until subFrames) {
                val v = window.bottomLeft[start + i].toDouble()
                energy += v * v
            }
            if (energy > bestEnergy) {
                bestEnergy = energy
                bestStart = start
            }
            start += step
        }
        val sub = localizeRange(window, bestStart, subFrames)
        return MultiScaleResult(full = full, sub = sub, subStartFrame = bestStart, subFrameCount = subFrames)
    }

    private fun localizeRange(window: AudioWindow, startFrame: Int, frameCount: Int): LocalizationResult {
        val bL = window.bottomLeft.copyOfRange(startFrame, startFrame + frameCount)
        val bR = window.bottomRight.copyOfRange(startFrame, startFrame + frameCount)
        val kL = window.backLeft.copyOfRange(startFrame, startFrame + frameCount)
        val kR = window.backRight.copyOfRange(startFrame, startFrame + frameCount)

        val bottomMono = mix(bL, bR)
        val backMono = mix(kL, kR)

        // Per-channel RMS (for ILD) and per-pair mono RMS (for front/back bias).
        val bLRms = rms(bL)
        val bRRms = rms(bR)
        val kLRms = rms(kL)
        val kRRms = rms(kR)
        val bottomRms = rms(bottomMono)
        val backRms = rms(backMono)

        val total = bottomRms + backRms
        val frontBackBias = if (total > 1e-6f) (bottomRms - backRms) / total else 0f

        // ILD = (R - L) / (R + L), positive = source closer to the right channel.
        val bottomIld = ildOf(bLRms, bRRms)
        val backIld = ildOf(kLRms, kRRms)

        val crossPair = localizer.localize(bottomMono, backMono, MAX_LAG_CROSS).toLagSample()
        val withinBottom = localizer.localize(bL, bR, MAX_LAG_WITHIN).toLagSample()
        val withinBack = localizer.localize(kL, kR, MAX_LAG_WITHIN).toLagSample()

        return LocalizationResult(
            frameNumber = window.frameNumber,
            captureTimestampNanos = window.captureTimestampNanos,
            sampleRate = window.sampleRate,
            bottomLeftRms = bLRms,
            bottomRightRms = bRRms,
            backLeftRms = kLRms,
            backRightRms = kRRms,
            bottomRms = bottomRms,
            backRms = backRms,
            frontBackBias = frontBackBias,
            bottomIld = bottomIld,
            backIld = backIld,
            crossPairLag = crossPair,
            withinPairBottom = withinBottom,
            withinPairBack = withinBack,
            worldOrientation = window.worldOrientation,
        )
    }

    private fun ildOf(lRms: Float, rRms: Float): Float {
        val sum = lRms + rRms
        return if (sum > 1e-6f) (rRms - lRms) / sum else 0f
    }

    private fun mix(l: ShortArray, r: ShortArray): ShortArray {
        val n = minOf(l.size, r.size)
        val out = ShortArray(n)
        for (i in 0 until n) {
            out[i] = ((l[i].toInt() + r[i].toInt()) / 2).toShort()
        }
        return out
    }

    private fun rms(s: ShortArray): Float {
        if (s.isEmpty()) return 0f
        var sumSq = 0.0
        for (v in s) sumSq += v.toDouble() * v.toDouble()
        return sqrt(sumSq / s.size).toFloat()
    }

    private fun com.echoai.audio.LagResult.toLagSample(): LagSample =
        LagSample(samples = lagSamples, confidence = confidence)

    companion object {
        private const val MAX_LAG_CROSS = 100
        private const val MAX_LAG_WITHIN = 16
    }
}

/**
 * Pair of LocalizationResults: one for the full window, one for the highest-energy
 * 250 ms slice within it. Useful for diagnostics — compare to see if the short window
 * gives more consistent / confident peaks for transient events.
 */
data class MultiScaleResult(
    val full: LocalizationResult,
    val sub: LocalizationResult,
    val subStartFrame: Int = 0,
    val subFrameCount: Int = 0,
)


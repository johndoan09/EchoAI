package com.echoai.audio

import kotlin.math.sqrt

/**
 * Cross-correlation lag estimator. Named GccPhatLocalizer for forward compatibility —
 * the prototype uses plain time-domain normalized cross-correlation, which is fine for
 * the search ranges and signal lengths we're using (within-pair: ~16000 samples × ±16
 * lag; cross-pair: ~16000 samples × ±100 lag, still under ~5 ms on-device).
 *
 * TODO(v1.5): swap implementation for FFT-based GCC-PHAT (with phase transform
 * weighting) for better robustness in reverberant environments. Needs an FFT library
 * (JTransforms `com.github.wendykierp:JTransforms:3.1` works); shape of the public API
 * stays the same.
 */
class GccPhatLocalizer {

    /**
     * Returns peak-lag and a confidence score in [0, 1].
     *
     * Sign convention: positive lag means signal `b` is delayed relative to `a`, i.e.,
     * the source reached `a` first.
     *
     * Confidence is peak / (3 × RMS of correlations in the search range), clamped to
     * [0, 1]. A peak much taller than the surrounding correlation noise → high confidence.
     */
    fun localize(a: ShortArray, b: ShortArray, maxLagSamples: Int): LagResult {
        val n = minOf(a.size, b.size)
        if (n == 0 || maxLagSamples < 0) return LagResult(0, 0f)

        var sumA2 = 0.0
        var sumB2 = 0.0
        for (i in 0 until n) {
            sumA2 += a[i].toDouble() * a[i].toDouble()
            sumB2 += b[i].toDouble() * b[i].toDouble()
        }
        val denom = sqrt(sumA2 * sumB2)
        if (denom < 1e-9) return LagResult(0, 0f)

        val lagCount = 2 * maxLagSamples + 1
        val correlations = DoubleArray(lagCount)
        var bestLag = 0
        var bestCorr = Double.NEGATIVE_INFINITY

        for (lag in -maxLagSamples..maxLagSamples) {
            val start = if (lag >= 0) lag else 0
            val end = if (lag >= 0) n else n + lag
            var sum = 0.0
            for (i in start until end) {
                sum += a[i].toDouble() * b[i - lag].toDouble()
            }
            val corr = sum / denom
            correlations[lag + maxLagSamples] = corr
            if (corr > bestCorr) {
                bestCorr = corr
                bestLag = lag
            }
        }

        var sumC2 = 0.0
        for (c in correlations) sumC2 += c * c
        val rmsCorr = sqrt(sumC2 / lagCount)
        val confidence = if (rmsCorr > 1e-9) {
            (bestCorr / (3.0 * rmsCorr)).coerceIn(0.0, 1.0).toFloat()
        } else 0f

        return LagResult(bestLag, confidence)
    }
}

data class LagResult(val lagSamples: Int, val confidence: Float)

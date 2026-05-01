package com.echoai.domain

import kotlin.math.cos
import kotlin.math.exp

/**
 * Bayesian belief over world-frame source direction (azimuth in degrees, [0, 360)).
 *
 * The phone has 1D directional sensitivity along its long axis (top-edge ↔ bottom-edge).
 * Per audio window we get a long-axis bias measurement (e.g., bot_ild) and the phone's
 * current world-frame yaw. As the user rotates the phone, the device's sensitive axis
 * sweeps through different world headings, and combining the measurements across
 * rotations recovers the source's world-frame direction — the rotational-aperture trick.
 *
 * Sensitivity model: `expected_bias = +biasScale × cos(deviceAngle)` where deviceAngle is
 * the source's bearing in the device frame, defined so that
 *  - 0° = TOP edge of phone — empirically corresponds to bot_ild *positive* (top mic
 *    physically louder, mapped to the R channel by Samsung's CAMCORDER virtual stereo).
 *    The earlier "-biasScale" sign was inferred from naming (bot_L ≟ bottom mic) but
 *    user testing showed the belief consistently landed at the antipodal of the true
 *    source direction, indicating the L/R channel-to-mic mapping is the opposite.
 *  - 180° = BOT edge of phone (charger end) → expected bias negative
 *  - ±90° = perpendicular to long axis → expected bias zero, broadside cone of confusion
 *    that's resolved by rotation
 *
 * Update is a Gaussian-likelihood Bayesian step on each window, then exponential decay
 * back toward uniform so old measurements age out (the source might be moving or
 * intermittent, and the belief shouldn't latch on a stale estimate forever).
 *
 * Not thread-safe — call [update] / [snapshot] / [argmaxDegrees] from one coroutine.
 */
class BeliefDistribution(
    private val numBins: Int = 36,                  // 10° resolution
    private val biasScale: Float = 0.20f,           // empirical |bias| at 0° / 180°
    private val measurementSigma: Float = 0.15f,    // Gaussian noise std around expected bias
    private val decayRate: Float = 0.05f,           // per-update fraction toward uniform prior
) {
    private val bins = FloatArray(numBins) { 1f / numBins }
    private val binDegrees = 360f / numBins

    /**
     * Apply one window's measurement. [measuredBias] is the long-axis bias signal
     * (positive = source toward BOT edge of phone). [phoneYawDegrees] is the heading
     * of the phone's TOP edge in world frame (0 = north).
     */
    fun update(measuredBias: Float, phoneYawDegrees: Float) {
        // Decay toward uniform first, so the prior is what we'd use absent of new info.
        val uniform = 1f / numBins
        for (i in bins.indices) {
            bins[i] = bins[i] * (1f - decayRate) + uniform * decayRate
        }

        // Gaussian-likelihood update per candidate world-frame angle.
        val twoSigmaSq = 2f * measurementSigma * measurementSigma
        for (i in bins.indices) {
            val worldAngle = i * binDegrees
            val deviceAngle = worldAngle - phoneYawDegrees
            val expectedBias = biasScale * cos(Math.toRadians(deviceAngle.toDouble())).toFloat()
            val residual = measuredBias - expectedBias
            val likelihood = exp(-(residual * residual) / twoSigmaSq)
            bins[i] *= likelihood
        }

        // Renormalize. Underflow guard: if all likelihoods collapsed, restart uniform.
        var sum = 0f
        for (b in bins) sum += b
        if (sum > 1e-20f) {
            val invSum = 1f / sum
            for (i in bins.indices) bins[i] *= invSum
        } else {
            for (i in bins.indices) bins[i] = uniform
        }
    }

    /** World-frame angle (degrees) of the most-likely bin. */
    fun argmaxDegrees(): Float {
        var bestIdx = 0
        var bestVal = Float.NEGATIVE_INFINITY
        for (i in bins.indices) {
            if (bins[i] > bestVal) { bestVal = bins[i]; bestIdx = i }
        }
        return bestIdx * binDegrees
    }

    /** Peak belief value in [0, 1]. Close to 1/numBins ≈ 0.028 means uniform / no info. */
    fun maxBelief(): Float {
        var m = 0f
        for (b in bins) if (b > m) m = b
        return m
    }

    /** Defensive copy of the current belief, length [numBins]. Bin i covers angle i*[binDegrees]. */
    fun snapshot(): FloatArray = bins.copyOf()

    fun reset() {
        val uniform = 1f / numBins
        for (i in bins.indices) bins[i] = uniform
    }
}

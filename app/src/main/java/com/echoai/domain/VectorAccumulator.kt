package com.echoai.domain

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Rotational-aperture localizer using vector summation. Replaces the cos-Bayesian
 * `BeliefDistribution`.
 *
 * Each window contributes a 2D vote to an accumulating vector:
 *   `vote = bias × (cos yaw, sin yaw)`
 * The vector decays exponentially each window so old votes age out. Direction is
 * `atan2(vy, vx)`; magnitude is the L2 norm and serves as confidence.
 *
 * **Why this beats the previous Bayesian / cos-model approach:**
 *  - **No magnitude clipping.** The cos model required `|bias| ≤ biasScale`; measurements
 *    larger than that were unfittable. Here, larger biases just contribute proportionally
 *    larger votes — strong evidence stays strong.
 *  - **Silent windows auto-discount.** `bias ≈ 0` → `vote ≈ 0`; silence doesn't pollute
 *    the estimate, only contributes to decay-driven forgetting.
 *  - **No antipodal twin from cos reflection.** Vector summation produces a single
 *    direction per accumulated state. Reflection candidates that the cos likelihood
 *    couldn't disambiguate per-window cancel out across rotations.
 *  - **Single confidence number** (magnitude) for clean UX: hide the pointer below a
 *    threshold instead of drawing a noisy halo.
 *  - **Sign convention is one switch.** If results land 180° off, set [flipSign] = true.
 *
 * Math sanity (single source at world α with `bias = K·cos(α − yaw)`): integrated over
 * a full rotation, the accumulated vector points at α (or α+180° if K is negative).
 * Sign of K depends on Samsung's HAL bot_L/bot_R-to-physical-mic mapping; calibrated
 * empirically via [flipSign].
 *
 * Not thread-safe. Call [update] / [directionDegrees] / [reset] from a single coroutine.
 */
class VectorAccumulator(
    /** Decay applied each window. 0.10 ≈ 1.5 s half-life at 500 ms windows. */
    private val decayPerWindow: Float = 0.10f,
    /** Below this raw magnitude, [directionDegrees] returns null and the radar hides the
     *  pointer. Tune from the `pointer_magnitude` column in the diagnostics CSV — set
     *  above the silent-floor noise but below typical converged values. */
    private val magnitudeFloor: Float = 0.20f,
    /** Negate the accumulated vector before reporting if the empirical sign is reversed
     *  (HAL mapping puts bot_L at top mic instead of bottom mic, or vice versa). Toggle
     *  if controlled tests show the pointer lands 180° from the actual source. */
    private val flipSign: Boolean = false,
) {
    private var vx: Float = 0f
    private var vy: Float = 0f

    /**
     * Apply one window's measurement. [bias] is the long-axis bias signal (typically
     * `bot_ild`). [yawDegrees] is the phone's world-frame heading at capture time.
     */
    fun update(bias: Float, yawDegrees: Float) {
        vx *= (1f - decayPerWindow)
        vy *= (1f - decayPerWindow)
        val rad = Math.toRadians(yawDegrees.toDouble())
        vx += bias * cos(rad).toFloat()
        vy += bias * sin(rad).toFloat()
    }

    /** Estimated source direction in world frame, [0, 360). Null when below threshold. */
    fun directionDegrees(): Float? {
        if (magnitude() < magnitudeFloor) return null
        val effVx = if (flipSign) -vx else vx
        val effVy = if (flipSign) -vy else vy
        val deg = Math.toDegrees(atan2(effVy.toDouble(), effVx.toDouble())).toFloat()
        return ((deg % 360f) + 360f) % 360f
    }

    /** L2 norm of the accumulated vector. Larger = more sustained directional evidence. */
    fun magnitude(): Float = sqrt(vx * vx + vy * vy)

    fun reset() {
        vx = 0f
        vy = 0f
    }
}

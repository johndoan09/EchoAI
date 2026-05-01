package com.echoai.domain

import com.echoai.ml.LabeledScore
import kotlin.math.asin

data class ClassificationResult(
    val frameNumber: Long,
    val topK: List<LabeledScore>,
)

/**
 * Per-window spatial estimate. All three GCC-PHAT entries are independent estimates of
 * different time-delay axes:
 *  - cross-pair: bottomMono ↔ backMono (front-back direction-of-arrival, buffer-level sync)
 *  - within-pair bottom: bottomLeft ↔ bottomRight (left-right azimuth from bottom array)
 *  - within-pair back: backLeft ↔ backRight (left-right azimuth from back array)
 */
data class LocalizationResult(
    val frameNumber: Long,
    val captureTimestampNanos: Long,
    val sampleRate: Int,
    // Per-channel RMS (used to compute ILD and for diagnostic display).
    val bottomLeftRms: Float,
    val bottomRightRms: Float,
    val backLeftRms: Float,
    val backRightRms: Float,
    // Mono-mix RMS per pair.
    val bottomRms: Float,
    val backRms: Float,
    val frontBackBias: Float,            // (bottomRms - backRms) / (bottomRms + backRms), [-1, +1]
    // Within-pair ILD (Interaural Level Difference) = (R - L) / (R + L), positive = source on right.
    // Stronger signal than within-pair ITD lag in our data (3–6× channel ratio for clear sources).
    val bottomIld: Float,
    val backIld: Float,
    val crossPairLag: LagSample,         // bottomMono vs backMono
    val withinPairBottom: LagSample,     // bottomLeft vs bottomRight
    val withinPairBack: LagSample,       // backLeft vs backRight
    val worldOrientation: FloatArray?,
)

data class LagSample(val samples: Int, val confidence: Float)

/**
 * One actively-tracked sound. Position is stored both as raw spatial measurements and
 * as a derived azimuth bin so the UI can render without doing trig.
 *
 * `worldOrientation` snapshot from the window the event was last refreshed in. When IMU
 * is wired, the fusion stage will use this to convert device-frame azimuth into world
 * frame for cross-window smoothing and artifact rejection.
 */
data class SoundEvent(
    val label: String,
    val confidence: Float,
    val firstSeenTimestampNanos: Long,
    val lastSeenTimestampNanos: Long,
    val devicePosition: DevicePosition,
    val worldOrientation: FloatArray?,
)

data class DevicePosition(
    val frontBackBias: Float,            // -1 (back-heavy) … +1 (front-heavy)
    val bottomIld: Float,                // -1 (left-heavy) … +1 (right-heavy), bottom array
    val backIld: Float,                  // -1 (left-heavy) … +1 (right-heavy), back array
    val crossPairLag: LagSample,
    val withinPairBottom: LagSample,
    val withinPairBack: LagSample,
    val sampleRate: Int,
) {
    /**
     * **X-axis** (east-west) signal: within-pair ILD of the bottom-edge array. The bottom
     * array spans the phone's short edge, so its L/R channel asymmetry reflects sources
     * left vs right of the phone in the screen plane.
     *
     * Convention: positive degrees = source on right side. Returns null below a noise floor
     * so the radar X doesn't jitter on silence.
     */
    fun azimuthFromBottomIld(): Float? {
        if (kotlin.math.abs(bottomIld) < ILD_NOISE_FLOOR) return null
        return (bottomIld.coerceIn(-1f, 1f) * 90f)
    }

    /**
     * **Y-axis** (long axis / top-bottom of phone) signal: within-pair ILD of the
     * back-face array. **Hypothesis** to verify with controlled testing — if the back-array
     * mics are arranged along the phone's long axis (likely on S25 Ultra with mics near
     * top of back vs bottom of back), this captures north/south directionality when the
     * phone is held flat.
     *
     * Convention: positive return = source toward "front" (top edge of phone). May need
     * sign flip after test calibration depending on which back-array channel is L vs R.
     */
    fun yAxisFromBackIld(): Float? {
        if (kotlin.math.abs(backIld) < ILD_NOISE_FLOOR) return null
        return (backIld.coerceIn(-1f, 1f) * 90f)
    }

    /**
     * **Diagnostic** azimuth from within-pair GCC-PHAT lag. Kept available for A/B
     * comparison against [azimuthFromIld]. Sign convention follows from cross-correlation
     * derivation: `peakXcorr(a, b)` peaks at negative lag when a leads b, so for
     * `(bottomLeft, bottomRight)` a positive lag means bottomRight leads → source on the
     * **right** → positive degrees. No sign negation required.
     *
     * Mapping is empirical, not geometric: the 10 cm mic-spacing assumption gave
     * maxValidLag = 4, but real confident detections cluster at ±8 because HAL adds
     * inter-channel processing delay on top of geometric ITD. Map linearly across the
     * GCC-PHAT search range instead.
     */
    fun azimuthFromLag(): Float? {
        val pickBottom = withinPairBottom.confidence >= withinPairBack.confidence
        val pick = if (pickBottom) withinPairBottom else withinPairBack
        if (pick.confidence < 0.15f) return null
        val ratio = pick.samples.toFloat() / LAG_SCALE_FOR_AZIMUTH
        val angleRad = asin(ratio.coerceIn(-1f, 1f).toDouble())
        return Math.toDegrees(angleRad).toFloat()
    }

    /** Public API kept for the radar; resolves to the bottom-array X-axis signal. */
    fun azimuthDegrees(): Float? = azimuthFromBottomIld()

    companion object {
        /** Below this absolute ILD value, the directional signal is treated as noise and
         *  the radar shows no angle. Calibrate from CSV — silence/ambient ILD distribution
         *  should fall below this threshold; clear directional sources should exceed it. */
        private const val ILD_NOISE_FLOOR = 0.05f
        /** Lag value (in samples) that maps to ±90° in the lag-based diagnostic azimuth. */
        private const val LAG_SCALE_FOR_AZIMUTH = 16f
    }
}

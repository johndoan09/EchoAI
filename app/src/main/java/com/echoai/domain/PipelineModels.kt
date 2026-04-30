package com.echoai.domain

import com.echoai.ml.LabeledScore
import kotlin.math.abs
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
    val bottomRms: Float,
    val backRms: Float,
    val frontBackBias: Float,            // (bottomRms - backRms) / (bottomRms + backRms), [-1, +1]
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
    val urgency: Urgency,
    val firstSeenTimestampNanos: Long,
    val lastSeenTimestampNanos: Long,
    val devicePosition: DevicePosition,
    val worldOrientation: FloatArray?,
)

data class DevicePosition(
    val frontBackBias: Float,            // -1 (back-heavy) … +1 (front-heavy)
    val crossPairLag: LagSample,
    val withinPairBottom: LagSample,
    val withinPairBack: LagSample,
    val sampleRate: Int,
) {
    /**
     * Device-frame azimuth in degrees: −90 = left, 0 = ahead, +90 = right.
     * Returns null when no within-pair estimate is confident enough.
     *
     * Sign convention: GCC-PHAT on (L, R) returns positive lag when sound reaches L
     * first → source is on the **left**. We negate so positive degrees = right.
     *
     * Mapping: empirical rather than geometric. The original 10 cm mic-spacing assumption
     * gave maxValidLag = 4 samples, but the CSV diagnostics show confident detections
     * routinely hitting ±8 — the "lag" we're measuring includes HAL inter-channel processing
     * delay, not just geometric ITD. Map linearly across the search range instead so high-
     * confidence detections produce a defined angle. Trade-off: degrees are nominal, not
     * metric — useful for relative L/R/centered placement, not for precise bearings.
     */
    fun azimuthDegrees(): Float? {
        val pickBottom = withinPairBottom.confidence >= withinPairBack.confidence
        val pick = if (pickBottom) withinPairBottom else withinPairBack
        if (pick.confidence < 0.15f) return null

        val ratio = -pick.samples.toFloat() / LAG_SCALE_FOR_AZIMUTH
        val angleRad = asin(ratio.coerceIn(-1f, 1f).toDouble())
        return Math.toDegrees(angleRad).toFloat()
    }

    companion object {
        /** Lag value (in samples) that maps to ±90°. Matches the within-pair GCC-PHAT
         *  search range so confident detections at the search edge saturate at the radar
         *  edge. Calibrate against more sessions if ITD distribution shifts. */
        private const val LAG_SCALE_FOR_AZIMUTH = 16f
    }
}

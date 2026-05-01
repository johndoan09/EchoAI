package com.echoai.domain

import org.junit.Assert.*
import org.junit.Test

class DevicePositionTest {

    private fun pos(
        bottomIld: Float = 0f,
        backIld: Float = 0f,
        withinBottomSamples: Int = 0,
        withinBottomConf: Float = 0f,
        withinBackSamples: Int = 0,
        withinBackConf: Float = 0f,
    ) = DevicePosition(
        frontBackBias = 0f,
        bottomIld = bottomIld,
        backIld = backIld,
        crossPairLag = LagSample(0, 0f),
        withinPairBottom = LagSample(withinBottomSamples, withinBottomConf),
        withinPairBack = LagSample(withinBackSamples, withinBackConf),
        sampleRate = 16000,
    )

    // --- azimuthFromBottomIld ---

    @Test
    fun azimuthFromBottomIldBelowNoiseFloorIsNull() {
        assertNull(pos(bottomIld = 0.04f).azimuthFromBottomIld())
    }

    @Test
    fun azimuthFromBottomIldExactlyAtNoiseFloorIsNull() {
        // noise floor = 0.05; abs(0.05) is NOT < 0.05, so it returns a value
        assertNotNull(pos(bottomIld = 0.05f).azimuthFromBottomIld())
    }

    @Test
    fun azimuthFromBottomIldPositiveGivesPositiveDegrees() {
        val az = pos(bottomIld = 0.5f).azimuthFromBottomIld()
        assertNotNull(az)
        assertEquals(45f, az!!, 0.01f) // 0.5 * 90 = 45°
    }

    @Test
    fun azimuthFromBottomIldNegativeGivesNegativeDegrees() {
        val az = pos(bottomIld = -0.5f).azimuthFromBottomIld()
        assertNotNull(az)
        assertEquals(-45f, az!!, 0.01f)
    }

    @Test
    fun azimuthFromBottomIldClampsToNinetyDegrees() {
        // ILD > 1.0 is clamped to 1.0 before multiplying
        val az = pos(bottomIld = 2.0f).azimuthFromBottomIld()
        assertNotNull(az)
        assertEquals(90f, az!!, 0.01f)
    }

    // --- yAxisFromBackIld ---

    @Test
    fun yAxisFromBackIldBelowNoiseFloorIsNull() {
        assertNull(pos(backIld = 0.02f).yAxisFromBackIld())
    }

    @Test
    fun yAxisFromBackIldAboveNoiseFloor() {
        val y = pos(backIld = 1.0f).yAxisFromBackIld()
        assertNotNull(y)
        assertEquals(90f, y!!, 0.01f)
    }

    @Test
    fun yAxisFromBackIldNegative() {
        val y = pos(backIld = -0.5f).yAxisFromBackIld()
        assertNotNull(y)
        assertEquals(-45f, y!!, 0.01f)
    }

    // --- azimuthFromLag ---

    @Test
    fun azimuthFromLagBelowConfidenceThresholdIsNull() {
        assertNull(pos(withinBottomSamples = 8, withinBottomConf = 0.1f).azimuthFromLag())
    }

    @Test
    fun azimuthFromLagWithHighConfidence() {
        // lag=8, LAG_SCALE=16 → ratio=0.5 → asin(0.5) = 30°
        val az = pos(withinBottomSamples = 8, withinBottomConf = 0.9f).azimuthFromLag()
        assertNotNull(az)
        assertEquals(30f, az!!, 0.5f)
    }

    @Test
    fun azimuthFromLagPrefersHigherConfidencePair() {
        // bottom confidence < back confidence → should use back pair
        val p = pos(
            withinBottomSamples = 8, withinBottomConf = 0.2f,
            withinBackSamples = -8, withinBackConf = 0.9f,
        )
        val az = p.azimuthFromLag()
        assertNotNull(az)
        assertTrue("Back pair (negative lag) should give negative angle", az!! < 0f)
    }

    // --- azimuthDegrees (public API) ---

    @Test
    fun azimuthDegreesMatchesBottomIldResult() {
        val p = pos(bottomIld = 0.5f)
        assertEquals(p.azimuthFromBottomIld(), p.azimuthDegrees())
    }

    @Test
    fun azimuthDegreesIsNullWhenBottomIldBelowFloor() {
        assertNull(pos(bottomIld = 0.01f).azimuthDegrees())
    }
}

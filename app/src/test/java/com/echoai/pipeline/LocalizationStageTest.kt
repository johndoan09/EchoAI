package com.echoai.pipeline

import com.echoai.audio.AudioWindow
import org.junit.Assert.*
import org.junit.Test

class LocalizationStageTest {

    private val stage = LocalizationStage()

    private fun makeWindow(
        n: Int,
        bottomLeft: ShortArray = ShortArray(n),
        bottomRight: ShortArray = ShortArray(n),
        backLeft: ShortArray = ShortArray(n),
        backRight: ShortArray = ShortArray(n),
        sampleRate: Int = 16000,
        frameNumber: Long = 7L,
    ) = AudioWindow(
        frameNumber = frameNumber,
        captureTimestampNanos = 123_456_789L,
        sampleRate = sampleRate,
        bottomLeft = bottomLeft,
        bottomRight = bottomRight,
        backLeft = backLeft,
        backRight = backRight,
        worldOrientation = null,
    )

    private fun const(n: Int, v: Int): ShortArray = ShortArray(n) { v.toShort() }

    // --- Silent window ---

    @Test
    fun silentWindowProducesZeroRmsAndZeroBias() {
        val result = stage.localize(makeWindow(1000))
        assertEquals(0f, result.bottomRms, 1e-6f)
        assertEquals(0f, result.backRms, 1e-6f)
        assertEquals(0f, result.frontBackBias, 1e-6f)
    }

    @Test
    fun silentWindowProducesZeroIld() {
        val result = stage.localize(makeWindow(1000))
        assertEquals(0f, result.bottomIld, 1e-6f)
        assertEquals(0f, result.backIld, 1e-6f)
    }

    @Test
    fun silentWindowGccPhatHasZeroConfidence() {
        val result = stage.localize(makeWindow(1000))
        assertEquals(0f, result.crossPairLag.confidence, 1e-6f)
        assertEquals(0f, result.withinPairBottom.confidence, 1e-6f)
        assertEquals(0f, result.withinPairBack.confidence, 1e-6f)
    }

    // --- Front/back bias ---

    @Test
    fun bottomLouderGivesPositiveFrontBackBias() {
        // bottom=2000, back=500 → bias = (2000−500)/(2000+500) = 0.6
        val result = stage.localize(
            makeWindow(1000,
                bottomLeft = const(1000, 2000), bottomRight = const(1000, 2000),
                backLeft   = const(1000,  500), backRight   = const(1000,  500),
            )
        )
        assertTrue("Expected positive bias, got ${result.frontBackBias}", result.frontBackBias > 0f)
        assertEquals(0.6f, result.frontBackBias, 0.01f)
    }

    @Test
    fun backLouderGivesNegativeFrontBackBias() {
        val result = stage.localize(
            makeWindow(1000,
                bottomLeft = const(1000,  500), bottomRight = const(1000,  500),
                backLeft   = const(1000, 2000), backRight   = const(1000, 2000),
            )
        )
        assertTrue("Expected negative bias, got ${result.frontBackBias}", result.frontBackBias < 0f)
        assertEquals(-0.6f, result.frontBackBias, 0.01f)
    }

    @Test
    fun equalBottomAndBackGivesZeroBias() {
        val result = stage.localize(
            makeWindow(1000,
                bottomLeft = const(1000, 1500), bottomRight = const(1000, 1500),
                backLeft   = const(1000, 1500), backRight   = const(1000, 1500),
            )
        )
        assertEquals(0f, result.frontBackBias, 0.01f)
    }

    // --- ILD (left/right within-pair) ---

    @Test
    fun rightLouderBottomGivesPositiveBottomIld() {
        // bottomLeft=1000, bottomRight=3000 → ild = (3000−1000)/(3000+1000) = 0.5
        val result = stage.localize(
            makeWindow(1000,
                bottomLeft  = const(1000, 1000),
                bottomRight = const(1000, 3000),
            )
        )
        assertTrue("Expected positive bottomIld, got ${result.bottomIld}", result.bottomIld > 0f)
        assertEquals(0.5f, result.bottomIld, 0.01f)
    }

    @Test
    fun leftLouderBottomGivesNegativeBottomIld() {
        // bottomLeft=3000, bottomRight=1000 → ild = (1000−3000)/(1000+3000) = −0.5
        val result = stage.localize(
            makeWindow(1000,
                bottomLeft  = const(1000, 3000),
                bottomRight = const(1000, 1000),
            )
        )
        assertTrue("Expected negative bottomIld, got ${result.bottomIld}", result.bottomIld < 0f)
        assertEquals(-0.5f, result.bottomIld, 0.01f)
    }

    @Test
    fun equalBottomChannelsGiveZeroIld() {
        val result = stage.localize(
            makeWindow(1000,
                bottomLeft  = const(1000, 2000),
                bottomRight = const(1000, 2000),
            )
        )
        assertEquals(0f, result.bottomIld, 0.01f)
    }

    @Test
    fun rightLouderBackGivesPositiveBackIld() {
        val result = stage.localize(
            makeWindow(1000,
                backLeft  = const(1000, 1000),
                backRight = const(1000, 3000),
            )
        )
        assertTrue(result.backIld > 0f)
        assertEquals(0.5f, result.backIld, 0.01f)
    }

    // --- Metadata passthrough ---

    @Test
    fun frameNumberAndTimestampPassedThrough() {
        val window = makeWindow(500, frameNumber = 42L)
        val result = stage.localize(window)
        assertEquals(42L, result.frameNumber)
        assertEquals(123_456_789L, result.captureTimestampNanos)
    }

    @Test
    fun sampleRatePassedThrough() {
        val result = stage.localize(makeWindow(500, sampleRate = 48000))
        assertEquals(48000, result.sampleRate)
    }

    // --- Per-channel RMS values ---

    @Test
    fun perChannelRmsReflectsIndividualChannelAmplitude() {
        val result = stage.localize(
            makeWindow(1000,
                bottomLeft  = const(1000, 1000),
                bottomRight = const(1000, 2000),
                backLeft    = const(1000, 3000),
                backRight   = const(1000, 4000),
            )
        )
        assertEquals(1000f, result.bottomLeftRms,  1f)
        assertEquals(2000f, result.bottomRightRms, 1f)
        assertEquals(3000f, result.backLeftRms,    1f)
        assertEquals(4000f, result.backRightRms,   1f)
    }

    // --- Multi-scale ---

    @Test
    fun multiScaleShortWindowReturnsSameFullAndSub() {
        // window.frameCount (3000) <= subFrames (16000/4=4000) → sub == full
        val result = stage.localizeMultiScale(makeWindow(3000))
        assertEquals(0, result.subFrameCount)
    }

    @Test
    fun multiScalePicksHighestEnergySlice() {
        // 1 second window (16000 frames). Loud burst in frames 8000..11999 (the 3rd 250 ms slice).
        // Sliding window (step=2000) candidates: 0,2000,4000,6000,8000,10000,12000.
        // Energy is maximised at start=8000 (all 4000 burst frames included).
        val n = 16000
        val burstStart = 8000
        val burstEnd = 12000 // exclusive
        val bottomLeft = ShortArray(n) { i -> if (i in burstStart until burstEnd) 10000 else 0 }

        val result = stage.localizeMultiScale(
            makeWindow(n, bottomLeft = bottomLeft)
        )
        assertEquals(burstStart, result.subStartFrame)
        assertEquals(4000, result.subFrameCount)
    }

    @Test
    fun multiScaleSubHasHigherRmsThanFullForTransientBurst() {
        val n = 16000
        // Loud burst in the second quarter; the rest is quiet.
        val bottomLeft = ShortArray(n) { i -> if (i in 4000 until 8000) 8000 else 100 }
        val result = stage.localizeMultiScale(
            makeWindow(n, bottomLeft = bottomLeft)
        )
        // Sub (highest-energy slice) should have higher bottomRms than the full window average.
        assertTrue(
            "Sub bottomRms (${result.sub.bottomRms}) should exceed full (${result.full.bottomRms})",
            result.sub.bottomRms > result.full.bottomRms
        )
    }
}

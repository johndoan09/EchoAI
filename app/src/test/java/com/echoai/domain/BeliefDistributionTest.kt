package com.echoai.domain

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class BeliefDistributionTest {

    @Test
    fun initialDistributionIsUniform() {
        val bd = BeliefDistribution()
        val snap = bd.snapshot()
        val expected = 1f / snap.size
        for (b in snap) assertEquals(expected, b, 1e-6f)
    }

    @Test
    fun snapshotReturnsCopy() {
        val bd = BeliefDistribution()
        val a = bd.snapshot()
        val b = bd.snapshot()
        assertNotSame(a, b)
    }

    @Test
    fun snapshotAlwaysSumsToOne() {
        val bd = BeliefDistribution(biasScale = 1.0f)
        repeat(5) { i -> bd.update(1.0f, i * 45f) }
        val snap = bd.snapshot()
        var sum = 0f
        for (b in snap) sum += b
        assertEquals(1f, sum, 1e-5f)
    }

    @Test
    fun updateConcentratesBeliefAwayFromUniform() {
        val bd = BeliefDistribution(biasScale = 1.0f, measurementSigma = 0.25f)
        val uniform = 1f / 36
        repeat(10) { bd.update(measuredBias = 1.0f, phoneYawDegrees = 0f) }
        assertTrue("Max bin should exceed uniform after repeated updates", bd.maxBelief() > uniform * 2)
    }

    @Test
    fun resetRestoresUniformDistribution() {
        val bd = BeliefDistribution(biasScale = 1.0f)
        repeat(20) { bd.update(1.0f, 0f) }
        bd.reset()
        val snap = bd.snapshot()
        val uniform = 1f / snap.size
        for (b in snap) assertEquals(uniform, b, 1e-6f)
    }

    @Test
    fun decayOnlyReducesPeak() {
        val bd = BeliefDistribution(biasScale = 1.0f)
        repeat(20) { bd.update(1.0f, 0f) }
        val peakBefore = bd.maxBelief()
        bd.decayOnly(0.5f)
        assertTrue("decayOnly should reduce the peak bin", bd.maxBelief() < peakBefore)
    }

    @Test
    fun maxBeliefEqualsUniformWhenFlat() {
        val bd = BeliefDistribution(numBins = 36)
        assertEquals(1f / 36, bd.maxBelief(), 1e-6f)
    }

    @Test
    fun zeroBiasUpdateConcentratesAtBroadsideAngles() {
        // measuredBias=0 means the source is at ±90° (broadside, where cos=0 → expectedBias=0).
        // After repeated updates, bins near 90° and 270° should dominate over 0°/180°.
        val bd = BeliefDistribution(biasScale = 1.0f, measurementSigma = 0.15f)
        repeat(10) { bd.update(0f, 0f) }
        val snap = bd.snapshot()
        // 36 bins → bin 9 = 90°, bin 0 = 0°
        val binAt90 = snap[9]
        val binAt0 = snap[0]
        assertTrue("Zero-bias should concentrate near ±90°, not at 0°/180°", binAt90 > binAt0)
    }

    @Test
    fun smoothedPeakMovesAtMostMaxStepPerCall() {
        val bd = BeliefDistribution(biasScale = 1.0f, measurementSigma = 0.1f)
        // Concentrate belief near 0°
        repeat(30) { bd.update(1.0f, 0f) }
        val peak1 = bd.smoothedPeakDegrees(maxStepDeg = 5f)
        // Shift belief toward ~90° by using yaw=90°
        repeat(30) { bd.update(1.0f, 90f) }
        val peak2 = bd.smoothedPeakDegrees(maxStepDeg = 5f)
        val rawDelta = abs(peak2 - peak1)
        val delta = minOf(rawDelta, 360f - rawDelta)
        assertTrue("Smoothed peak should move ≤5° per call, moved $delta°", delta <= 5.01f)
    }

    @Test
    fun argmaxDegreesOnUniformReturnsFirstBin() {
        val bd = BeliefDistribution(numBins = 36)
        // All bins equal → argmax returns index 0 → 0°
        assertEquals(0f, bd.argmaxDegrees(), 0f)
    }

    @Test
    fun updateConvergesOnExpectedWorldAngle() {
        // biasScale=1.0, measuredBias=1.0, phoneYaw=90°:
        //   expectedBias = cos(worldAngle - 90°) = 1.0 → worldAngle = 90° (bin 9 of 36)
        val bd = BeliefDistribution(biasScale = 1.0f, measurementSigma = 0.15f)
        repeat(30) { bd.update(measuredBias = 1.0f, phoneYawDegrees = 90f) }
        val peak = bd.argmaxDegrees()
        val delta = minOf(abs(peak - 90f), 360f - abs(peak - 90f))
        assertTrue("Belief should converge near 90°, peaked at $peak°", delta <= 10f)
    }

    @Test
    fun rotationalApertureResolvesDirectionalAmbiguity() {
        // Single yaw creates a cosine mirror: source at 60° and mirror at 300° look identical.
        // Adding a second yaw breaks the mirror so only 60° is consistent.
        val bd = BeliefDistribution(biasScale = 1.0f, measurementSigma = 0.15f, decayRate = 0.005f)
        val expected = 60f
        val bias0  = kotlin.math.cos(Math.toRadians(expected.toDouble())).toFloat()    // cos(60°) for yaw=0°
        val bias90 = kotlin.math.cos(Math.toRadians((expected - 90f).toDouble())).toFloat() // cos(-30°) for yaw=90°

        // Alternating yaws: each update rules out one of the two mirrors.
        repeat(40) { i ->
            if (i % 2 == 0) bd.update(bias0,  phoneYawDegrees = 0f)
            else             bd.update(bias90, phoneYawDegrees = 90f)
        }
        val peak = bd.argmaxDegrees()
        val delta = minOf(abs(peak - expected), 360f - abs(peak - expected))
        assertTrue("Rotational aperture should resolve peak near ${expected}°, got $peak°", delta <= 15f)
    }

    @Test
    fun beliefPeakIncreasesWithMoreUpdates() {
        val bd = BeliefDistribution(biasScale = 1.0f, measurementSigma = 0.15f)
        bd.update(1.0f, 0f)
        val peakAfter1 = bd.maxBelief()
        repeat(19) { bd.update(1.0f, 0f) }
        val peakAfter20 = bd.maxBelief()
        assertTrue("More consistent updates should sharpen the peak", peakAfter20 > peakAfter1)
    }
}

package com.echoai.audio

import org.junit.Assert.*
import org.junit.Test

class GccPhatLocalizerTest {

    private val localizer = GccPhatLocalizer()

    @Test
    fun emptyArraysReturnZeroResult() {
        val result = localizer.localize(ShortArray(0), ShortArray(0), 10)
        assertEquals(0, result.lagSamples)
        assertEquals(0f, result.confidence, 0f)
    }

    @Test
    fun silentSignalsReturnZeroConfidence() {
        val a = ShortArray(1000) { 0 }
        val b = ShortArray(1000) { 0 }
        val result = localizer.localize(a, b, 10)
        assertEquals(0f, result.confidence, 0f)
    }

    @Test
    fun identicalSignalsReturnZeroLag() {
        val signal = ShortArray(1000) { i -> ((i % 50 - 25) * 100).toShort() }
        val result = localizer.localize(signal, signal, 16)
        assertEquals(0, result.lagSamples)
    }

    @Test
    fun shiftedSignalReturnsNegativeLag() {
        // b[i] = base[i - shift]: b received the signal `shift` samples later than a.
        // Cross-correlation peaks at lag = -shift (implementation sign convention).
        val n = 500
        val shift = 5
        val base = ShortArray(n) { i -> if (i % 40 < 20) 1000 else -1000 }
        val b = ShortArray(n) { i -> if (i >= shift) base[i - shift] else 0 }
        val result = localizer.localize(base, b, 16)
        assertEquals(-shift, result.lagSamples)
    }

    @Test
    fun shiftedSignalProducesHighConfidence() {
        val n = 1000
        val shift = 3
        val base = ShortArray(n) { i -> if (i % 20 < 10) 2000 else -2000 }
        val b = ShortArray(n) { i -> if (i >= shift) base[i - shift] else 0 }
        val result = localizer.localize(base, b, 16)
        assertTrue("Clean shift should produce high confidence, got ${result.confidence}", result.confidence > 0.5f)
    }

    @Test
    fun confidenceNeverExceedsOne() {
        val n = 500
        val signal = ShortArray(n) { i -> if (i == 0) Short.MAX_VALUE else 0 }
        val result = localizer.localize(signal, signal, 10)
        assertTrue("Confidence must be ≤ 1, was ${result.confidence}", result.confidence <= 1f)
    }

    @Test
    fun maxLagZeroForcesZeroLag() {
        val signal = ShortArray(500) { i -> if (i % 10 < 5) 1000 else -1000 }
        val shifted = ShortArray(500) { i -> if (i >= 3) signal[i - 3] else 0 }
        val result = localizer.localize(signal, shifted, 0)
        assertEquals(0, result.lagSamples)
    }

    @Test
    fun confidenceIsNonNegative() {
        val a = ShortArray(200) { i -> (i * 100).toShort() }
        val b = ShortArray(200) { i -> (i * 77).toShort() }
        val result = localizer.localize(a, b, 10)
        assertTrue("Confidence must be ≥ 0, was ${result.confidence}", result.confidence >= 0f)
    }

    @Test
    fun unequalLengthArraysUseMinLength() {
        val a = ShortArray(200) { i -> if (i % 20 < 10) 1000 else -1000 }
        val b = ShortArray(100) { i -> if (i % 20 < 10) 1000 else -1000 }
        // Should not throw; processes the shorter length
        val result = localizer.localize(a, b, 10)
        assertTrue(result.confidence >= 0f)
    }
}

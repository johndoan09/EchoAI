package com.echoai.ml

import org.junit.Assert.*
import org.junit.Test

class StubSoundClassifierTest {

    private val stub = StubSoundClassifier(inputSampleCount = 15_600)

    // --- Contract ---

    @Test
    fun inputSampleCountIs15600() {
        assertEquals(15_600, stub.inputSampleCount)
    }

    @Test
    fun classifyAlwaysReturnsNonEmptyList() {
        assertTrue(stub.classify(FloatArray(15_600)).isNotEmpty())
    }

    @Test
    fun allConfidencesAreInZeroOneRange() {
        for (score in stub.classify(FloatArray(15_600) { 0.5f })) {
            assertTrue("${score.label} confidence ${score.confidence} out of range",
                score.confidence in 0f..1f)
        }
    }

    // --- Branch: silence (rms < 0.00625) ---

    @Test
    fun silentInputReturnsSilenceLabel() {
        val result = stub.classify(FloatArray(15_600) { 0f })
        assertEquals("Silence", result.first().label)
    }

    @Test
    fun silentInputSilenceConfidenceIs0_95() {
        val result = stub.classify(FloatArray(15_600) { 0f })
        assertEquals(0.95f, result.first().confidence, 0.001f)
    }

    // --- Branch: medium activity (0.00625 ≤ rms < 0.025) ---

    @Test
    fun mediumActivityFirstLabelIsAmbient() {
        // rms = 0.015 → active = 0.12, in [0.05, 0.20) → "Ambient" branch
        val result = stub.classify(FloatArray(15_600) { 0.015f })
        assertEquals("Ambient", result.first().label)
    }

    @Test
    fun mediumActivityHasThreeLabels() {
        val result = stub.classify(FloatArray(15_600) { 0.015f })
        assertEquals(3, result.size)
        val labels = result.map { it.label }
        assertTrue(labels.contains("Speech"))
        assertTrue(labels.contains("Vehicle"))
    }

    // --- Branch: high activity (rms ≥ 0.025) ---

    @Test
    fun highActivityFirstLabelIsLoudSound() {
        // rms = 0.5 → active = 4.0 (clamped to 1.0) → "LoudSound" branch
        val result = stub.classify(FloatArray(15_600) { 0.5f })
        assertEquals("LoudSound", result.first().label)
    }

    @Test
    fun highActivityHasThreeLabels() {
        val result = stub.classify(FloatArray(15_600) { 0.5f })
        assertEquals(3, result.size)
        val labels = result.map { it.label }
        assertTrue(labels.contains("Vehicle"))
        assertTrue(labels.contains("Alarm"))
    }

    // --- Confidence ordering ---

    @Test
    fun labelsAreOrderedByDescendingConfidence() {
        val result = stub.classify(FloatArray(15_600) { 0.5f })
        for (i in 1 until result.size) {
            assertTrue(
                "Label at $i (${result[i].confidence}) should be ≤ label at ${i-1} (${result[i-1].confidence})",
                result[i].confidence <= result[i - 1].confidence
            )
        }
    }

    // --- Edge: near-threshold RMS ---

    @Test
    fun justAboveSilenceThresholdIsNotSilence() {
        // rms = 0.007 → active = 0.056, slightly above 0.05 threshold → medium branch
        val result = stub.classify(FloatArray(15_600) { 0.007f })
        assertNotEquals("Silence", result.first().label)
    }

    @Test
    fun justBelowSilenceThresholdIsSilence() {
        // rms = 0.003 → active = 0.024 < 0.05 → Silence
        val result = stub.classify(FloatArray(15_600) { 0.003f })
        assertEquals("Silence", result.first().label)
    }
}

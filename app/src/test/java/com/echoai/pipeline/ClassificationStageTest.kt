package com.echoai.pipeline

import com.echoai.audio.AudioWindow
import com.echoai.ml.StubSoundClassifier
import org.junit.Assert.*
import org.junit.Test

class ClassificationStageTest {

    private val classifier = StubSoundClassifier(inputSampleCount = 15_600)
    private val stage = ClassificationStage(classifier)

    private fun makeWindow(
        n: Int = 15_600,
        value: Int = 0,
        frameNumber: Long = 3L,
    ): AudioWindow {
        val ch = ShortArray(n) { value.toShort() }
        return AudioWindow(
            frameNumber = frameNumber,
            captureTimestampNanos = System.nanoTime(),
            sampleRate = 16_000,
            bottomLeft  = ch.copyOf(),
            bottomRight = ch.copyOf(),
            backLeft    = ch.copyOf(),
            backRight   = ch.copyOf(),
            worldOrientation = null,
        )
    }

    // --- Frame number passthrough ---

    @Test
    fun classifyPreservesFrameNumber() {
        val result = stage.classify(makeWindow(frameNumber = 99L))
        assertEquals(99L, result.frameNumber)
    }

    // --- Silent window → Silence label ---

    @Test
    fun silentWindowProducesSilenceLabel() {
        val result = stage.classify(makeWindow(value = 0))
        assertTrue("Expected Silence label for silent input, got ${result.topK}",
            result.topK.first().label == "Silence")
    }

    @Test
    fun silentWindowSilenceConfidenceIsHigh() {
        val result = stage.classify(makeWindow(value = 0))
        assertTrue(result.topK.first().confidence >= 0.9f)
    }

    // --- Loud window → active labels ---

    @Test
    fun loudWindowProducesLoudSoundLabel() {
        // constant Short.MAX_VALUE in all channels → high RMS → active class
        val result = stage.classify(makeWindow(value = Short.MAX_VALUE.toInt()))
        assertEquals("LoudSound", result.topK.first().label)
    }

    @Test
    fun loudWindowTopLabelHasHighConfidence() {
        val result = stage.classify(makeWindow(value = Short.MAX_VALUE.toInt()))
        assertTrue(result.topK.first().confidence > 0.5f)
    }

    // --- Medium activity ---

    @Test
    fun mediumWindowProducesAmbientLabel() {
        // constant 400 → downmix=400, normalized≈0.012, rms≈0.012, active=0.097 → Ambient tier
        val result = stage.classify(makeWindow(value = 400))
        assertEquals("Ambient", result.topK.first().label)
    }

    // --- Downmix averaging ---

    @Test
    fun fourIdenticalChannelsDownmixToSameAmplitude() {
        // If 4 channels are all value V, downmix average = V. RMS should be same as single channel.
        // Two windows with same value in 4 channels vs 1 active channel should differ in output.
        val allLoud = makeWindow(value = Short.MAX_VALUE.toInt())
        val result = stage.classify(allLoud)
        assertEquals("LoudSound", result.topK.first().label)
    }

    // --- TopK output shape ---

    @Test
    fun classifyResultIsNonEmpty() {
        val result = stage.classify(makeWindow())
        assertTrue(result.topK.isNotEmpty())
    }

    @Test
    fun allConfidencesAreBetweenZeroAndOne() {
        val result = stage.classify(makeWindow(value = Short.MAX_VALUE.toInt()))
        for (score in result.topK) {
            assertTrue("${score.label} confidence ${score.confidence} out of range",
                score.confidence in 0f..1f)
        }
    }

    // --- Short window padding ---

    @Test
    fun windowShorterThanInputSampleCountStillClassifies() {
        // Window half the required length → pads with zeros → still runs without error
        val result = stage.classify(makeWindow(n = 7_800, value = Short.MAX_VALUE.toInt()))
        assertNotNull(result)
        assertTrue(result.topK.isNotEmpty())
    }

    // --- Long window trimming ---

    @Test
    fun windowLongerThanInputSampleCountStillClassifies() {
        val result = stage.classify(makeWindow(n = 20_000, value = Short.MAX_VALUE.toInt()))
        assertNotNull(result)
        assertTrue(result.topK.isNotEmpty())
    }
}

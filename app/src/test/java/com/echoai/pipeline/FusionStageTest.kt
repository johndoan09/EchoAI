package com.echoai.pipeline

import com.echoai.domain.ClassificationResult
import com.echoai.domain.LagSample
import com.echoai.domain.LocalizationResult
import com.echoai.domain.SoundProfile
import com.echoai.domain.Urgency
import com.echoai.ml.LabeledScore
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class FusionStageTest {

    private lateinit var stage: FusionStage

    @Before
    fun setup() {
        stage = FusionStage()
        // Default profile: matchAll=true so any label is tracked.
        stage.applyProfile(SoundProfile.DEFAULT)
    }

    private fun classResult(vararg pairs: Pair<String, Float>) = ClassificationResult(
        frameNumber = 1L,
        topK = pairs.map { LabeledScore(it.first, it.second) },
    )

    private fun locResult(ts: Long = System.nanoTime()) = LocalizationResult(
        frameNumber = 1L,
        captureTimestampNanos = ts,
        sampleRate = 16000,
        bottomLeftRms = 0.1f, bottomRightRms = 0.1f,
        backLeftRms = 0.1f, backRightRms = 0.1f,
        bottomRms = 0.1f, backRms = 0.1f,
        frontBackBias = 0f,
        bottomIld = 0f, backIld = 0f,
        crossPairLag = LagSample(0, 0f),
        withinPairBottom = LagSample(0, 0f),
        withinPairBack = LagSample(0, 0f),
        worldOrientation = null,
    )

    // --- Basic event creation ---

    @Test
    fun processWithConfidentLabelReturnsEvent() {
        val events = stage.process(classResult("Dog" to 0.9f), locResult())
        assertEquals(1, events.size)
        assertEquals("Dog", events[0].label)
    }

    @Test
    fun processWithBelowThresholdLabelReturnsEmpty() {
        val events = stage.process(classResult("Dog" to 0.05f), locResult())
        assertTrue(events.isEmpty())
    }

    @Test
    fun processWithEmptyTopKReturnsEmpty() {
        val events = stage.process(classResult(), locResult())
        assertTrue(events.isEmpty())
    }

    // --- Temporal accumulation across calls ---

    @Test
    fun secondCallWithSameLabelUpdatesEvent() {
        val ts = System.nanoTime()
        stage.process(classResult("Dog" to 0.5f), locResult(ts))
        val events = stage.process(classResult("Dog" to 0.9f), locResult(ts + 100))
        assertEquals(1, events.size)
        assertEquals(0.9f, events[0].confidence, 0.001f)
    }

    @Test
    fun twoDistinctLabelsAreTrackedSeparately() {
        val ts = System.nanoTime()
        stage.process(classResult("Dog" to 0.9f), locResult(ts))
        val events = stage.process(classResult("Alarm" to 0.9f), locResult(ts + 100))
        assertEquals(2, events.size)
        assertTrue(events.map { it.label }.containsAll(listOf("Dog", "Alarm")))
    }

    @Test
    fun eventsAreSortedCriticalFirst() {
        val ts = System.nanoTime()
        // Default urgency (no urgencyClassifier) → all LOW. Use a stage with urgency overrides.
        val customStage = FusionStage()
        customStage.applyProfile(SoundProfile(
            id = "test", name = "test",
            priorityLabels = emptySet(), checkAll = true,
            urgencyOverrides = mapOf("Alarm" to Urgency.CRITICAL, "Dog" to Urgency.LOW),
        ))
        customStage.process(classResult("Dog" to 0.9f), locResult(ts))
        val events = customStage.process(classResult("Alarm" to 0.9f), locResult(ts + 100))
        assertEquals(2, events.size)
        assertEquals(Urgency.CRITICAL, events[0].urgency)
        assertEquals(Urgency.LOW, events[1].urgency)
    }

    // --- applyProfile ---

    @Test
    fun applyDefaultProfileTracksAllLabels() {
        stage.applyProfile(SoundProfile.DEFAULT) // checkAll=true
        val events = stage.process(classResult("AnyLabel" to 0.9f), locResult())
        assertEquals(1, events.size)
    }

    @Test
    fun applyProfileWithCheckAllFalseFiltersUnprioritized() {
        val restrictive = SoundProfile(
            id = "r", name = "r",
            priorityLabels = setOf("Dog"),
            checkAll = false,
        )
        stage.applyProfile(restrictive)
        val events = stage.process(classResult("Car" to 0.9f), locResult())
        assertTrue("Non-priority label should be filtered", events.isEmpty())
    }

    @Test
    fun applyProfileWithCheckAllFalseAllowsPriorityLabel() {
        val restrictive = SoundProfile(
            id = "r", name = "r",
            priorityLabels = setOf("Dog"),
            checkAll = false,
        )
        stage.applyProfile(restrictive)
        val events = stage.process(classResult("Dog" to 0.9f), locResult())
        assertEquals(1, events.size)
    }

    @Test
    fun applyProfileAutoAddsCriticalOverrideLabelsToPriority() {
        // "Alarm" is not in priorityLabels, but has a CRITICAL urgency override.
        // applyProfile should auto-add it to the tracker's priority set.
        val profile = SoundProfile(
            id = "t", name = "t",
            priorityLabels = setOf("Dog"),
            checkAll = false,
            urgencyOverrides = mapOf("Alarm" to Urgency.CRITICAL),
        )
        stage.applyProfile(profile)
        val events = stage.process(classResult("Alarm" to 0.9f), locResult())
        assertEquals(1, events.size)
        assertEquals(Urgency.CRITICAL, events[0].urgency)
    }

    @Test
    fun applyProfileDoesNotAutoAddLowOverrideLabelsToPriority() {
        // LOW urgency overrides should NOT auto-elevate a label to priority.
        val profile = SoundProfile(
            id = "t", name = "t",
            priorityLabels = setOf("Dog"),
            checkAll = false,
            urgencyOverrides = mapOf("Music" to Urgency.LOW),
        )
        stage.applyProfile(profile)
        val events = stage.process(classResult("Music" to 0.9f), locResult())
        assertTrue("LOW override should not auto-add 'Music' to priority", events.isEmpty())
    }

    @Test
    fun applyProfileUrgencyOverrideAppliedToEvents() {
        val profile = SoundProfile(
            id = "t", name = "t",
            priorityLabels = emptySet(), checkAll = true,
            urgencyOverrides = mapOf("Dog" to Urgency.HIGH),
        )
        stage.applyProfile(profile)
        val events = stage.process(classResult("Dog" to 0.9f), locResult())
        assertEquals(1, events.size)
        assertEquals(Urgency.HIGH, events[0].urgency)
    }
}

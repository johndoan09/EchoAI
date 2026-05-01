package com.echoai.domain

import com.echoai.ml.LabeledScore
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class EventTrackerTest {

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

    private lateinit var tracker: EventTracker

    @Before
    fun setup() {
        tracker = EventTracker(urgencyClassifier = null, staleAfterNanos = 3_000_000_000L)
        tracker.matchAll = true
    }

    @Test
    fun matchAllTracksAnyConfidentLabel() {
        tracker.update(classResult("Dog" to 0.9f), locResult())
        val snap = tracker.snapshot()
        assertEquals(1, snap.size)
        assertEquals("Dog", snap[0].label)
    }

    @Test
    fun labelBelowConfidenceThresholdIsNotTracked() {
        tracker.update(classResult("Dog" to 0.05f), locResult()) // below 0.10 threshold
        assertTrue(tracker.snapshot().isEmpty())
    }

    @Test
    fun labelAtConfidenceThresholdIsTracked() {
        tracker.update(classResult("Dog" to 0.10f), locResult()) // exactly at 0.10
        assertEquals(1, tracker.snapshot().size)
    }

    @Test
    fun staleEventIsEvictedAfterTimeout() {
        val baseTs = System.nanoTime()
        tracker.update(classResult("Dog" to 0.9f), locResult(ts = baseTs))
        val snap = tracker.snapshot(asOfNanos = baseTs + 4_000_000_000L) // 4s later > 3s window
        assertTrue("Event older than staleAfterNanos should be evicted", snap.isEmpty())
    }

    @Test
    fun nonStaleEventIsRetained() {
        val baseTs = System.nanoTime()
        tracker.update(classResult("Dog" to 0.9f), locResult(ts = baseTs))
        val snap = tracker.snapshot(asOfNanos = baseTs + 2_000_000_000L) // 2s later < 3s window
        assertEquals(1, snap.size)
    }

    @Test
    fun updateRefreshesExistingEventKeepingFirstSeen() {
        val baseTs = System.nanoTime()
        tracker.update(classResult("Dog" to 0.5f), locResult(ts = baseTs))
        val refreshTs = baseTs + 1_000_000_000L
        tracker.update(classResult("Dog" to 0.9f), locResult(ts = refreshTs))

        val snap = tracker.snapshot(asOfNanos = refreshTs + 100)
        assertEquals(1, snap.size)
        assertEquals(0.9f, snap[0].confidence, 0.001f)
        assertEquals(baseTs, snap[0].firstSeenTimestampNanos)
        assertEquals(refreshTs, snap[0].lastSeenTimestampNanos)
    }

    @Test
    fun matchAllFalseIgnoresNonPriorityLabel() {
        tracker.matchAll = false
        tracker.priorityLabels = setOf("Dog")
        tracker.update(classResult("Car" to 0.9f), locResult())
        assertTrue(tracker.snapshot().isEmpty())
    }

    @Test
    fun matchAllFalseAllowsPriorityLabel() {
        tracker.matchAll = false
        tracker.priorityLabels = setOf("Dog")
        tracker.update(classResult("Dog" to 0.9f), locResult())
        assertEquals(1, tracker.snapshot().size)
    }

    @Test
    fun priorityLabelMatchIsCaseInsensitive() {
        tracker.matchAll = false
        tracker.priorityLabels = setOf("dog")
        tracker.update(classResult("Dog" to 0.9f), locResult())
        assertEquals(1, tracker.snapshot().size)
    }

    @Test
    fun urgencyOverrideTakesPrecedenceOverDefault() {
        tracker.urgencyOverrides = mapOf("Dog" to Urgency.CRITICAL)
        tracker.update(classResult("Dog" to 0.9f), locResult())
        assertEquals(Urgency.CRITICAL, tracker.snapshot()[0].urgency)
    }

    @Test
    fun noUrgencyClassifierDefaultsToLow() {
        tracker.update(classResult("UnknownXYZ" to 0.9f), locResult())
        assertEquals(Urgency.LOW, tracker.snapshot()[0].urgency)
    }

    @Test
    fun snapshotSortedByCriticalFirst() {
        val baseTs = System.nanoTime()
        tracker.urgencyOverrides = mapOf(
            "Dog" to Urgency.LOW,
            "Alarm" to Urgency.CRITICAL,
            "Speech" to Urgency.MEDIUM,
        )
        tracker.update(classResult("Dog" to 0.9f), locResult(ts = baseTs))
        tracker.update(classResult("Alarm" to 0.9f), locResult(ts = baseTs + 1))
        tracker.update(classResult("Speech" to 0.9f), locResult(ts = baseTs + 2))

        val snap = tracker.snapshot(asOfNanos = baseTs + 100)
        assertEquals(3, snap.size)
        assertEquals(Urgency.CRITICAL, snap[0].urgency)
        assertEquals(Urgency.MEDIUM, snap[1].urgency)
        assertEquals(Urgency.LOW, snap[2].urgency)
    }

    @Test
    fun emptyTrackerSnapshotIsEmpty() {
        assertTrue(tracker.snapshot().isEmpty())
    }

    @Test
    fun silenceLabelIsTrackedByEventTracker() {
        // "Silence" filtering happens upstream in MainActivity, not in EventTracker.
        tracker.update(classResult("Silence" to 0.95f), locResult())
        assertEquals(1, tracker.snapshot().size)
    }

    @Test
    fun priorityLabelSubstringMatchFromLabel() {
        // priorityLabel "Dog" matches input "Dog bark" (label contains priority key)
        tracker.matchAll = false
        tracker.priorityLabels = setOf("Dog")
        tracker.update(classResult("Dog bark" to 0.9f), locResult())
        assertEquals(1, tracker.snapshot().size)
        assertEquals("Dog bark", tracker.snapshot()[0].label)
    }

    @Test
    fun priorityLabelSubstringMatchFromKey() {
        // priorityLabel "bark" matches input "Dog bark" (label contains priority key)
        tracker.matchAll = false
        tracker.priorityLabels = setOf("bark")
        tracker.update(classResult("Dog bark" to 0.9f), locResult())
        assertEquals(1, tracker.snapshot().size)
    }

    @Test
    fun urgencyOverrideSubstringMatchApplied() {
        // urgencyOverrides key "Dog" should match label "Dog bark"
        tracker.urgencyOverrides = mapOf("Dog" to Urgency.HIGH)
        tracker.update(classResult("Dog bark" to 0.9f), locResult())
        assertEquals(Urgency.HIGH, tracker.snapshot()[0].urgency)
    }

    @Test
    fun firstPriorityLabelInTopKIsTracked() {
        // topK = [("Car", 0.9), ("Dog", 0.8)]; only "Dog" is priority
        // EventTracker should pick "Dog" (first priority hit), not "Car"
        tracker.matchAll = false
        tracker.priorityLabels = setOf("Dog")
        tracker.update(classResult("Car" to 0.9f, "Dog" to 0.8f), locResult())
        val snap = tracker.snapshot()
        assertEquals(1, snap.size)
        assertEquals("Dog", snap[0].label)
    }

    @Test
    fun emptyTopKDoesNothing() {
        tracker.update(ClassificationResult(1L, emptyList()), locResult())
        assertTrue(tracker.snapshot().isEmpty())
    }

    @Test
    fun multipleDistinctLabelsTrackedIndependently() {
        val baseTs = System.nanoTime()
        tracker.update(classResult("Dog" to 0.9f), locResult(baseTs))
        tracker.update(classResult("Alarm" to 0.9f), locResult(baseTs + 100))
        val snap = tracker.snapshot(asOfNanos = baseTs + 200)
        assertEquals(2, snap.size)
        assertTrue(snap.map { it.label }.containsAll(listOf("Dog", "Alarm")))
    }
}

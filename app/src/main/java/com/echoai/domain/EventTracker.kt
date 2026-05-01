package com.echoai.domain

/**
 * Rolling tracker for stage-(a) attribution. Keeps each YAMNet class as a single
 * `SoundEvent`, refreshing it when the same class re-appears with sufficient confidence.
 * Drops events that haven't been refreshed within `staleAfterNanos`.
 *
 * Profile-driven behaviour:
 * - [priorityLabels]: selected labels from the active profile. Only selected labels are
 *   surfaced, using a lower confidence threshold.
 * - [urgencyOverrides]: per-label urgency chosen by the user in their profile, taking
 *   precedence over the base urgency_map.json lookup.
 *
 * Not thread-safe — call `update`/`snapshot` from the same coroutine.
 */
class EventTracker(
    private val urgencyClassifier: UrgencyClassifier? = null,
    private val staleAfterNanos: Long = 3_000_000_000L,
    private val refreshConfidenceThreshold: Float = 0.20f,
) {
    private val active = mutableMapOf<String, SoundEvent>()

    /** When true every label is treated as priority (profile has "all checked"). */
    var matchAll: Boolean = false

    /** Labels the user has marked as priority for the active [SoundProfile]. */
    var priorityLabels: Set<String> = emptySet()

    /** Per-label urgency overrides from the active [SoundProfile]. */
    var urgencyOverrides: Map<String, Urgency> = emptyMap()

    fun update(classification: ClassificationResult, localization: LocalizationResult) {
        val now = localization.captureTimestampNanos
        evictStale(now)

        val top = classification.topK.firstOrNull { isPriority(it.label) } ?: return
        val threshold = PRIORITY_CONFIDENCE_THRESHOLD
        if (top.confidence < threshold) return

        val devicePos = DevicePosition(
            frontBackBias = localization.frontBackBias,
            bottomIld = localization.bottomIld,
            backIld = localization.backIld,
            crossPairLag = localization.crossPairLag,
            withinPairBottom = localization.withinPairBottom,
            withinPairBack = localization.withinPairBack,
            sampleRate = localization.sampleRate,
        )
        val urgency = resolveUrgency(top.label)
        val existing = active[top.label]
        active[top.label] = if (existing != null) {
            existing.copy(
                confidence = top.confidence,
                urgency = urgency,
                lastSeenTimestampNanos = now,
                devicePosition = devicePos,
                worldOrientation = localization.worldOrientation,
                isPrioritized = true,
            )
        } else {
            SoundEvent(
                label = top.label,
                confidence = top.confidence,
                urgency = urgency,
                firstSeenTimestampNanos = now,
                lastSeenTimestampNanos = now,
                devicePosition = devicePos,
                worldOrientation = localization.worldOrientation,
                isPrioritized = true,
            )
        }
    }

    /**
     * Returns active events sorted by resolved urgency (CRITICAL first), then recency.
     */
    fun snapshot(asOfNanos: Long = System.nanoTime()): List<SoundEvent> {
        evictStale(asOfNanos)
        return active.values.sortedWith(
            compareByDescending<SoundEvent> { effectiveRank(it) }
                .thenByDescending { it.lastSeenTimestampNanos }
        )
    }

    private fun resolveUrgency(label: String): Urgency =
        urgencyOverrides[label]
            ?: urgencyOverrides.entries.firstOrNull {
                label.contains(it.key, ignoreCase = true) || it.key.contains(label, ignoreCase = true)
            }?.value
            ?: urgencyClassifier?.classify(label)
            ?: Urgency.LOW

    private fun effectiveRank(event: SoundEvent): Int =
        event.urgency.ordinalRank

    private fun isPriority(label: String): Boolean {
        if (matchAll) return true
        return priorityLabels.any {
            it.equals(label, ignoreCase = true) ||
                label.contains(it, ignoreCase = true) ||
                it.contains(label, ignoreCase = true)
        }
    }

    private fun evictStale(nowNanos: Long) {
        val cutoff = nowNanos - staleAfterNanos
        val iter = active.entries.iterator()
        while (iter.hasNext()) {
            if (iter.next().value.lastSeenTimestampNanos < cutoff) iter.remove()
        }
    }

    companion object {
        private const val PRIORITY_CONFIDENCE_THRESHOLD = 0.10f
    }
}

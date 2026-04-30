package com.echoai.domain

/**
 * Rolling tracker for stage-(a) attribution: keeps each YAMNet class as a single
 * `SoundEvent` and refreshes it when the same class re-appears with sufficient
 * confidence. Drops events that haven't been refreshed within `staleAfterNanos`.
 *
 * Not thread-safe. Call `update`/`snapshot` from the same coroutine.
 */
class EventTracker(
    private val staleAfterNanos: Long = 3_000_000_000L,    // 3 s
    private val refreshConfidenceThreshold: Float = 0.20f,
) {
    private val active = mutableMapOf<String, SoundEvent>()

    /**
     * Apply one window's classification + localization to the rolling state.
     * Stage-(a): only the top-1 label is treated as a directional source per window.
     */
    fun update(
        classification: ClassificationResult,
        localization: LocalizationResult,
    ) {
        val now = localization.captureTimestampNanos
        evictStale(now)

        val top = classification.topK.firstOrNull() ?: return
        if (top.confidence < refreshConfidenceThreshold) return

        val devicePos = DevicePosition(
            frontBackBias = localization.frontBackBias,
            crossPairLag = localization.crossPairLag,
            withinPairBottom = localization.withinPairBottom,
            withinPairBack = localization.withinPairBack,
            sampleRate = localization.sampleRate,
        )

        val existing = active[top.label]
        active[top.label] = if (existing != null) {
            existing.copy(
                confidence = top.confidence,
                lastSeenTimestampNanos = now,
                devicePosition = devicePos,
                worldOrientation = localization.worldOrientation,
            )
        } else {
            SoundEvent(
                label = top.label,
                confidence = top.confidence,
                firstSeenTimestampNanos = now,
                lastSeenTimestampNanos = now,
                devicePosition = devicePos,
                worldOrientation = localization.worldOrientation,
            )
        }
    }

    /** Returns currently-active events sorted by recency (most-recently-refreshed first). */
    fun snapshot(asOfNanos: Long = System.nanoTime()): List<SoundEvent> {
        evictStale(asOfNanos)
        return active.values.sortedByDescending { it.lastSeenTimestampNanos }
    }

    private fun evictStale(nowNanos: Long) {
        val cutoff = nowNanos - staleAfterNanos
        val iter = active.entries.iterator()
        while (iter.hasNext()) {
            if (iter.next().value.lastSeenTimestampNanos < cutoff) iter.remove()
        }
    }
}

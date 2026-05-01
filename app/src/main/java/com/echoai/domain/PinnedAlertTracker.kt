package com.echoai.domain

data class PinnedAlert(
    val label: String,
    val urgency: Urgency,
    val isPrioritized: Boolean,
    val detectedAtMs: Long,
)

/**
 * Collects HIGH/CRITICAL events into a persistent unacknowledged set. Entries survive
 * until the user explicitly dismisses them, so a sound that fired while the phone was
 * idle stays visible on next interaction.
 */
class PinnedAlertTracker {

    private val pinned = mutableMapOf<String, PinnedAlert>()

    fun onEvents(events: List<SoundEvent>) {
        for (event in events) {
            if (event.label in pinned) continue
            if (effectiveRank(event) >= Urgency.HIGH.ordinalRank) {
                pinned[event.label] = PinnedAlert(
                    label = event.label,
                    urgency = event.urgency,
                    isPrioritized = event.isPrioritized,
                    detectedAtMs = System.currentTimeMillis(),
                )
            }
        }
    }

    fun acknowledge(label: String) {
        pinned.remove(label)
    }

    fun snapshot(): List<PinnedAlert> =
        pinned.values.sortedByDescending { effectivePinnedRank(it) }

    fun isEmpty(): Boolean = pinned.isEmpty()

    private fun effectiveRank(event: SoundEvent): Int =
        event.urgency.ordinalRank

    private fun effectivePinnedRank(alert: PinnedAlert): Int =
        alert.urgency.ordinalRank
}

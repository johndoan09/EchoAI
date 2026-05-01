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
 *
 * Keyed by (label, urgency) so that a re-classified sound (urgency changed in scene
 * profile) adds a new banner alongside the original rather than being suppressed.
 */
class PinnedAlertTracker {

    private val pinned = mutableMapOf<Pair<String, Urgency>, PinnedAlert>()

    fun onEvents(events: List<SoundEvent>) {
        for (event in events) {
            val key = event.label to event.urgency
            if (key in pinned) continue
            if (event.urgency.ordinalRank >= Urgency.HIGH.ordinalRank) {
                pinned[key] = PinnedAlert(
                    label = event.label,
                    urgency = event.urgency,
                    isPrioritized = event.isPrioritized,
                    detectedAtMs = System.currentTimeMillis(),
                )
            }
        }
    }

    fun acknowledge(label: String, urgency: Urgency) {
        pinned.remove(label to urgency)
    }

    fun acknowledgeAll() {
        pinned.clear()
    }

    fun snapshot(): List<PinnedAlert> =
        pinned.values.sortedByDescending { it.detectedAtMs }

    fun isEmpty(): Boolean = pinned.isEmpty()
}

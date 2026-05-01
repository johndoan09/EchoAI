package com.echoai.domain

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.json.JSONArray
import org.json.JSONObject

data class PinnedAlert(
    val label: String,
    val urgency: Urgency,
    val isPrioritized: Boolean,
    val detectedAtMs: Long,
)

object PinnedAlertUpdates {
    private val _updates = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val updates: SharedFlow<Unit> = _updates.asSharedFlow()

    fun notifyChanged() {
        _updates.tryEmit(Unit)
    }
}

/**
 * Collects HIGH/CRITICAL events into a persistent unacknowledged set. Entries survive
 * until the user explicitly dismisses them, so a sound that fired while the phone was
 * idle stays visible on next interaction.
 *
 * Keyed by (label, urgency) so that a re-classified sound (urgency changed in scene
 * profile) adds a new banner alongside the original rather than being suppressed.
 */
class PinnedAlertTracker(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val pinned = mutableMapOf<Pair<String, Urgency>, PinnedAlert>()

    init {
        loadFromStorage()
    }

    fun onEvents(events: List<SoundEvent>) {
        var changed = false
        for (event in events) {
            if (event.urgency.ordinalRank < Urgency.HIGH.ordinalRank) continue
            val key = event.label to event.urgency
            val existing = pinned[key]
            if (existing != null) {
                // Banner already present — refresh timestamp so it moves to the top
                pinned[key] = existing.copy(detectedAtMs = System.currentTimeMillis())
            } else {
                pinned[key] = PinnedAlert(
                    label = event.label,
                    urgency = event.urgency,
                    isPrioritized = event.isPrioritized,
                    detectedAtMs = System.currentTimeMillis(),
                )
            }
            changed = true
        }
        if (changed) saveToStorage()
    }

    fun acknowledge(label: String, urgency: Urgency) {
        if (pinned.remove(label to urgency) != null) saveToStorage()
    }

    fun acknowledgeAll() {
        pinned.clear()
        saveToStorage()
    }

    fun snapshot(): List<PinnedAlert> =
        pinned.values.sortedByDescending { it.detectedAtMs }

    fun isEmpty(): Boolean = pinned.isEmpty()

    private fun loadFromStorage() {
        pinned.clear()
        val entries = try {
            JSONArray(prefs.getString(KEY_ALERTS, "[]") ?: "[]")
        } catch (_: Exception) {
            JSONArray()
        }
        for (i in 0 until entries.length()) {
            val obj = entries.optJSONObject(i) ?: continue
            val label = obj.optString(KEY_LABEL).takeIf { it.isNotBlank() } ?: continue
            val urgency = Urgency.entries.firstOrNull { it.name == obj.optString(KEY_URGENCY) } ?: continue
            pinned[label to urgency] = PinnedAlert(
                label = label,
                urgency = urgency,
                isPrioritized = obj.optBoolean(KEY_PRIORITIZED, false),
                detectedAtMs = obj.optLong(KEY_DETECTED_AT, System.currentTimeMillis()),
            )
        }
    }

    private fun saveToStorage() {
        val entries = JSONArray()
        pinned.values.forEach { alert ->
            entries.put(JSONObject().apply {
                put(KEY_LABEL, alert.label)
                put(KEY_URGENCY, alert.urgency.name)
                put(KEY_PRIORITIZED, alert.isPrioritized)
                put(KEY_DETECTED_AT, alert.detectedAtMs)
            })
        }
        prefs.edit().putString(KEY_ALERTS, entries.toString()).apply()
        PinnedAlertUpdates.notifyChanged()
    }

    companion object {
        private const val PREFS_NAME = "echo_pinned_alerts"
        private const val KEY_ALERTS = "alerts"
        private const val KEY_LABEL = "label"
        private const val KEY_URGENCY = "urgency"
        private const val KEY_PRIORITIZED = "prioritized"
        private const val KEY_DETECTED_AT = "detected_at"
    }
}

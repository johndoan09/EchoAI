package com.echoai.domain

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class HistoryEntry(
    val label: String,
    val urgency: Urgency,
    val profileName: String,
    val timestampMs: Long,
)

/**
 * Persists HIGH/CRITICAL sound detections to SharedPreferences as a JSON array.
 * Entries older than 24 hours are pruned on every read/write. Same-label events
 * within [DEDUP_WINDOW_MS] are de-duplicated to keep the log concise.
 */
class SoundHistoryManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("echo_history", Context.MODE_PRIVATE)

    private val recentLabels = mutableMapOf<String, Long>()

    fun logHighUrgencyEvents(events: List<SoundEvent>, profileName: String) {
        val now = System.currentTimeMillis()
        val qualifying = events.filter { effectiveRank(it) >= Urgency.HIGH.ordinalRank }
        if (qualifying.isEmpty()) return

        val entries = loadRaw()
        var changed = false
        for (event in qualifying) {
            val lastLogged = recentLabels[event.label]
            if (lastLogged != null && now - lastLogged < DEDUP_WINDOW_MS) continue
            recentLabels[event.label] = now
            entries.put(JSONObject().apply {
                put(KEY_LABEL, event.label)
                put(KEY_URGENCY, event.urgency.name)
                put(KEY_PROFILE, profileName)
                put(KEY_TIMESTAMP, now)
            })
            changed = true
        }
        if (changed) save(prune(entries, now))
    }

    fun dismiss(timestampMs: Long, label: String) {
        val entries = loadRaw()
        val kept = JSONArray()
        for (i in 0 until entries.length()) {
            val obj = entries.optJSONObject(i) ?: continue
            if (obj.optLong(KEY_TIMESTAMP) == timestampMs && obj.optString(KEY_LABEL) == label) continue
            kept.put(obj)
        }
        save(kept)
    }

    fun clearAll() {
        save(JSONArray())
    }

    fun getHistory(): List<HistoryEntry> {
        val entries = prune(loadRaw(), System.currentTimeMillis())
        save(entries)
        val result = mutableListOf<HistoryEntry>()
        for (i in 0 until entries.length()) {
            val obj = entries.optJSONObject(i) ?: continue
            val label = obj.optString(KEY_LABEL).takeIf { it.isNotBlank() } ?: continue
            result += HistoryEntry(
                label = label,
                urgency = Urgency.entries.firstOrNull { it.name == obj.optString(KEY_URGENCY) }
                    ?: Urgency.LOW,
                profileName = obj.optString(KEY_PROFILE),
                timestampMs = obj.optLong(KEY_TIMESTAMP),
            )
        }
        return result.sortedByDescending { it.timestampMs }
    }

    private fun effectiveRank(event: SoundEvent): Int =
        event.urgency.ordinalRank

    private fun loadRaw(): JSONArray = try {
        JSONArray(prefs.getString(KEY_HISTORY, "[]") ?: "[]")
    } catch (_: Exception) { JSONArray() }

    private fun save(entries: JSONArray) {
        prefs.edit().putString(KEY_HISTORY, entries.toString()).apply()
    }

    private fun prune(entries: JSONArray, now: Long): JSONArray {
        val cutoff = now - RETENTION_MS
        val pruned = JSONArray()
        for (i in 0 until entries.length()) {
            val obj = entries.optJSONObject(i) ?: continue
            if (obj.optLong(KEY_TIMESTAMP) >= cutoff) pruned.put(obj)
        }
        return pruned
    }

    companion object {
        private const val KEY_HISTORY = "history"
        private const val KEY_LABEL = "label"
        private const val KEY_URGENCY = "urgency"
        private const val KEY_PROFILE = "profile"
        private const val KEY_TIMESTAMP = "ts"
        private const val RETENTION_MS = 24 * 60 * 60 * 1000L
        private const val DEDUP_WINDOW_MS = 30_000L
    }
}

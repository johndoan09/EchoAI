package com.echoai.domain

import android.content.Context
import android.util.Log
import org.json.JSONObject

enum class Urgency(val color: Int, val ordinalRank: Int) {
    CRITICAL(0xFFE53935.toInt(), 3),
    HIGH(0xFFFB8C00.toInt(), 2),
    MEDIUM(0xFFFDD835.toInt(), 1),
    LOW(0xFF42A5F5.toInt(), 0);
}

/**
 * Maps YAMNet display_name labels to urgency tiers using `assets/urgency_map.json`.
 * Lookup: exact match first, then substring containment, then default LOW.
 */
class UrgencyClassifier(context: Context) {

    private val exactMap: Map<String, Urgency>
    private val entries: List<Pair<String, Urgency>>

    init {
        val map = mutableMapOf<String, Urgency>()
        try {
            val json = context.assets.open("urgency_map.json")
                .bufferedReader().use { it.readText() }
            val root = JSONObject(json)
            for (tier in Urgency.entries) {
                val arr = root.optJSONArray(tier.name) ?: continue
                for (i in 0 until arr.length()) {
                    map[arr.getString(i)] = tier
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load urgency_map.json, all labels default to LOW", e)
        }
        exactMap = map
        entries = map.entries.map { it.key to it.value }
    }

    fun classify(label: String): Urgency {
        exactMap[label]?.let { return it }
        for ((key, urgency) in entries) {
            if (label.contains(key, ignoreCase = true) ||
                key.contains(label, ignoreCase = true)
            ) return urgency
        }
        return Urgency.LOW
    }

    companion object {
        private const val TAG = "UrgencyClassifier"
    }
}

package com.echoai.domain

import android.content.Context
import android.util.Log
import org.json.JSONObject

enum class Urgency(val color: Int, val ordinalRank: Int) {
    CRITICAL(0xFFD63A2F.toInt(), 3),
    HIGH(0xFFD4700A.toInt(), 2),
    MEDIUM(0xFFA8880A.toInt(), 1),
    LOW(0xFF2F8F5C.toInt(), 0);

    /** Tinted background color used for badges / banner backgrounds (urgency.color × 0.14 alpha). */
    val tintBackground: Int get() = (color and 0x00FFFFFF) or 0x24000000
    /** Darker text color used inside badges, per design tokens. */
    val textColor: Int get() = when (this) {
        CRITICAL -> 0xFFB82E24.toInt()
        HIGH -> 0xFFB85C00.toInt()
        MEDIUM -> 0xFF876C08.toInt()
        LOW -> 0xFF226B45.toInt()
    }
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

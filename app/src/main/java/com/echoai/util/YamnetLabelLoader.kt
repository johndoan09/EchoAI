package com.echoai.util

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * Loads the list of labels the user can pick from in the Scene Profile screen.
 *
 * Source of truth is `assets/yamnet_consolidation_map.json` — the 42 application
 * groups that [com.echoai.ml.YamnetClassifier] actually emits after top-k noisy-OR
 * consolidation. The raw 521-class YAMNet CSV is no longer surfaced to users.
 */
object YamnetLabelLoader {

    private const val TAG = "YamnetLabelLoader"

    fun loadAll(context: Context): List<String> = try {
        val raw = context.assets.open("yamnet_consolidation_map.json")
            .bufferedReader().use { it.readText() }
        val groups = JSONObject(raw).getJSONArray("groups")
        buildList(groups.length()) {
            for (i in 0 until groups.length()) {
                add(groups.getJSONObject(i).getString("name"))
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to load yamnet_consolidation_map.json", e)
        emptyList()
    }
}

package com.echoai.ml

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Maps YAMNet's 521 raw classes to a smaller set of application-meaningful groups.
 *
 * Per-group score is computed as **top-k noisy-OR** over member sigmoid scores:
 *   score(G) = 1 − ∏ (1 − p_i)  over the top [noisyOrTopK] strongest members of G
 *
 * Why noisy-OR: when several sub-classes of a group fire at moderate confidence
 * (e.g. Music=0.5 and Classical=0.7), the evidence compounds — the group should
 * read higher than any single member (here, 0.85). Plain max would discard this.
 *
 * Why top-k (not full noisy-OR): big buckets like Music (156 ids) and Other (103)
 * would saturate near 1.0 every window from noise-floor activations alone if all
 * members were included. Top-3 bounds the compounding to the strongest evidence
 * and ignores the long tail of background activations.
 *
 * Each output stays an independent score in [0, 1] — same shape as YAMNet's
 * native sigmoid head, just over fewer classes.
 *
 * Backed by `assets/yamnet_consolidation_map.json`. Failure to load returns null
 * from [load] and the classifier transparently falls back to raw 521-class output.
 */
class YamnetConsolidationMap(
    val groupNames: List<String>,
    private val yamnetIdToGroup: IntArray,
    val noisyOrTopK: Int = 3,
) {
    val groupCount: Int get() = groupNames.size
    val yamnetClassCount: Int get() = yamnetIdToGroup.size

    /** Pre-bucketed member ids per group, for fast per-window consolidation. */
    private val groupMembers: Array<IntArray> = Array(groupCount) { g ->
        (0 until yamnetClassCount).filter { yamnetIdToGroup[it] == g }.toIntArray()
    }

    /**
     * Top-k noisy-OR pool [rawScores] (length [yamnetClassCount]) into [out]
     * (length >= [groupCount]). Unmapped raw ids (yamnetIdToGroup[i] == -1) are
     * ignored — they have no group.
     */
    fun consolidate(rawScores: FloatArray, out: FloatArray) {
        require(rawScores.size == yamnetClassCount) {
            "rawScores size ${rawScores.size} != $yamnetClassCount"
        }
        require(out.size >= groupCount) {
            "out size ${out.size} < $groupCount"
        }
        for (g in 0 until groupCount) {
            out[g] = topKNoisyOr(rawScores, groupMembers[g], noisyOrTopK)
        }
    }

    /** 1 − ∏(1 − p_i) over the top [k] member scores. Result clamped to [0, 1]. */
    private fun topKNoisyOr(rawScores: FloatArray, members: IntArray, k: Int): Float {
        if (members.isEmpty()) return 0f
        if (members.size == 1) {
            return rawScores[members[0]].coerceIn(0f, 1f)
        }
        // Sort member scores ascending so the top-k live at the tail.
        val scores = FloatArray(members.size) { rawScores[members[it]] }
        scores.sort()
        val take = minOf(k, scores.size)
        var prod = 1.0
        for (i in scores.size - take until scores.size) {
            val p = scores[i].coerceIn(0f, 1f).toDouble()
            prod *= (1.0 - p)
        }
        return (1.0 - prod).toFloat().coerceIn(0f, 1f)
    }

    companion object {
        private const val TAG = "YamnetConsolidationMap"

        fun load(
            context: Context,
            asset: String = "yamnet_consolidation_map.json",
            yamnetClassCount: Int = 521,
        ): YamnetConsolidationMap? = runCatching {
            val raw = BufferedReader(InputStreamReader(context.assets.open(asset))).use { it.readText() }
            val root = JSONObject(raw)
            val groups = root.getJSONArray("groups")
            val names = ArrayList<String>(groups.length())
            val idToGroup = IntArray(yamnetClassCount) { -1 }
            for (gi in 0 until groups.length()) {
                val grp = groups.getJSONObject(gi)
                names += grp.getString("name")
                val ids = grp.getJSONArray("yamnet_ids")
                for (k in 0 until ids.length()) {
                    val id = ids.getInt(k)
                    if (id !in 0 until yamnetClassCount) {
                        Log.w(TAG, "id $id (group '${grp.getString("name")}') out of range, skipped")
                        continue
                    }
                    if (idToGroup[id] >= 0) {
                        Log.w(TAG, "id $id remapped from group ${idToGroup[id]} to $gi")
                    }
                    idToGroup[id] = gi
                }
            }
            var unmapped = 0
            for (i in 0 until yamnetClassCount) if (idToGroup[i] < 0) unmapped++
            if (unmapped > 0) {
                Log.w(TAG, "$unmapped of $yamnetClassCount YAMNet ids unmapped (will be ignored)")
            } else {
                Log.i(TAG, "Consolidation map loaded: ${names.size} groups, all $yamnetClassCount ids mapped")
            }
            YamnetConsolidationMap(names, idToGroup)
        }.getOrElse {
            Log.e(TAG, "Failed to load $asset", it)
            null
        }
    }
}

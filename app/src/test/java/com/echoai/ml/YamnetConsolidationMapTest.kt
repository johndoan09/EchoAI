package com.echoai.ml

import org.junit.Assert.*
import org.junit.Test

class YamnetConsolidationMapTest {

    /**
     * Build a map without Android assets. [groups] maps group index → list of yamnet ids
     * that belong to it. Ids not in any group stay at -1 (unmapped).
     */
    private fun makeMap(
        yamnetClassCount: Int,
        groups: List<List<Int>>,
        names: List<String> = groups.indices.map { "Group$it" },
        noisyOrTopK: Int = 3,
    ): YamnetConsolidationMap {
        val idToGroup = IntArray(yamnetClassCount) { -1 }
        for ((gi, members) in groups.withIndex()) {
            for (id in members) idToGroup[id] = gi
        }
        return YamnetConsolidationMap(names, idToGroup, noisyOrTopK)
    }

    // --- Basic properties ---

    @Test
    fun groupCountMatchesNumberOfGroups() {
        val map = makeMap(10, listOf(listOf(0, 1), listOf(2, 3, 4)))
        assertEquals(2, map.groupCount)
    }

    @Test
    fun yamnetClassCountMatchesInput() {
        val map = makeMap(521, listOf(listOf(0)))
        assertEquals(521, map.yamnetClassCount)
    }

    // --- Single member ---

    @Test
    fun singleMemberGroupReturnsItsScore() {
        val map = makeMap(5, listOf(listOf(2)))
        val rawScores = FloatArray(5) { i -> if (i == 2) 0.6f else 0f }
        val out = FloatArray(1)
        map.consolidate(rawScores, out)
        assertEquals(0.6f, out[0], 0.001f)
    }

    @Test
    fun singleMemberAtOneReturnsOne() {
        val map = makeMap(5, listOf(listOf(0)))
        val rawScores = FloatArray(5) { if (it == 0) 1.0f else 0f }
        val out = FloatArray(1)
        map.consolidate(rawScores, out)
        assertEquals(1.0f, out[0], 0.001f)
    }

    // --- Empty group ---

    @Test
    fun groupWithNoMembersReturnsZero() {
        val map = makeMap(5, listOf(emptyList(), listOf(0)))
        val rawScores = FloatArray(5) { 0.8f }
        val out = FloatArray(2)
        map.consolidate(rawScores, out)
        assertEquals(0f, out[0], 0.001f) // empty group → 0
        assertTrue(out[1] > 0f)           // non-empty group → some score
    }

    // --- Noisy-OR compounding ---

    @Test
    fun twoMembersNoisyOrCompoundsScores() {
        // 1 - (1 - 0.5) * (1 - 0.7) = 1 - 0.5 * 0.3 = 0.85
        val map = makeMap(5, listOf(listOf(0, 1)))
        val rawScores = floatArrayOf(0.5f, 0.7f, 0f, 0f, 0f)
        val out = FloatArray(1)
        map.consolidate(rawScores, out)
        assertEquals(0.85f, out[0], 0.001f)
    }

    @Test
    fun noisyOrResultExceedsAnyIndividualMember() {
        val map = makeMap(5, listOf(listOf(0, 1, 2)))
        val rawScores = floatArrayOf(0.4f, 0.5f, 0.6f, 0f, 0f)
        val out = FloatArray(1)
        map.consolidate(rawScores, out)
        assertTrue("Noisy-OR result (${out[0]}) should exceed max member (0.6f)", out[0] > 0.6f)
    }

    @Test
    fun topKLimitsCompounding() {
        // 4 members with scores [0.1, 0.2, 0.5, 0.7]; k=2 uses only top-2 = [0.5, 0.7].
        // 1 - (1 - 0.5) * (1 - 0.7) = 0.85
        val map = makeMap(10, listOf(listOf(0, 1, 2, 3)), noisyOrTopK = 2)
        val rawScores = FloatArray(10).also {
            it[0] = 0.1f; it[1] = 0.2f; it[2] = 0.5f; it[3] = 0.7f
        }
        val out = FloatArray(1)
        map.consolidate(rawScores, out)
        assertEquals(0.85f, out[0], 0.001f)
    }

    @Test
    fun topKOneEffectivelyMaxPool() {
        // k=1 → takes top-1 member only → result equals that member's score
        val map = makeMap(5, listOf(listOf(0, 1, 2)), noisyOrTopK = 1)
        val rawScores = floatArrayOf(0.3f, 0.7f, 0.5f, 0f, 0f)
        val out = FloatArray(1)
        map.consolidate(rawScores, out)
        assertEquals(0.7f, out[0], 0.001f)
    }

    // --- All-zero / all-one edge cases ---

    @Test
    fun zeroScoresProduceZeroOutput() {
        val map = makeMap(5, listOf(listOf(0, 1, 2)))
        val rawScores = FloatArray(5) { 0f }
        val out = FloatArray(1)
        map.consolidate(rawScores, out)
        assertEquals(0f, out[0], 0.001f)
    }

    @Test
    fun allOneScoresProduceNearOneOutput() {
        val map = makeMap(5, listOf(listOf(0, 1, 2)))
        val rawScores = FloatArray(5) { 1.0f }
        val out = FloatArray(1)
        map.consolidate(rawScores, out)
        assertEquals(1.0f, out[0], 0.001f)
    }

    // --- Unmapped ids ---

    @Test
    fun unmappedIdsDoNotContributeToAnyGroup() {
        // id 3 and 4 are unmapped (-1). They should not affect group 0's score.
        val map = makeMap(5, listOf(listOf(0)))  // only id 0 in group 0; ids 1..4 unmapped
        val rawScores = FloatArray(5) { 1.0f }   // everyone fires at 1.0
        val out = FloatArray(1)
        map.consolidate(rawScores, out)
        // Group 0 has only id 0, so score = 1.0 (not compounded with unmapped ids)
        assertEquals(1.0f, out[0], 0.001f)
    }

    // --- Output bounds ---

    @Test
    fun outputAlwaysClamped0To1() {
        val map = makeMap(5, listOf(listOf(0, 1, 2, 3, 4)))
        // Scores slightly outside the logical [0,1] range — coerceIn should handle this
        val rawScores = floatArrayOf(1.1f, 1.2f, 1.3f, 1.4f, 1.5f)
        val out = FloatArray(1)
        map.consolidate(rawScores, out)
        assertTrue("Output should be ≤ 1.0, was ${out[0]}", out[0] <= 1.0f)
        assertTrue("Output should be ≥ 0.0, was ${out[0]}", out[0] >= 0.0f)
    }

    // --- Input size validation ---

    @Test(expected = IllegalArgumentException::class)
    fun consolidateThrowsOnWrongInputSize() {
        val map = makeMap(5, listOf(listOf(0)))
        map.consolidate(FloatArray(3), FloatArray(1)) // wrong input size
    }

    // --- Multiple groups ---

    @Test
    fun multipleGroupsComputedIndependently() {
        // Group 0: ids 0,1. Group 1: ids 2,3.
        val map = makeMap(4, listOf(listOf(0, 1), listOf(2, 3)))
        val rawScores = floatArrayOf(0.5f, 0.5f, 0.8f, 0.8f)
        val out = FloatArray(2)
        map.consolidate(rawScores, out)
        // Group 0: 1 - 0.5*0.5 = 0.75
        assertEquals(0.75f, out[0], 0.001f)
        // Group 1: 1 - 0.2*0.2 = 0.96
        assertEquals(0.96f, out[1], 0.001f)
    }
}

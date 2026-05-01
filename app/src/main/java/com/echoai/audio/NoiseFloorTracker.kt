package com.echoai.audio

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Tracks the per-stream background noise floor as a rolling minimum of recent window
 * RMSes. The minimum (rather than mean / median) is used because the floor we care
 * about is the *quietest* recent state — that's what defines "no event happening
 * right now" and gives the cleanest reference for SNR gating.
 *
 * Classifier-gated update: history only accepts windows the caller considers silent
 * ([update] with `isSilent=true`). During sustained sound the floor freezes — without
 * this, continuous speech / music / traffic would train the floor up to its own
 * level within ~6 s and the gate would stop firing on it. Caller is expected to
 * derive [isSilent] from the post-classification state (gate closed, or top label
 * is Silence, or no class above the priority threshold).
 *
 * Warmup: the first [warmupWindows] observations bypass the silence check so the
 * floor has a chance to settle even if the session starts mid-sound. Otherwise
 * the floor would stay at [floorMinimum] forever, making SNR meaningless.
 *
 * Single-threaded — caller (MainActivity processing loop) is the only updater.
 */
class NoiseFloorTracker(
    private val historySize: Int = DEFAULT_HISTORY_SIZE,
    private val floorMinimum: Float = DEFAULT_FLOOR_MINIMUM,
    private val warmupWindows: Int = DEFAULT_WARMUP_WINDOWS,
) {
    private val history = ArrayDeque<Float>(historySize)
    private var observationCount = 0

    /** Most recent floor estimate in s16-RMS units. Bounded below by [floorMinimum]. */
    var floorRms: Float = floorMinimum
        private set

    /** True if the most recent [update] call actually modified the floor history. */
    var lastUpdateApplied: Boolean = false
        private set

    /**
     * Conditionally fold [currentRms] into the rolling-min history.
     *  - During warmup (first [warmupWindows] observations), [isSilent] is ignored
     *    and every window is added.
     *  - After warmup, the window is added only when [isSilent] is true; otherwise
     *    the floor freezes for this step.
     */
    fun update(currentRms: Float, isSilent: Boolean) {
        observationCount++
        val inWarmup = observationCount <= warmupWindows
        val shouldAdd = inWarmup || isSilent
        lastUpdateApplied = shouldAdd
        if (!shouldAdd) return
        history.addLast(currentRms)
        if (history.size > historySize) history.removeFirst()
        var min = Float.MAX_VALUE
        for (v in history) if (v < min) min = v
        floorRms = min.coerceAtLeast(floorMinimum)
    }

    /** Returns SNR in dB of [currentRms] above [floorRms]. 0 if either side is invalid. */
    fun snrDb(currentRms: Float): Float {
        if (floorRms <= 0f || currentRms <= 0f) return 0f
        return 20f * log10(currentRms / floorRms)
    }

    fun reset() {
        history.clear()
        floorRms = floorMinimum
        observationCount = 0
        lastUpdateApplied = false
    }

    companion object {
        /** 48 windows × 125 ms hop = 6 s rolling buffer at 8 Hz pipeline rate. */
        const val DEFAULT_HISTORY_SIZE = 48
        /** Smallest representable floor; prevents div-by-zero in dB when truly silent. */
        const val DEFAULT_FLOOR_MINIMUM = 1f
        /** First N windows accepted unconditionally so the floor can settle on launch. */
        const val DEFAULT_WARMUP_WINDOWS = 16
    }
}

/**
 * RMS of the same 4-channel mono downmix that feeds YAMNet. Cheap (one pass over
 * the window, no allocations).
 */
fun AudioWindow.monoRms(): Float {
    val n = minOf(bottomLeft.size, bottomRight.size, backLeft.size, backRight.size)
    if (n == 0) return 0f
    var sumSq = 0.0
    for (i in 0 until n) {
        val mono = (bottomLeft[i].toInt() + bottomRight[i].toInt() +
            backLeft[i].toInt() + backRight[i].toInt()) / 4
        sumSq += mono.toDouble() * mono.toDouble()
    }
    return sqrt(sumSq / n).toFloat()
}

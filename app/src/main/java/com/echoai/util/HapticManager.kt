package com.echoai.util

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.echoai.domain.SoundEvent
import com.echoai.domain.Urgency

/**
 * Fires urgency-differentiated vibration patterns. Each tier has a distinct pattern
 * and a cooldown interval to avoid continuous buzzing during sustained detections.
 */
class HapticManager(context: Context) {

    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager)
            .defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    private val patterns = mapOf(
        Urgency.CRITICAL to VibrationEffect.createWaveform(
            longArrayOf(0, 100, 50, 100, 50, 100),
            intArrayOf(0, 255, 0, 255, 0, 255),
            -1,
        ),
        Urgency.HIGH to VibrationEffect.createWaveform(
            longArrayOf(0, 200, 100, 200),
            intArrayOf(0, 200, 0, 200),
            -1,
        ),
    )

    private val cooldownMs = mapOf(
        Urgency.CRITICAL to 2_000L,
        Urgency.HIGH to 3_000L,
    )

    private val lastFiredAt = mutableMapOf<Urgency, Long>()

    fun vibrateFor(urgency: Urgency) {
        val now = System.currentTimeMillis()
        val minInterval = cooldownMs[urgency] ?: 3_000L
        val last = lastFiredAt[urgency] ?: 0L
        if (now - last < minInterval) return

        patterns[urgency]?.let { vibrator.vibrate(it) }
        lastFiredAt[urgency] = now
    }

    /** Fire the single highest-urgency pattern from the active event list. Only CRITICAL and HIGH trigger haptics. */
    fun vibrateForHighest(events: List<SoundEvent>) {
        val top = events.maxByOrNull { it.urgency.ordinalRank } ?: return
        if (top.urgency.ordinalRank >= Urgency.HIGH.ordinalRank) {
            vibrateFor(top.urgency)
        }
    }
}

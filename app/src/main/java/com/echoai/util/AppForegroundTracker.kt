package com.echoai.util

import java.util.concurrent.atomic.AtomicInteger

/**
 * Lightweight process-local foreground tracker used by services that should only notify
 * when the user is outside EchoAI. Each visible Activity increments the count.
 */
object AppForegroundTracker {
    private val visibleActivities = AtomicInteger(0)

    fun onActivityStarted() {
        visibleActivities.incrementAndGet()
    }

    fun onActivityStopped() {
        visibleActivities.updateAndGet { count -> (count - 1).coerceAtLeast(0) }
    }

    fun isInForeground(): Boolean = visibleActivities.get() > 0
}

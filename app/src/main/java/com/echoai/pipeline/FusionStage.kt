package com.echoai.pipeline

import com.echoai.domain.ClassificationResult
import com.echoai.domain.EventTracker
import com.echoai.domain.LocalizationResult
import com.echoai.domain.SoundEvent
import com.echoai.domain.SoundProfile
import com.echoai.domain.Urgency

class FusionStage(
    private val tracker: EventTracker = EventTracker(),
) {

    fun process(
        classification: ClassificationResult,
        localization: LocalizationResult,
    ): List<SoundEvent> {
        tracker.update(classification, localization)
        return tracker.snapshot(asOfNanos = localization.captureTimestampNanos)
    }

    /** Apply profile settings to the tracker: priority labels and per-label urgency overrides. */
    fun applyProfile(profile: SoundProfile) {
        tracker.priorityLabels = profile.priorityLabels
        tracker.urgencyOverrides = profile.urgencyOverrides
    }
}

package com.echoai.pipeline

import com.echoai.domain.ClassificationResult
import com.echoai.domain.EventTracker
import com.echoai.domain.LocalizationResult
import com.echoai.domain.SoundEvent

/**
 * Stage-(a) fusion: top-1 attribution + temporal accumulation.
 *  - Take the top YAMNet label for the window.
 *  - Attach the current localization estimate to it.
 *  - Refresh that label's tracked event in [EventTracker]; drop events that go stale.
 *
 * Stage-(b) extension point: when per-pair YAMNet is wired (running classification on
 * `bottomMono` and `backMono` separately), replace the single [ClassificationResult] arg
 * with a `PerPairClassification` and route per-class labels to the side with the higher
 * per-class confidence. The tracker API stays the same.
 */
class FusionStage(
    private val tracker: EventTracker = EventTracker(),
) {

    /**
     * Apply this window's classification + localization to the rolling state and
     * return the current set of active events.
     */
    fun process(
        classification: ClassificationResult,
        localization: LocalizationResult,
    ): List<SoundEvent> {
        tracker.update(classification, localization)
        return tracker.snapshot(asOfNanos = localization.captureTimestampNanos)
    }
}

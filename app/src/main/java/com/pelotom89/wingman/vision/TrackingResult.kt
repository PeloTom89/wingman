package com.pelotom89.wingman.vision

/** Normalized [0,1] box in frame coordinates — resolution-independent so flightcontrol/
 *  math (offset-from-center, size-as-distance-proxy) doesn't care about video resolution. */
data class BoundingBox(
    val centerX: Float,
    val centerY: Float,
    val width: Float,
    val height: Float,
) {
    /** Rough area-as-distance-proxy: a bigger box means the subject is closer. Only
     *  meaningful relative to itself frame-to-frame, not as an absolute distance. */
    val area: Float get() = width * height
}

sealed class TrackingResult {
    data object NotStarted : TrackingResult()
    data class Tracking(val box: BoundingBox, val confidence: Float) : TrackingResult()

    /** [framesSinceSeen] backs FlightStateMachine's debounce — see plan: VisualTrack should
     *  only fall through to GpsGuided after ~1.5-2.5s of continuous loss, not one dropped
     *  frame, so this needs to accumulate rather than flip straight to "lost." */
    data class Lost(val framesSinceSeen: Int, val lastKnownBox: BoundingBox?) : TrackingResult()
}

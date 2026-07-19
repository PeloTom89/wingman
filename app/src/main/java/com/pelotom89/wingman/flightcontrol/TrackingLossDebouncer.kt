package com.pelotom89.wingman.flightcontrol

import com.pelotom89.wingman.vision.TrackingResult

/**
 * Hysteresis for the VisualTrack <-> GpsGuided transition, per the plan: switch to
 * GpsGuided only after ~1.5-2.5s of CONTINUOUS tracking loss (not one dropped frame), and
 * switch back only after a similar sustained reacquisition window — otherwise a subject
 * flickering in and out near a tree line would flap the aircraft's behavior every frame.
 *
 * Takes an injected clock so it's unit-testable against synthetic [TrackingResult]
 * sequences with a fake time source, per the plan's testing note — no real-time waiting
 * needed in tests.
 */
class TrackingLossDebouncer(
    private val lostThresholdMillis: Long = 2000,
    private val reacquireThresholdMillis: Long = 800,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private var lostSinceMillis: Long? = null
    private var seenSinceMillis: Long? = null

    /**
     * Call once per tick with the latest tracking result and the currently active flight
     * state (only VisualTrack/GpsGuided are meaningful inputs here; other states should
     * not consult this class). Returns the state to move to, or null to stay put.
     */
    fun onTick(result: TrackingResult, currentState: FlightState): FlightState? {
        val now = nowMillis()
        return when (result) {
            is TrackingResult.Tracking -> {
                lostSinceMillis = null
                if (currentState is FlightState.GpsGuided) {
                    val seenSince = seenSinceMillis ?: now.also { seenSinceMillis = it }
                    if (now - seenSince >= reacquireThresholdMillis) FlightState.VisualTrack else null
                } else {
                    seenSinceMillis = null
                    null
                }
            }
            is TrackingResult.Lost, TrackingResult.NotStarted -> {
                seenSinceMillis = null
                if (currentState is FlightState.VisualTrack) {
                    val lostSince = lostSinceMillis ?: now.also { lostSinceMillis = it }
                    if (now - lostSince >= lostThresholdMillis) FlightState.GpsGuided else null
                } else {
                    lostSinceMillis = null
                    null
                }
            }
        }
    }
}

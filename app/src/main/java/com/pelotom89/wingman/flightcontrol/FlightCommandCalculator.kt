package com.pelotom89.wingman.flightcontrol

import com.pelotom89.wingman.sdk.VirtualStickCommand
import kotlin.math.abs
import kotlin.math.atan2

/**
 * Pure proportional-controller math translating GPS state into stick/gimbal commands —
 * kept free of SDK/Flow types so it's directly unit-testable. [ObstacleSafetyClamp] and
 * [SafetyLimits] are applied afterward by FlightStateMachine; this class only expresses
 * "what would get us toward/around the subject," not "is that safe."
 *
 * GPS-only following (vision tracking removed — see README): the aircraft holds a standoff
 * distance from the subject's phone-GPS position rather than closing the gap to zero (the
 * old GpsGuided fallback behavior, designed for "go re-acquire visual," not "follow
 * continuously"), and the gimbal — not the aircraft's own yaw range or any vision-based
 * framing — is what keeps the camera pointed at the subject vertically. The aircraft's own
 * yaw still faces the subject horizontally so the subject stays within the gimbal's
 * forward-facing view without needing independent gimbal yaw (whose range on the Mini 4
 * Pro's compact 3-axis gimbal is unverified — see sdk/GimbalController.kt).
 */
class FlightCommandCalculator(
    private val targetDistanceMeters: Double = 10.0,
    private val distanceToleranceMeters: Double = 2.0,
    private val distanceGainMetersPerSecondPerMeter: Double = 0.3,
    // Deliberately conservative (1.5 m/s) for the FIRST outdoor autonomous-following test
    // (2026-07-27) -- walking pace, gives the operator reaction time to STOP if the untested
    // follow logic misbehaves. Raise toward 3 m/s (and eventually cyclist speeds) only after
    // the behavior is confirmed correct in real flight, per README's milestone progression.
    private val maxApproachSpeedMetersPerSecond: Double = 1.5,
) {
    /**
     * Yaws to face the subject's phone-GPS position, then approaches or backs off to hold
     * [targetDistanceMeters] (within [distanceToleranceMeters], to avoid hunting back and
     * forth right at the target). Vertical is held level here — altitude is a separate,
     * operator/SafetyLimits-set concern, not something this following logic adjusts.
     */
    fun computeFollowCommand(
        aircraft: LatLon,
        aircraftHeadingDegrees: Double,
        subject: LatLon,
    ): VirtualStickCommand {
        val distanceMeters = haversineMeters(aircraft, subject)
        val targetBearing = bearingDegrees(aircraft, subject)
        var headingError = targetBearing - aircraftHeadingDegrees
        headingError = ((headingError + 180) % 360 + 360) % 360 - 180 // normalize to [-180, 180]

        // Don't commit to forward/back speed while still substantially off-heading — turn
        // first, then hold distance, rather than crabbing sideways toward a moving target.
        val headingAligned = abs(headingError) < HEADING_ALIGNMENT_THRESHOLD_DEGREES
        val distanceError = distanceMeters - targetDistanceMeters // positive = too far (approach)
        val holdSpeed = if (headingAligned && abs(distanceError) > distanceToleranceMeters) {
            (distanceError * distanceGainMetersPerSecondPerMeter)
                .coerceIn(-maxApproachSpeedMetersPerSecond, maxApproachSpeedMetersPerSecond)
        } else {
            0.0
        }

        return VirtualStickCommand(
            pitchMetersPerSecond = holdSpeed,
            rollMetersPerSecond = 0.0,
            yawDegreesPerSecond = headingError * YAW_RATE_GAIN_DEGREES_PER_SECOND_PER_DEGREE_ERROR,
            verticalMetersPerSecond = 0.0,
        )
    }

    private companion object {
        const val HEADING_ALIGNMENT_THRESHOLD_DEGREES = 15.0
        const val YAW_RATE_GAIN_DEGREES_PER_SECOND_PER_DEGREE_ERROR = 1.5
    }
}

/**
 * Gimbal pitch angle (degrees; DJI convention assumed 0 = level/forward, negative = looking
 * down — unverified against the real API, see sdk/GimbalController.kt) to point the camera
 * directly at a ground-level point [horizontalDistanceMeters] away and [altitudeAglMeters]
 * below the aircraft. Per the user's simplification: the subject (a cyclist) is always
 * assumed to be at ground level, so this needs only altitude and horizontal distance — no
 * subject-height estimate. Clamped to never look upward (0..-90), since the aircraft should
 * always be flying above the subject's ground level.
 */
fun computeGimbalPitchDegrees(altitudeAglMeters: Double, horizontalDistanceMeters: Double): Double {
    val angle = Math.toDegrees(atan2(altitudeAglMeters, horizontalDistanceMeters.coerceAtLeast(0.01)))
    return (-angle).coerceIn(-90.0, 0.0)
}

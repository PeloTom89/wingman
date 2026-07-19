package com.pelotom89.wingman.flightcontrol

import com.pelotom89.wingman.sdk.VirtualStickCommand
import com.pelotom89.wingman.vision.BoundingBox
import kotlin.math.abs

/**
 * Pure proportional-controller math translating tracking/GPS state into stick commands —
 * kept free of SDK/Flow types so it's directly unit-testable. [ObstacleSafetyClamp] and
 * [SafetyLimits] are applied afterward by FlightStateMachine; this class only expresses
 * "what would get us toward the subject," not "is that safe."
 */
class FlightCommandCalculator(
    private val yawGainDegreesPerSecondPerUnitOffset: Double = 60.0,
    private val verticalGainMetersPerSecondPerUnitOffset: Double = 1.0,
    private val targetBoxArea: Float = 0.15f,
    private val pitchGainMetersPerSecondPerUnitAreaError: Double = 4.0,
    private val gpsPitchGainMetersPerSecondPerMeter: Double = 0.05,
    private val gpsMaxApproachSpeedMetersPerSecond: Double = 3.0,
) {
    /**
     * VisualTrack: centers the subject via yaw + a small vertical correction, and holds
     * a target on-screen box size via forward/back pitch (bigger box than target = too
     * close, back off; smaller = too far, close in). Gimbal-based re-centering
     * (sdk/GimbalController) is expected to handle small framing corrections upstream of
     * this — this function assumes the aircraft itself still needs to move to hold
     * distance and follow horizontal travel the gimbal's own range can't cover.
     */
    fun computeVisualTrackCommand(box: BoundingBox): VirtualStickCommand {
        val horizontalOffset = box.centerX - 0.5f // negative = subject left of center
        val verticalOffset = box.centerY - 0.5f // negative = subject above center
        val areaError = box.area - targetBoxArea // positive = subject too close

        return VirtualStickCommand(
            pitchMetersPerSecond = -areaError * pitchGainMetersPerSecondPerUnitAreaError,
            rollMetersPerSecond = 0.0, // lateral repositioning left to yaw + forward motion, not strafing
            yawDegreesPerSecond = horizontalOffset * yawGainDegreesPerSecondPerUnitOffset,
            verticalMetersPerSecond = -verticalOffset * verticalGainMetersPerSecondPerUnitOffset,
        )
    }

    /**
     * GpsGuided: yaws to face the subject's phone-GPS position, then closes distance at a
     * speed proportional to (but capped well below) the remaining distance — avoids a
     * full-speed final approach toward a person. Vertical is held level; obstacle/altitude
     * safety layers are responsible for anything more than that.
     */
    fun computeGpsGuidedCommand(
        aircraft: LatLon,
        aircraftHeadingDegrees: Double,
        subject: LatLon,
    ): VirtualStickCommand {
        val distanceMeters = haversineMeters(aircraft, subject)
        val targetBearing = bearingDegrees(aircraft, subject)
        var headingError = targetBearing - aircraftHeadingDegrees
        headingError = ((headingError + 180) % 360 + 360) % 360 - 180 // normalize to [-180, 180]

        // Don't commit to forward speed while still substantially off-heading — turn
        // first, then approach, rather than crabbing sideways toward a moving target.
        val headingAligned = abs(headingError) < HEADING_ALIGNMENT_THRESHOLD_DEGREES
        val approachSpeed = if (headingAligned) {
            (distanceMeters * gpsPitchGainMetersPerSecondPerMeter)
                .coerceIn(0.0, gpsMaxApproachSpeedMetersPerSecond)
        } else {
            0.0
        }

        return VirtualStickCommand(
            pitchMetersPerSecond = approachSpeed,
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

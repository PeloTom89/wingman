package com.pelotom89.wingman.flightcontrol

import com.pelotom89.wingman.sdk.ObstacleDirection
import com.pelotom89.wingman.sdk.ObstacleReading
import com.pelotom89.wingman.sdk.VirtualStickCommand

/**
 * Custom replacement for DJI's APAS obstacle avoidance, which is confirmed DISABLED
 * whenever VirtualStick control is active on the Mini 4 Pro (unlike the M300/M350/M30/
 * Mavic 3E/3M lineup, which keep APAS active under VirtualStick — see the plan's
 * "Hardware tradeoff" decision: staying on the Mini 4 Pro means this file, not a firmware
 * feature, is what stands between the aircraft and a collision).
 *
 * This is the most safety-critical pure-logic file in the app. Kept as a pure function of
 * (proposed command, latest obstacle readings) -> clamped command specifically so it's
 * exhaustively unit-testable against synthetic [ObstacleReading] sequences per the plan's
 * testing note, with zero SDK/threading dependencies to get in the way of that.
 *
 * Applied LAST, after [SafetyLimits.clampSpeed], in every flight state — VisualTrack and
 * GpsGuided both route their proposed command through here before it reaches
 * VirtualStickController.
 */
class ObstacleSafetyClamp(
    private val brakingDistanceMeters: Double = 3.0,
    private val warningDistanceMeters: Double = 6.0,
) {
    fun clamp(command: VirtualStickCommand, readings: List<ObstacleReading>): VirtualStickCommand {
        val byDirection = readings.associateBy { it.direction }

        val forwardScale = travelScale(command.pitchMetersPerSecond > 0, byDirection[ObstacleDirection.FORWARD])
        val backwardScale = travelScale(command.pitchMetersPerSecond < 0, byDirection[ObstacleDirection.BACKWARD])
        val pitchScale = if (command.pitchMetersPerSecond > 0) forwardScale else backwardScale

        val rightScale = travelScale(command.rollMetersPerSecond > 0, byDirection[ObstacleDirection.RIGHT])
        val leftScale = travelScale(command.rollMetersPerSecond < 0, byDirection[ObstacleDirection.LEFT])
        val rollScale = if (command.rollMetersPerSecond > 0) rightScale else leftScale

        val upScale = travelScale(command.verticalMetersPerSecond > 0, byDirection[ObstacleDirection.UPWARD])
        val downScale = travelScale(command.verticalMetersPerSecond < 0, byDirection[ObstacleDirection.DOWNWARD])
        val verticalScale = if (command.verticalMetersPerSecond > 0) upScale else downScale

        return command.copy(
            pitchMetersPerSecond = command.pitchMetersPerSecond * pitchScale,
            rollMetersPerSecond = command.rollMetersPerSecond * rollScale,
            verticalMetersPerSecond = command.verticalMetersPerSecond * verticalScale,
        )
    }

    /**
     * Returns a 0..1 multiplier for a single axis of travel toward [reading]:
     *  - no reading at all (sensor blind spot / no data) -> treated as an obstacle, not
     *    as "clear," since an absent reading is not evidence of safety on a system that's
     *    standing in for a disabled factory safety net.
     *  - inside braking distance -> 0 (full stop on that axis).
     *  - inside warning distance -> linear ramp between 0 and 1.
     *  - beyond warning distance -> 1 (no clamp).
     * Only applies when [isMovingTowardObstacle] is true — moving away from a close
     * obstacle should not be clamped.
     */
    private fun travelScale(isMovingTowardObstacle: Boolean, reading: ObstacleReading?): Double {
        if (!isMovingTowardObstacle) return 1.0
        val distance = reading?.distanceMeters ?: return 0.0
        return when {
            distance <= brakingDistanceMeters -> 0.0
            distance >= warningDistanceMeters -> 1.0
            else -> (distance - brakingDistanceMeters) / (warningDistanceMeters - brakingDistanceMeters)
        }
    }

    /** True if the clamp has zeroed every axis the command was trying to move on — the
     *  state machine treats this as "can't safely proceed in any direction." */
    fun isFullyBlocked(original: VirtualStickCommand, clamped: VirtualStickCommand): Boolean {
        val triedToMove = original.pitchMetersPerSecond != 0.0 ||
            original.rollMetersPerSecond != 0.0 ||
            original.verticalMetersPerSecond != 0.0
        val couldMove = clamped.pitchMetersPerSecond != 0.0 ||
            clamped.rollMetersPerSecond != 0.0 ||
            clamped.verticalMetersPerSecond != 0.0
        return triedToMove && !couldMove
    }
}

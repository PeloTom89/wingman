package com.pelotom89.wingman.flightcontrol

import com.pelotom89.wingman.sdk.ObstacleSnapshot
import com.pelotom89.wingman.sdk.VirtualStickCommand
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Custom replacement for DJI's APAS obstacle avoidance, which is confirmed DISABLED
 * whenever VirtualStick control is active on the Mini 4 Pro (unlike the M300/M350/M30/
 * Mavic 3E/3M lineup, which keep APAS active under VirtualStick — see the plan's
 * "Hardware tradeoff" decision: staying on the Mini 4 Pro means this file, not a firmware
 * feature, is what stands between the aircraft and a collision).
 *
 * This is the most safety-critical pure-logic file in the app. Kept as a pure function of
 * (proposed command, latest obstacle snapshot) -> clamped command specifically so it's
 * exhaustively unit-testable per the plan's testing note, with zero SDK/threading
 * dependencies to get in the way of that.
 *
 * Applied LAST, after [SafetyLimits.clampSpeed], in every flight state — VisualTrack and
 * GpsGuided both route their proposed command through here before it reaches
 * VirtualStickController.
 *
 * Reworked against the REAL MSDK V5 perception API (see sdk/PerceptionRepository.kt's
 * header comment): there are no discrete forward/backward/left/right readings, only a
 * ring of horizontal distance samples around the aircraft plus separate up/down values.
 * So instead of clamping pitch and roll independently, this computes the single bearing
 * the aircraft is actually travelling toward (from the combined pitch+roll vector) and
 * samples the ring at that bearing — physically correct, since a diagonal command flies
 * toward one point, not two independent axes.
 */
class ObstacleSafetyClamp(
    private val brakingDistanceMeters: Double = 3.0,
    private val warningDistanceMeters: Double = 6.0,
) {
    fun clamp(command: VirtualStickCommand, snapshot: ObstacleSnapshot): VirtualStickCommand {
        val horizontalSpeed = hypot(command.pitchMetersPerSecond, command.rollMetersPerSecond)
        val horizontalScale = if (horizontalSpeed > 0.0) {
            // atan2(roll, pitch): pitch is forward(+)/back(-), roll is right(+)/left(-),
            // matching the ring's assumed 0=nose/90=right/clockwise convention.
            val travelBearingDegrees = Math.toDegrees(atan2(command.rollMetersPerSecond, command.pitchMetersPerSecond))
            travelScale(snapshot.horizontalDistanceAtBearing(travelBearingDegrees))
        } else {
            1.0
        }

        val verticalScale = when {
            command.verticalMetersPerSecond > 0 -> travelScale(snapshot.upwardDistanceMeters)
            command.verticalMetersPerSecond < 0 -> travelScale(snapshot.downwardDistanceMeters)
            else -> 1.0
        }

        return command.copy(
            pitchMetersPerSecond = command.pitchMetersPerSecond * horizontalScale,
            rollMetersPerSecond = command.rollMetersPerSecond * horizontalScale,
            verticalMetersPerSecond = command.verticalMetersPerSecond * verticalScale,
        )
    }

    /**
     * Returns a 0..1 multiplier for travel toward an obstacle at [distanceMeters]:
     *  - null (no reading, e.g. sensor blind spot or no data yet) -> treated as an
     *    obstacle, not as "clear," since an absent reading is not evidence of safety on a
     *    system that's standing in for a disabled factory safety net.
     *  - inside braking distance -> 0 (full stop on that axis).
     *  - inside warning distance -> linear ramp between 0 and 1.
     *  - beyond warning distance -> 1 (no clamp).
     */
    private fun travelScale(distanceMeters: Double?): Double {
        val distance = distanceMeters ?: return 0.0
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

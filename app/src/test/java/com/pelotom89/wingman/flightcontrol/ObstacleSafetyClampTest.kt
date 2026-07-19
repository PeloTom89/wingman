package com.pelotom89.wingman.flightcontrol

import com.pelotom89.wingman.sdk.ObstacleSnapshot
import com.pelotom89.wingman.sdk.VirtualStickCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * This is the pure-logic replacement for DJI's disabled APAS (see the class's own header
 * comment) — these tests are the closest thing this app has to a safety proof, so they're
 * deliberately exhaustive across the braking/warning/clear zones rather than spot checks.
 *
 * Ring convention under test: index 0 = bearing 0 (nose/forward), increasing clockwise —
 * see PerceptionRepository's header comment for why this is flagged as unverified against
 * real hardware.
 */
class ObstacleSafetyClampTest {

    private val clamp = ObstacleSafetyClamp(brakingDistanceMeters = 3.0, warningDistanceMeters = 6.0)

    private fun ringSnapshot(vararg distancesMeters: Double, intervalDegrees: Double = 90.0): ObstacleSnapshot =
        ObstacleSnapshot(
            horizontalDistancesMeters = distancesMeters.toList(),
            horizontalAngleIntervalDegrees = intervalDegrees,
            upwardDistanceMeters = 100.0,
            downwardDistanceMeters = 100.0,
        )

    @Test
    fun `forward motion fully stopped inside braking distance`() {
        val command = VirtualStickCommand(pitchMetersPerSecond = 2.0, 0.0, 0.0, 0.0)
        // index 0 = forward/nose
        val snapshot = ringSnapshot(1.5, 100.0, 100.0, 100.0)

        val result = clamp.clamp(command, snapshot)

        assertEquals(0.0, result.pitchMetersPerSecond, 0.0001)
    }

    @Test
    fun `forward motion unaffected beyond warning distance`() {
        val command = VirtualStickCommand(pitchMetersPerSecond = 2.0, 0.0, 0.0, 0.0)
        val snapshot = ringSnapshot(10.0, 100.0, 100.0, 100.0)

        val result = clamp.clamp(command, snapshot)

        assertEquals(2.0, result.pitchMetersPerSecond, 0.0001)
    }

    @Test
    fun `forward motion linearly ramped inside warning distance`() {
        // Halfway between braking (3m) and warning (6m) distance should scale ~0.5.
        val command = VirtualStickCommand(pitchMetersPerSecond = 2.0, 0.0, 0.0, 0.0)
        val snapshot = ringSnapshot(4.5, 100.0, 100.0, 100.0)

        val result = clamp.clamp(command, snapshot)

        assertEquals(1.0, result.pitchMetersPerSecond, 0.05)
    }

    @Test
    fun `missing ring data is treated as an obstacle, not as clear`() {
        val command = VirtualStickCommand(pitchMetersPerSecond = 2.0, 0.0, 0.0, 0.0)

        val result = clamp.clamp(command, ObstacleSnapshot.EMPTY)

        assertEquals(0.0, result.pitchMetersPerSecond, 0.0001)
    }

    @Test
    fun `rightward motion samples the ring at 90 degrees`() {
        val command = VirtualStickCommand(pitchMetersPerSecond = 0.0, rollMetersPerSecond = 2.0, 0.0, 0.0)
        // index 1 (90 degrees, interval 90) is close; everything else clear
        val snapshot = ringSnapshot(100.0, 1.5, 100.0, 100.0)

        val result = clamp.clamp(command, snapshot)

        assertEquals(0.0, result.rollMetersPerSecond, 0.0001)
    }

    @Test
    fun `pitch and roll are scaled together since they represent one direction of travel`() {
        // A pure-forward reading being close should proportionally reduce a diagonal
        // command that includes forward motion, not leave roll untouched.
        val command = VirtualStickCommand(pitchMetersPerSecond = 2.0, rollMetersPerSecond = 2.0, 0.0, 0.0)
        // Diagonal (45 degrees) bearing with a 90-degree interval ring rounds to the
        // nearest sample (index 1 / 90 degrees here) per the clamp's rounding behavior.
        val snapshot = ringSnapshot(100.0, 1.5, 100.0, 100.0)

        val result = clamp.clamp(command, snapshot)

        assertEquals(result.pitchMetersPerSecond, result.rollMetersPerSecond, 0.0001)
        assertTrue("expected both axes scaled down together", result.pitchMetersPerSecond < 2.0)
    }

    @Test
    fun `vertical axes are independent of horizontal ring data`() {
        val command = VirtualStickCommand(0.0, 0.0, 0.0, verticalMetersPerSecond = 1.0)
        val snapshot = ObstacleSnapshot(
            horizontalDistancesMeters = listOf(1.0, 1.0, 1.0, 1.0), // all close, irrelevant here
            horizontalAngleIntervalDegrees = 90.0,
            upwardDistanceMeters = 100.0,
            downwardDistanceMeters = 100.0,
        )

        val result = clamp.clamp(command, snapshot)

        assertEquals(1.0, result.verticalMetersPerSecond, 0.0001)
    }

    @Test
    fun `isFullyBlocked true only when every attempted axis was zeroed`() {
        val original = VirtualStickCommand(2.0, 0.0, 0.0, 0.0)
        val fullyClamped = VirtualStickCommand(0.0, 0.0, 0.0, 0.0)
        val partiallyClamped = VirtualStickCommand(0.0, 1.0, 0.0, 0.0)

        assertTrue(clamp.isFullyBlocked(original, fullyClamped))
        assertTrue(!clamp.isFullyBlocked(original, partiallyClamped))
    }

    @Test
    fun `isFullyBlocked false when the original command was already zero`() {
        val original = VirtualStickCommand.ZERO
        assertTrue(!clamp.isFullyBlocked(original, VirtualStickCommand.ZERO))
    }
}

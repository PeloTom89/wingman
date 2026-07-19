package com.pelotom89.wingman.flightcontrol

import com.pelotom89.wingman.sdk.ObstacleDirection
import com.pelotom89.wingman.sdk.ObstacleReading
import com.pelotom89.wingman.sdk.VirtualStickCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * This is the pure-logic replacement for DJI's disabled APAS (see the class's own header
 * comment) — these tests are the closest thing this app has to a safety proof, so they're
 * deliberately exhaustive across the braking/warning/clear zones rather than spot checks.
 */
class ObstacleSafetyClampTest {

    private val clamp = ObstacleSafetyClamp(brakingDistanceMeters = 3.0, warningDistanceMeters = 6.0)

    @Test
    fun `forward motion fully stopped inside braking distance`() {
        val command = VirtualStickCommand(pitchMetersPerSecond = 2.0, 0.0, 0.0, 0.0)
        val readings = listOf(ObstacleReading(ObstacleDirection.FORWARD, distanceMeters = 1.5))

        val result = clamp.clamp(command, readings)

        assertEquals(0.0, result.pitchMetersPerSecond, 0.0001)
    }

    @Test
    fun `forward motion unaffected beyond warning distance`() {
        val command = VirtualStickCommand(pitchMetersPerSecond = 2.0, 0.0, 0.0, 0.0)
        val readings = listOf(ObstacleReading(ObstacleDirection.FORWARD, distanceMeters = 10.0))

        val result = clamp.clamp(command, readings)

        assertEquals(2.0, result.pitchMetersPerSecond, 0.0001)
    }

    @Test
    fun `forward motion linearly ramped inside warning distance`() {
        // Halfway between braking (3m) and warning (6m) distance should scale ~0.5.
        val command = VirtualStickCommand(pitchMetersPerSecond = 2.0, 0.0, 0.0, 0.0)
        val readings = listOf(ObstacleReading(ObstacleDirection.FORWARD, distanceMeters = 4.5))

        val result = clamp.clamp(command, readings)

        assertEquals(1.0, result.pitchMetersPerSecond, 0.05)
    }

    @Test
    fun `missing reading for the direction of travel is treated as an obstacle, not as clear`() {
        val command = VirtualStickCommand(pitchMetersPerSecond = 2.0, 0.0, 0.0, 0.0)

        val result = clamp.clamp(command, emptyList())

        assertEquals(0.0, result.pitchMetersPerSecond, 0.0001)
    }

    @Test
    fun `moving away from a close obstacle is not clamped`() {
        // Command moves backward (negative pitch); a close FORWARD reading is irrelevant.
        val command = VirtualStickCommand(pitchMetersPerSecond = -2.0, 0.0, 0.0, 0.0)
        val readings = listOf(ObstacleReading(ObstacleDirection.FORWARD, distanceMeters = 1.0))

        val result = clamp.clamp(command, readings)

        assertEquals(-2.0, result.pitchMetersPerSecond, 0.0001)
    }

    @Test
    fun `each axis is clamped independently`() {
        val command = VirtualStickCommand(
            pitchMetersPerSecond = 2.0,
            rollMetersPerSecond = 2.0,
            yawDegreesPerSecond = 0.0,
            verticalMetersPerSecond = 1.0,
        )
        val readings = listOf(
            ObstacleReading(ObstacleDirection.FORWARD, distanceMeters = 1.0), // blocks pitch
            ObstacleReading(ObstacleDirection.RIGHT, distanceMeters = 10.0), // clear
            ObstacleReading(ObstacleDirection.UPWARD, distanceMeters = 10.0), // clear
        )

        val result = clamp.clamp(command, readings)

        assertEquals(0.0, result.pitchMetersPerSecond, 0.0001)
        assertEquals(2.0, result.rollMetersPerSecond, 0.0001)
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

package com.pelotom89.wingman.flightcontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlightCommandCalculatorTest {

    private val calculator = FlightCommandCalculator(
        targetDistanceMeters = 10.0,
        distanceToleranceMeters = 2.0,
        distanceGainMetersPerSecondPerMeter = 0.3,
        maxApproachSpeedMetersPerSecond = 3.0,
    )

    // Aircraft south of the subject; bearing to subject is due north (0 degrees).
    private val aircraft = LatLon(37.0, -122.0)
    private val subjectNorth = LatLon(37.0002, -122.0) // ~22m north, comfortably past target distance

    @Test
    fun `approaches when farther than target distance and already aligned`() {
        val command = calculator.computeFollowCommand(
            aircraft = aircraft,
            aircraftHeadingDegrees = 0.0, // already facing the subject
            subject = subjectNorth,
        )

        assertTrue("expected forward approach speed, got ${command.pitchMetersPerSecond}", command.pitchMetersPerSecond > 0.0)
        assertEquals(0.0, command.yawDegreesPerSecond, 0.001)
    }

    @Test
    fun `backs off when closer than target distance and already aligned`() {
        val closeSubject = LatLon(37.00004, -122.0) // ~4.4m north, inside the standoff distance

        val command = calculator.computeFollowCommand(
            aircraft = aircraft,
            aircraftHeadingDegrees = 0.0,
            subject = closeSubject,
        )

        assertTrue("expected backing-off (negative) speed, got ${command.pitchMetersPerSecond}", command.pitchMetersPerSecond < 0.0)
    }

    @Test
    fun `holds still within the distance tolerance band`() {
        val withinTolerance = LatLon(37.000081, -122.0) // ~9m north, inside 10m +/- 2m band

        val command = calculator.computeFollowCommand(
            aircraft = aircraft,
            aircraftHeadingDegrees = 0.0,
            subject = withinTolerance,
        )

        assertEquals(0.0, command.pitchMetersPerSecond, 0.001)
    }

    @Test
    fun `approach speed is capped at the configured maximum`() {
        val veryFar = LatLon(37.01, -122.0) // over 1km north, would blow past the raw proportional speed

        val command = calculator.computeFollowCommand(
            aircraft = aircraft,
            aircraftHeadingDegrees = 0.0,
            subject = veryFar,
        )

        assertEquals(3.0, command.pitchMetersPerSecond, 0.001)
    }

    @Test
    fun `does not commit to forward speed while still turning to face the subject`() {
        val command = calculator.computeFollowCommand(
            aircraft = aircraft,
            aircraftHeadingDegrees = 0.0, // facing north
            subject = LatLon(37.0, -121.99), // subject is due east, ~89m away
        )

        assertEquals(0.0, command.pitchMetersPerSecond, 0.001)
        assertTrue("expected a positive (rightward) yaw rate, got ${command.yawDegreesPerSecond}", command.yawDegreesPerSecond > 0.0)
    }

    @Test
    fun `yaws toward the subject proportionally to heading error`() {
        val command = calculator.computeFollowCommand(
            aircraft = aircraft,
            aircraftHeadingDegrees = 0.0,
            subject = LatLon(37.0, -121.99), // due east: ~90 degree bearing
        )

        assertEquals(90.0 * 1.5, command.yawDegreesPerSecond, 2.0)
    }

    @Test
    fun `gimbal pitches level when the subject is directly beneath the aircraft altitude-wise`() {
        // atan2(0, distance) is 0 regardless of horizontal distance -- an aircraft at the
        // subject's own altitude has nowhere to pitch down to.
        val pitch = computeGimbalPitchDegrees(altitudeAglMeters = 0.0, horizontalDistanceMeters = 20.0)

        assertEquals(0.0, pitch, 0.001)
    }

    @Test
    fun `gimbal pitches straight down when the subject is directly below`() {
        val pitch = computeGimbalPitchDegrees(altitudeAglMeters = 15.0, horizontalDistanceMeters = 0.0)

        assertEquals(-90.0, pitch, 0.1)
    }

    @Test
    fun `gimbal pitches roughly 45 degrees down when altitude equals horizontal distance`() {
        val pitch = computeGimbalPitchDegrees(altitudeAglMeters = 10.0, horizontalDistanceMeters = 10.0)

        assertEquals(-45.0, pitch, 0.1)
    }

    @Test
    fun `gimbal pitch is always clamped between level and straight down`() {
        val nearlyOverhead = computeGimbalPitchDegrees(altitudeAglMeters = 50.0, horizontalDistanceMeters = 0.001)
        val nearlyLevel = computeGimbalPitchDegrees(altitudeAglMeters = 0.001, horizontalDistanceMeters = 50.0)

        assertTrue(nearlyOverhead in -90.0..0.0)
        assertTrue(nearlyLevel in -90.0..0.0)
    }
}

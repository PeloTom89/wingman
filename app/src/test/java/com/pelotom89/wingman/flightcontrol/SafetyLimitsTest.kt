package com.pelotom89.wingman.flightcontrol

import com.pelotom89.wingman.sdk.VirtualStickCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyLimitsTest {

    private val limits = SafetyLimits(
        maxHorizontalSpeedMetersPerSecond = 3.0,
        maxVerticalSpeedMetersPerSecond = 1.5,
        maxAltitudeMetersAgl = 8.0,
        geofenceRadiusMeters = 100.0,
        batteryRthTriggerPercent = 30,
        batteryCriticalPercent = 15,
    )

    @Test
    fun `horizontal speed over the cap is scaled down preserving direction`() {
        val command = VirtualStickCommand(pitchMetersPerSecond = 6.0, rollMetersPerSecond = 0.0, 0.0, 0.0)

        val clamped = limits.clampSpeed(command)

        assertEquals(3.0, clamped.pitchMetersPerSecond, 0.0001)
    }

    @Test
    fun `horizontal speed under the cap is left untouched`() {
        val command = VirtualStickCommand(pitchMetersPerSecond = 1.0, rollMetersPerSecond = 1.0, 0.0, 0.0)

        val clamped = limits.clampSpeed(command)

        assertEquals(1.0, clamped.pitchMetersPerSecond, 0.0001)
        assertEquals(1.0, clamped.rollMetersPerSecond, 0.0001)
    }

    @Test
    fun `vertical speed is coerced into range independent of horizontal`() {
        val command = VirtualStickCommand(0.0, 0.0, 0.0, verticalMetersPerSecond = 5.0)

        val clamped = limits.clampSpeed(command)

        assertEquals(1.5, clamped.verticalMetersPerSecond, 0.0001)
    }

    @Test
    fun `altitude ceiling check`() {
        assertTrue(limits.isAltitudeExceeded(9.0))
        assertFalse(limits.isAltitudeExceeded(7.0))
    }

    @Test
    fun `geofence breach uses real-world distance, not raw coordinate delta`() {
        val launch = LatLon(37.7749, -122.4194) // San Francisco
        val nearby = LatLon(37.7750, -122.4194) // ~11m north
        val far = LatLon(37.7849, -122.4194) // ~1.1km north

        assertFalse(limits.isGeofenceBreached(launch, nearby))
        assertTrue(limits.isGeofenceBreached(launch, far))
    }

    @Test
    fun `battery status thresholds`() {
        assertEquals(BatteryStatus.OK, limits.batteryStatus(50))
        assertEquals(BatteryStatus.RTH_TRIGGER, limits.batteryStatus(30))
        assertEquals(BatteryStatus.CRITICAL, limits.batteryStatus(15))
        assertEquals(BatteryStatus.CRITICAL, limits.batteryStatus(5))
    }

    @Test
    fun `haversine distance matches a known reference value within tolerance`() {
        // San Francisco to Oakland, roughly 13km apart.
        val sf = LatLon(37.7749, -122.4194)
        val oakland = LatLon(37.8044, -122.2712)

        val distance = haversineMeters(sf, oakland)

        assertTrue("expected ~13km, got ${distance}m", distance in 12_000.0..14_000.0)
    }

    @Test
    fun `bearing points due north when target is directly north`() {
        val origin = LatLon(37.0, -122.0)
        val north = LatLon(37.01, -122.0)

        val bearing = bearingDegrees(origin, north)

        assertEquals(0.0, bearing, 1.0)
    }

    @Test
    fun `bearing points due east when target is directly east`() {
        val origin = LatLon(37.0, -122.0)
        val east = LatLon(37.0, -121.99)

        val bearing = bearingDegrees(origin, east)

        assertEquals(90.0, bearing, 1.0)
    }
}

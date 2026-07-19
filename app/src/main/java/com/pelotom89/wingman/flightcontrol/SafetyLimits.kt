package com.pelotom89.wingman.flightcontrol

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Hard ceilings applied on top of [ObstacleSafetyClamp] — that clamp handles "don't hit
 * something nearby," this handles "don't do something dumb regardless of obstacles"
 * (fly too high, too fast, too far from launch, or on too little battery). Both gates run
 * on every command tick; see the plan's Milestone 3 note to start these very conservative
 * (~2-3 m/s, 5-8m altitude) and loosen only after real-world validation.
 *
 * Deliberately pure/stateless aside from the launch point and config, so it's unit-testable
 * per the plan's testing note.
 */
data class SafetyLimits(
    val maxHorizontalSpeedMetersPerSecond: Double = 3.0,
    val maxVerticalSpeedMetersPerSecond: Double = 1.5,
    val maxAltitudeMetersAgl: Double = 8.0,
    val geofenceRadiusMeters: Double = 100.0,
    val batteryRthTriggerPercent: Int = 30,
    val batteryCriticalPercent: Int = 15,
) {
    fun clampSpeed(command: com.pelotom89.wingman.sdk.VirtualStickCommand): com.pelotom89.wingman.sdk.VirtualStickCommand {
        val horizontalScale = maxOf(
            1.0,
            hypot(command.pitchMetersPerSecond, command.rollMetersPerSecond) / maxHorizontalSpeedMetersPerSecond,
        )
        return command.copy(
            pitchMetersPerSecond = command.pitchMetersPerSecond / horizontalScale,
            rollMetersPerSecond = command.rollMetersPerSecond / horizontalScale,
            verticalMetersPerSecond = command.verticalMetersPerSecond
                .coerceIn(-maxVerticalSpeedMetersPerSecond, maxVerticalSpeedMetersPerSecond),
        )
    }

    fun isAltitudeExceeded(altitudeMetersAgl: Double): Boolean = altitudeMetersAgl > maxAltitudeMetersAgl

    fun isGeofenceBreached(launchPoint: LatLon, currentPoint: LatLon): Boolean =
        haversineMeters(launchPoint, currentPoint) > geofenceRadiusMeters

    fun batteryStatus(batteryPercent: Int): BatteryStatus = when {
        batteryPercent <= batteryCriticalPercent -> BatteryStatus.CRITICAL
        batteryPercent <= batteryRthTriggerPercent -> BatteryStatus.RTH_TRIGGER
        else -> BatteryStatus.OK
    }

    private fun hypot(a: Double, b: Double): Double = sqrt(a.pow(2) + b.pow(2))
}

enum class BatteryStatus { OK, RTH_TRIGGER, CRITICAL }

data class LatLon(val latitude: Double, val longitude: Double)

/** Great-circle distance — used both for the geofence check and for Following's
 *  bearing/distance-to-subject computation in FlightCommandCalculator. */
fun haversineMeters(a: LatLon, b: LatLon): Double {
    val earthRadiusMeters = 6_371_000.0
    val dLat = Math.toRadians(b.latitude - a.latitude)
    val dLon = Math.toRadians(b.longitude - a.longitude)
    val lat1 = Math.toRadians(a.latitude)
    val lat2 = Math.toRadians(b.latitude)
    val h = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
    return 2 * earthRadiusMeters * atan2(sqrt(h), sqrt(1 - h))
}

/** Initial bearing from a to b, in degrees, 0=north/360, clockwise — used to compute the
 *  yaw-to-face-subject command while Following. */
fun bearingDegrees(a: LatLon, b: LatLon): Double {
    val lat1 = Math.toRadians(a.latitude)
    val lat2 = Math.toRadians(b.latitude)
    val dLon = Math.toRadians(b.longitude - a.longitude)
    val y = sin(dLon) * cos(lat2)
    val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
    val bearing = Math.toDegrees(atan2(y, x))
    return (bearing + 360) % 360
}

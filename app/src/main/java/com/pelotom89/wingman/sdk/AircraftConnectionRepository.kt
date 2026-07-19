package com.pelotom89.wingman.sdk

import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.common.LocationCoordinate2D
import dji.v5.manager.KeyManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Single source of truth for "where is the aircraft and is it safe to command." Every
 * other layer (flight control, UI) should read telemetry through here rather than
 * touching KeyManager directly, so there's one place that defines what "connected" means.
 *
 * NOTE: MSDK V5's key-based telemetry API (KeyManager/FlightControllerKey) shape is
 * per DJI's documented pattern; verify exact key names against the pinned SDK version's
 * API reference at first build.
 */
class AircraftConnectionRepository {

    val locationFlow: Flow<LocationCoordinate2D> = keyFlow(FlightControllerKey.KeyAircraftLocation)
    val altitudeFlow: Flow<Double> = keyFlow(FlightControllerKey.KeyAltitude)
    val velocityFlow: Flow<Triple<Double, Double, Double>> = keyFlow(FlightControllerKey.KeyAircraftVelocity)
        .distinctUntilChanged()
    val compassHeadingFlow: Flow<Double> = keyFlow(FlightControllerKey.KeyCompassHeading)
    val batteryPercentFlow: Flow<Int> = keyFlow(FlightControllerKey.KeyChargeRemainingInPercent)
    val isFlyingFlow: Flow<Boolean> = keyFlow(FlightControllerKey.KeyIsFlying)
    val gpsSignalLevelFlow: Flow<Int> = keyFlow(FlightControllerKey.KeyGPSSignalLevel)

    /** Combined snapshot the flight state machine actually consumes each tick. */
    val telemetryFlow: Flow<AircraftTelemetry> = combine(
        locationFlow, altitudeFlow, velocityFlow, compassHeadingFlow, batteryPercentFlow, isFlyingFlow,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val location = values[0] as LocationCoordinate2D
        val altitude = values[1] as Double
        val velocity = values[2] as Triple<Double, Double, Double>
        val heading = values[3] as Double
        val batteryPercent = values[4] as Int
        val isFlying = values[5] as Boolean
        AircraftTelemetry(
            latitude = location.latitude,
            longitude = location.longitude,
            altitudeMeters = altitude,
            velocityMetersPerSecond = velocity,
            headingDegrees = heading,
            batteryPercent = batteryPercent,
            isFlying = isFlying,
        )
    }

    private fun <T> keyFlow(key: dji.sdk.keyvalue.key.DJIKey<T>) = callbackFlow {
        val djiKey = KeyTools.createKey(key)
        val listener = dji.v5.manager.KeyManager.KeyListener<T> { _, newValue ->
            newValue?.let { trySend(it) }
        }
        KeyManager.getInstance().listen(djiKey, this@AircraftConnectionRepository, listener)
        KeyManager.getInstance().getValue(djiKey) { value -> value?.let { trySend(it) } }
        awaitClose { KeyManager.getInstance().cancelListen(djiKey, this@AircraftConnectionRepository) }
    }
}

data class AircraftTelemetry(
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double,
    val velocityMetersPerSecond: Triple<Double, Double, Double>,
    val headingDegrees: Double,
    val batteryPercent: Int,
    val isFlying: Boolean,
)

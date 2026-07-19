package com.pelotom89.wingman.sdk

import dji.sdk.keyvalue.key.BatteryKey
import dji.sdk.keyvalue.key.DJIKeyInfo
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.common.LocationCoordinate2D
import dji.sdk.keyvalue.value.common.Velocity3D
import dji.v5.common.callback.CommonCallbacks
import dji.v5.manager.KeyManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine

/**
 * Single source of truth for "where is the aircraft and is it safe to command." Every
 * other layer (flight control, UI) should read telemetry through here rather than
 * touching KeyManager directly, so there's one place that defines what "connected" means.
 *
 * CORRECTED against the real MSDK V5 jar (dji-sdk-v5-aircraft-provided:5.18.0): MSDK V5's
 * key-value telemetry is `KeyManager.getInstance().listen(DJIKey<R>, Object identifier,
 * CommonCallbacks.KeyListener<R>)`, where `DJIKey<R>` is produced from a `DJIKeyInfo<R>`
 * static field (e.g. `FlightControllerKey.KeyAircraftLocation`) via `KeyTools.createKey`.
 * `getValue` is synchronous, not callback-based. Battery percent lives on a separate
 * `BatteryKey`, not `FlightControllerKey`.
 */
class AircraftConnectionRepository {

    val locationFlow: Flow<LocationCoordinate2D> = keyFlow(FlightControllerKey.KeyAircraftLocation)
    val altitudeFlow: Flow<Double> = keyFlow(FlightControllerKey.KeyAltitude)
    val velocityFlow: Flow<Velocity3D> = keyFlow(FlightControllerKey.KeyAircraftVelocity)
    val compassHeadingFlow: Flow<Double> = keyFlow(FlightControllerKey.KeyCompassHeading)
    val batteryPercentFlow: Flow<Int> = keyFlow(BatteryKey.KeyChargeRemainingInPercent)
    val isFlyingFlow: Flow<Boolean> = keyFlow(FlightControllerKey.KeyIsFlying)

    /** Combined snapshot the flight state machine actually consumes each tick. */
    val telemetryFlow: Flow<AircraftTelemetry> = combine(
        locationFlow, altitudeFlow, velocityFlow, compassHeadingFlow, batteryPercentFlow, isFlyingFlow,
    ) { values ->
        val location = values[0] as LocationCoordinate2D
        val altitude = values[1] as Double
        val velocity = values[2] as Velocity3D
        val heading = values[3] as Double
        val batteryPercent = values[4] as Int
        val isFlying = values[5] as Boolean
        AircraftTelemetry(
            latitude = location.latitude ?: 0.0,
            longitude = location.longitude ?: 0.0,
            altitudeMeters = altitude,
            velocityXMetersPerSecond = velocity.x ?: 0.0,
            velocityYMetersPerSecond = velocity.y ?: 0.0,
            velocityZMetersPerSecond = velocity.z ?: 0.0,
            headingDegrees = heading,
            batteryPercent = batteryPercent,
            isFlying = isFlying,
        )
    }

    private fun <T> keyFlow(keyInfo: DJIKeyInfo<T>) = callbackFlow {
        val djiKey = KeyTools.createKey(keyInfo)
        val listener = CommonCallbacks.KeyListener<T> { _, newValue ->
            newValue?.let { trySend(it) }
        }
        KeyManager.getInstance().listen(djiKey, this@AircraftConnectionRepository, listener)
        KeyManager.getInstance().getValue(djiKey)?.let { trySend(it) }
        awaitClose { KeyManager.getInstance().cancelListen(djiKey, this@AircraftConnectionRepository) }
    }
}

data class AircraftTelemetry(
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double,
    val velocityXMetersPerSecond: Double,
    val velocityYMetersPerSecond: Double,
    val velocityZMetersPerSecond: Double,
    val headingDegrees: Double,
    val batteryPercent: Int,
    val isFlying: Boolean,
)

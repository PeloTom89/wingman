package com.pelotom89.wingman.sdk

import android.util.Log
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

private const val TAG = "WingmanTelemetry"

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
 *
 * VERIFIED on real hardware (2026-07-19): all `FlightControllerKey`-family values (location,
 * altitude, velocity, heading, isFlying) stay null/uninitialized until the flight controller
 * ITSELF reports connected (`FlightControllerKey.KeyConnection`) -- which is a separate,
 * later event from `SDKManager`'s own `onProductConnect` (that only means the RC-N3 is
 * linked to the phone over USB, not that the RC-N3 has an active link to the aircraft, or
 * that some app has actually claimed that link). In one on-device repro, the FC stayed
 * disconnected (aircraft LEDs flashing red) until the operator manually granted DJI Fly's
 * own "allow to connect" USB-accessory-style prompt, at which point the FC came up (LEDs
 * flashing green) and stayed up when control switched back to this app. If FlightController
 * telemetry stays null with a genuinely GPS-visible aircraft, check `KeyConnection` before
 * assuming a code bug -- it may mean no app has an active claim on the aircraft link yet.
 */
class AircraftConnectionRepository {

    val locationFlow: Flow<LocationCoordinate2D> = keyFlow(FlightControllerKey.KeyAircraftLocation)
    val altitudeFlow: Flow<Double> = keyFlow(FlightControllerKey.KeyAltitude)
    val velocityFlow: Flow<Velocity3D> = keyFlow(FlightControllerKey.KeyAircraftVelocity)
    val compassHeadingFlow: Flow<Double> = keyFlow(FlightControllerKey.KeyCompassHeading)
    val batteryPercentFlow: Flow<Int> = keyFlow(BatteryKey.KeyChargeRemainingInPercent)
    val isFlyingFlow: Flow<Boolean> = keyFlow(FlightControllerKey.KeyIsFlying)

    /** The flight controller's OWN connection state -- distinct from, and a stricter check
     *  than, [dji.v5.manager.SDKManager]'s `onProductConnect` (see this class's header
     *  comment). This is what preflight UI should gate "aircraft connected" on; a
     *  ProductConnected SDK registration state can be true while this is false (observed
     *  on-device: RC-N3 linked to the phone over USB, but no app holding the aircraft link,
     *  aircraft LEDs flashing red / error, zero FlightController telemetry flowing). */
    val flightControllerConnectedFlow: Flow<Boolean> = keyFlow(FlightControllerKey.KeyConnection)

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
            Log.i(TAG, "${keyInfo.identifier} listener update: $newValue")
            newValue?.let { trySend(it) }
        }
        KeyManager.getInstance().listen(djiKey, this@AircraftConnectionRepository, listener)
        val initial = KeyManager.getInstance().getValue(djiKey)
        Log.i(TAG, "${keyInfo.identifier} initial getValue: $initial")
        initial?.let { trySend(it) }
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

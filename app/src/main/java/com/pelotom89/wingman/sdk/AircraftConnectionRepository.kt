package com.pelotom89.wingman.sdk

import dji.sdk.keyvalue.key.BatteryKey
import dji.sdk.keyvalue.key.DJIKeyInfo
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.key.RemoteControllerKey
import android.util.Log
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Telemetry poll cadence. 5Hz is plenty for GPS-following control and light on the
 *  RC<->aircraft link. */
private const val POLL_INTERVAL_MS = 200L

/**
 * Single source of truth for "where is the aircraft, is it safe to command, and is the
 * flight controller actually connected."
 *
 * REWRITTEN to POLL `getValue` instead of subscribing with `listen()` (2026-07-23, on hard
 * real-hardware evidence — DJI Mini 4 Pro + RC-N3): with the aircraft cleanly connected
 * (LEDs solid green, freshly-charged battery) the async
 * `KeyManager.getValue(DJIKey, CompletionCallbackWithParam)` returns real values for every
 * FlightController key — `KeyConnection` -> true, altitude/heading/isFlying/battery all
 * correct — while `KeyManager.listen()` on the exact same keys delivered ZERO callbacks.
 * The old listen()-based approach is why the app showed "not connected" even when
 * `KeyConnection` was literally true. Telemetry and connection state are now built from
 * periodic getValue polls, the mechanism proven to work on this hardware.
 *
 * Two hard-won lessons, both verified on-device and easy to regress:
 *  1. FlightController keys only succeed (rather than returning `REQUEST_HANDLER_NOT_FOUND`)
 *     when the aircraft is healthy and stable. A degraded aircraft (old battery, hours
 *     powered, LEDs cycling yellow/red) fails EVERY FlightController key while Battery/Camera
 *     keep working — that's an aircraft-state problem, not an app bug. Check the aircraft
 *     LEDs / battery before suspecting code.
 *  2. Sending ANY aircraft actuation (VirtualStick, gimbal, camera-stream request) BEFORE
 *     the link is established corrupts the handshake and drives the aircraft to the red
 *     error state — all of those are gated to fire only after flight starts (see
 *     WingmanViewModel / MainActivity). No working competitor app sends anything to the
 *     aircraft during connection.
 */
class AircraftConnectionRepository {

    /** Flight-controller connection state, POLLED via getValue (see class header). This is
     *  the reliable "aircraft connected" signal the preflight UI gates on. */
    val flightControllerConnectedFlow: Flow<Boolean> = flow {
        while (true) {
            emit(getValueAsync(FlightControllerKey.KeyConnection) ?: false)
            delay(POLL_INTERVAL_MS)
        }
    }.distinctUntilChanged()

    /** RC-N3's own key-channel connection state, polled — diagnostic split of which hop is up. */
    val remoteControllerConnectedFlow: Flow<Boolean> = flow {
        while (true) {
            emit(getValueAsync(RemoteControllerKey.KeyConnection) ?: false)
            delay(POLL_INTERVAL_MS)
        }
    }.distinctUntilChanged()

    /**
     * Combined aircraft telemetry the flight state machine consumes each tick, POLLED. Each
     * cycle reads every key via getValue; a key that fails/times out this cycle (e.g.
     * AircraftLocation indoors with no GPS lock, or any key on a momentarily busy link)
     * CARRIES FORWARD the last known-good value for that field rather than collapsing to a
     * hardcoded default. This matters because SafetyLimits reads this data as ground truth —
     * a fabricated 0 read as real telemetry, not "unknown," previously fired a false
     * EmergencyStop("Battery critical (0%)") off a single missed poll on a battery that was
     * nowhere near critical (verified on-device 2026-07-23, mid real flight). Seeded with
     * batteryPercent=100 so a failed FIRST-ever read (before any real value has arrived)
     * can't falsely trigger CRITICAL either; every other field seeds at a neutral 0/false
     * since FlightStateMachine already refuses to command on a stale/zero location fix and
     * nothing else escalates safety state off them at zero.
     */
    val telemetryFlow: Flow<AircraftTelemetry> = flow {
        var last = AircraftTelemetry(
            latitude = 0.0,
            longitude = 0.0,
            altitudeMeters = 0.0,
            velocityXMetersPerSecond = 0.0,
            velocityYMetersPerSecond = 0.0,
            velocityZMetersPerSecond = 0.0,
            headingDegrees = 0.0,
            batteryPercent = 100,
            isFlying = false,
        )
        while (true) {
            val location = getValueAsync(FlightControllerKey.KeyAircraftLocation)
            val altitude = getValueAsync(FlightControllerKey.KeyAltitude)
            val velocity = getValueAsync(FlightControllerKey.KeyAircraftVelocity)
            val heading = getValueAsync(FlightControllerKey.KeyCompassHeading)
            val batteryPercent = getValueAsync(BatteryKey.KeyChargeRemainingInPercent)
            val isFlying = getValueAsync(FlightControllerKey.KeyIsFlying)
            last = AircraftTelemetry(
                latitude = location?.latitude ?: last.latitude,
                longitude = location?.longitude ?: last.longitude,
                altitudeMeters = altitude ?: last.altitudeMeters,
                velocityXMetersPerSecond = velocity?.x ?: last.velocityXMetersPerSecond,
                velocityYMetersPerSecond = velocity?.y ?: last.velocityYMetersPerSecond,
                velocityZMetersPerSecond = velocity?.z ?: last.velocityZMetersPerSecond,
                headingDegrees = heading ?: last.headingDegrees,
                batteryPercent = batteryPercent ?: last.batteryPercent,
                isFlying = isFlying ?: last.isFlying,
            )
            emit(last)
            delay(POLL_INTERVAL_MS)
        }
    }

    /** Async getValue as a suspend fn: the ONLY key-read path that works on this hardware
     *  (listen() delivers nothing — see class header). Wrapped in a timeout because getValue
     *  can HANG (never call back) when the aircraft link is unhealthy — verified on-device;
     *  without this, one hung read freezes the whole poll loop. Returns null on
     *  failure/timeout/hang so polling keeps going. */
    private suspend fun <T> getValueAsync(keyInfo: DJIKeyInfo<T>): T? =
        withTimeoutOrNull(GET_VALUE_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                KeyManager.getInstance().getValue(
                    KeyTools.createKey(keyInfo),
                    object : CommonCallbacks.CompletionCallbackWithParam<T> {
                        override fun onSuccess(value: T?) {
                            logOnChange(keyInfo.identifier, "OK") { Log.i("WingmanPoll", "${keyInfo.identifier} = $value") }
                            if (cont.isActive) cont.resume(value)
                        }

                        override fun onFailure(error: IDJIError) {
                            val code = error.errorCode().toString()
                            logOnChange(keyInfo.identifier, code) { Log.w("WingmanPoll", "${keyInfo.identifier} FAIL code=$code") }
                            if (cont.isActive) cont.resume(null)
                        }
                    },
                )
            }
        }

    /** getValue fires every [POLL_INTERVAL_MS] per key -- logging every call floods logcat's
     *  buffer fast enough to evict one-shot diagnostic lines from elsewhere in the app
     *  (verified on-device: a 36-minute run left zero WingmanCameraStream/WingmanUI lines in
     *  the buffer, all evicted by WingmanPoll spam). Only log when a key's outcome actually
     *  changes, so the buffer still shows connection state transitions without the noise. */
    private val lastLoggedOutcome = java.util.concurrent.ConcurrentHashMap<String, String>()

    private inline fun logOnChange(identifier: String, outcome: String, log: () -> Unit) {
        if (lastLoggedOutcome.put(identifier, outcome) != outcome) log()
    }

    private companion object {
        /** getValue can hang on an unhealthy link; cap each read so the poll loop survives. */
        const val GET_VALUE_TIMEOUT_MS = 1500L
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

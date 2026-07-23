package com.pelotom89.wingman.sdk

import android.util.Log
import dji.sdk.keyvalue.key.BatteryKey
import dji.sdk.keyvalue.key.DJIKeyInfo
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.key.RemoteControllerKey
import dji.sdk.keyvalue.value.common.LocationCoordinate2D
import dji.sdk.keyvalue.value.common.Velocity3D
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
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
 *
 * ON THE INTERMITTENCE (investigated 2026-07-19, full dji-sdk/Mobile-SDK-Android-V5 issue
 * #427 thread + real-jar disassembly + DJI's own sample source): there is NO app-side API
 * that forces the RC->aircraft link up. DJI's official sample does nothing beyond
 * registerApp() (its MSDKManagerVM just logs onProductConnect), exposes no
 * reconnect/refresh action key anywhere under dji.v5.manager.*, and DJI staff state in
 * #427 that the preemption/stall "is caused by the design of the remote control firmware"
 * with force-stopping DJI Fly/Pilot as the only remedy; the issue reporter reproduced the
 * stall with DJI Fly NEVER opened since RC boot, and it remained unfixed as of Dec 2025.
 * MSDK's own USB layer (dji.sdk.datalink.usb.DJIUsbAccessoryReceiver, disassembled from
 * the real 5.18.0 jar) already self-retries the phone<->RC hop on 2-3s timers -- which is
 * why onProductConnect is reliable while the RC<->aircraft hop can stay down. What IS
 * achievable app-side, and done here: [remoteControllerConnectedFlow] to isolate which hop
 * is broken, and [probeFlightControllerLink] as a lightweight read-only request down the
 * FC channel so a stall is at least observable (and, unproven, possibly nudged).
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

    /** The RC-N3's own key-channel connection state (`RemoteControllerKey.KeyConnection`,
     *  verified present on the real 5.18.0 jar's DJIRemoteControllerKey). Splits the two
     *  hops apart diagnostically: true here + false on [flightControllerConnectedFlow]
     *  means the phone<->RC USB/key channel is healthy and the stall is the RC<->aircraft
     *  hop (the issue-#427 firmware state); false here means the USB hop itself is the
     *  problem (replug the cable) despite SDKManager reporting ProductConnected. */
    val remoteControllerConnectedFlow: Flow<Boolean> = keyFlow(RemoteControllerKey.KeyConnection)

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

    /**
     * Active, read-only request down the flight-controller key channel (async
     * `KeyManager.getValue(DJIKey, CompletionCallbackWithParam)` on
     * `FlightControllerKey.KeySerialNumber` -- signature verified against the real
     * 5.18.0 jar; unlike the synchronous cached `getValue(DJIKey)` used in [keyFlow],
     * the callback overload issues a real request toward the device).
     *
     * Called on a bounded timer by WingmanViewModel while the FC link is down after
     * ProductConnect. Honest scope: there is no evidence this un-sticks the RC firmware
     * state (see class header -- DJI provides no such API), but it is harmless, it is
     * exactly the "lightweight FC-touching call" a retry can make, and its onFailure
     * error code/description makes the stall observable in logcat instead of the app
     * silently waiting on a listener that never fires.
     */
    fun probeFlightControllerLink() {
        // TEMP diagnostic (2026-07-22): video streams fine but KeyManager.listen() delivers
        // ZERO telemetry updates. Testing whether async getValue (which works for the serial)
        // can read the telemetry the app actually needs (location/altitude/heading/battery),
        // which would mean the fix is to POLL getValue instead of relying on listen().
        probeGetValue("KeySerialNumber", FlightControllerKey.KeySerialNumber)
        probeGetValue("KeyConnection", FlightControllerKey.KeyConnection)
        probeGetValue("KeyAircraftLocation", FlightControllerKey.KeyAircraftLocation)
        probeGetValue("KeyAltitude", FlightControllerKey.KeyAltitude)
        probeGetValue("KeyCompassHeading", FlightControllerKey.KeyCompassHeading)
        probeGetValue("KeyIsFlying", FlightControllerKey.KeyIsFlying)
        probeGetValue("BatteryChargeRemaining", BatteryKey.KeyChargeRemainingInPercent)
    }

    private fun <T> probeGetValue(label: String, keyInfo: DJIKeyInfo<T>) {
        KeyManager.getInstance().getValue(
            KeyTools.createKey(keyInfo),
            object : CommonCallbacks.CompletionCallbackWithParam<T> {
                override fun onSuccess(value: T?) {
                    Log.i(TAG, "PROBE $label success: $value")
                }

                override fun onFailure(error: IDJIError) {
                    Log.w(TAG, "PROBE $label FAIL: code=${error.errorCode()} inner=${error.innerCode()}")
                }
            },
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

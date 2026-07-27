package com.pelotom89.wingman.sdk

import dji.sdk.keyvalue.key.GimbalKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.common.Attitude
import dji.sdk.keyvalue.value.gimbal.CtrlInfo
import dji.sdk.keyvalue.value.gimbal.GimbalAngleRotation
import dji.sdk.keyvalue.value.gimbal.GimbalAngleRotationMode
import dji.sdk.keyvalue.value.gimbal.GimbalResetType
import dji.sdk.keyvalue.value.gimbal.GimbalSpeedRotation
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Gimbal control. Two distinct paths:
 *
 *  - [setPitchSpeed] / [currentPitchDegrees] — VELOCITY control, for smooth continuous
 *    subject tracking while following. WingmanViewModel runs a P-controller: read the current
 *    pitch, command an angular velocity toward the target, and let the gimbal's OWN
 *    stabilization hold the horizon between corrections.
 *  - [rotateTo] — a discrete ABSOLUTE_ANGLE move, for the one-shot "test gimbal" button only.
 *
 * REWRITTEN 2026-07-27 from a rotateTo-at-5Hz tracking approach that was visibly jumpy AND
 * looked unstabilized in flight: repeatedly firing KeyRotateByAngle (each a timed "move to X
 * over 0.3s" action) restarts a new eased move every ~200ms, which never settles and fights
 * the gimbal's built-in stabilization. Velocity control is the right tool for continuous
 * tracking -- the gimbal slews smoothly toward the target and holds/stabilizes when there.
 *
 * All key-based (no GimbalManager class exists in MSDK V5) -- KeyRotateBySpeed /
 * KeyRotateByAngle / KeyGimbalAttitude / KeyGimbalReset via KeyManager, verified against the
 * real 5.17.0 jar.
 */
class GimbalController {

    // Default CtrlInfo (both flags false). GimbalSpeedRotation requires one; null can be
    // rejected by the native layer, so pass a constructed default.
    private val ctrlInfo = CtrlInfo()

    /** Command pitch angular velocity in deg/s (yaw/roll held at 0). Sent continuously by the
     *  tracking loop; the gimbal keeps moving at this rate until the next command, so send 0
     *  to hold. Sign is DJI's convention -- verify direction on the first flight (a sign error
     *  just points the camera the wrong way, it is not a flight hazard). */
    fun setPitchSpeed(pitchDegreesPerSecond: Double) {
        val rotation = GimbalSpeedRotation(pitchDegreesPerSecond, /* yaw = */ 0.0, /* roll = */ 0.0, ctrlInfo)
        KeyManager.getInstance().performAction(KeyTools.createKey(GimbalKey.KeyRotateBySpeed), rotation, null)
    }

    /** Read the current gimbal pitch (degrees). Async getValue -- listen() delivers nothing on
     *  this hardware (see AircraftConnectionRepository). Timeout-wrapped so a hung read can't
     *  stall the tracking loop; returns null on failure/timeout so the loop just skips a tick. */
    suspend fun currentPitchDegrees(): Double? = withTimeoutOrNull(GET_VALUE_TIMEOUT_MS) {
        suspendCancellableCoroutine { cont ->
            KeyManager.getInstance().getValue(
                KeyTools.createKey(GimbalKey.KeyGimbalAttitude),
                object : CommonCallbacks.CompletionCallbackWithParam<Attitude> {
                    override fun onSuccess(value: Attitude?) {
                        if (cont.isActive) cont.resume(value?.pitch)
                    }

                    override fun onFailure(error: IDJIError) {
                        if (cont.isActive) cont.resume(null)
                    }
                },
            )
        }
    }

    /** Discrete absolute-angle move, for the one-shot test-gimbal button only (NOT tracking --
     *  see the class header on why repeating this is jumpy). */
    fun rotateTo(pitchDegrees: Double, yawDegrees: Double) {
        val rotation = GimbalAngleRotation(
            /* mode = */ GimbalAngleRotationMode.ABSOLUTE_ANGLE,
            /* pitch = */ pitchDegrees,
            /* roll = */ 0.0,
            /* yaw = */ yawDegrees,
            /* pitchIgnored = */ false,
            /* rollIgnored = */ true,
            /* yawIgnored = */ false,
            /* duration = */ GIMBAL_MOVE_DURATION_SECONDS,
            /* jointReferenceUsed = */ false,
            /* timeout = */ null,
        )
        KeyManager.getInstance().performAction(KeyTools.createKey(GimbalKey.KeyRotateByAngle), rotation, null)
    }

    fun recenter() {
        KeyManager.getInstance().performAction(KeyTools.createKey(GimbalKey.KeyGimbalReset), GimbalResetType.RECENTER, null)
    }

    private companion object {
        const val GIMBAL_MOVE_DURATION_SECONDS = 0.3
        const val GET_VALUE_TIMEOUT_MS = 500L
    }
}

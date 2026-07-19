package com.pelotom89.wingman.sdk

import dji.sdk.keyvalue.key.GimbalKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.gimbal.GimbalAngleRotation
import dji.sdk.keyvalue.value.gimbal.GimbalAngleRotationMode
import dji.sdk.keyvalue.value.gimbal.GimbalResetType
import dji.v5.manager.KeyManager

/**
 * Gimbal pitch/yaw is the first, cheapest way to re-center a subject in frame — cheaper
 * and lower-latency than an aircraft velocity change, so vision/ should prefer this for
 * small framing corrections and only fall through to VirtualStick aircraft movement when
 * the subject is leaving the gimbal's own range of motion or the aircraft needs to close
 * distance, not just re-center.
 *
 * CORRECTED against the real MSDK V5 jar: there is no discrete GimbalManager class at all
 * — gimbal control is entirely key-based (`GimbalKey.KeyRotateByAngle` /
 * `GimbalKey.KeyGimbalReset`, driven through the same `KeyManager.performAction` used for
 * other action-style commands), unlike VirtualStick/Perception which do have dedicated
 * manager singletons.
 */
class GimbalController {

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
    }
}

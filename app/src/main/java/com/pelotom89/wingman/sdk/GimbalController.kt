package com.pelotom89.wingman.sdk

import dji.sdk.keyvalue.value.gimbal.GimbalAngleRotation
import dji.v5.manager.aircraft.gimbal.GimbalManager

/**
 * Gimbal pitch/yaw is the first, cheapest way to re-center a subject in frame — cheaper
 * and lower-latency than an aircraft velocity change, so vision/ should prefer this for
 * small framing corrections and only fall through to VirtualStick aircraft movement when
 * the subject is leaving the gimbal's own range of motion or the aircraft needs to close
 * distance, not just re-center.
 *
 * NOTE: GimbalManager/GimbalAngleRotation shape per DJI's documented MSDK V5 pattern;
 * verify against the pinned SDK version's API reference at first build.
 */
class GimbalController(private val gimbalIndex: dji.sdk.keyvalue.value.common.ComponentIndexType) {

    fun rotateTo(pitchDegrees: Double, yawDegrees: Double) {
        val rotation = GimbalAngleRotation().apply {
            pitch = pitchDegrees
            yaw = yawDegrees
            pitchIgnored = false
            yawIgnored = false
            rollIgnored = true
            duration = GIMBAL_MOVE_DURATION_SECONDS
        }
        GimbalManager.getInstance().rotateByAngle(gimbalIndex, rotation, null)
    }

    fun recenter() {
        GimbalManager.getInstance().reset(gimbalIndex, null)
    }

    private companion object {
        const val GIMBAL_MOVE_DURATION_SECONDS = 0.3
    }
}

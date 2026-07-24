package com.pelotom89.wingman.sdk

import android.util.Log
import dji.sdk.keyvalue.value.flightcontroller.RollPitchControlMode
import dji.sdk.keyvalue.value.flightcontroller.VerticalControlMode
import dji.sdk.keyvalue.value.flightcontroller.VirtualStickFlightControlParam
import dji.sdk.keyvalue.value.flightcontroller.YawControlMode
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.aircraft.virtualstick.VirtualStickManager
import dji.v5.manager.aircraft.virtualstick.VirtualStickState
import dji.v5.manager.aircraft.virtualstick.VirtualStickStateListener
import dji.sdk.keyvalue.value.flightcontroller.FlightControlAuthorityChangeReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TAG = "WingmanVirtualStick"

/**
 * Owns the entire VirtualStick command lifecycle and is the ONLY class in the app allowed
 * to call sendVirtualStickAdvancedParam. Centralizing this matters for two reasons DJI's
 * own API forces on us:
 *
 *  1. Advanced-mode params are single-use — the aircraft reverts to hover if a fresh param
 *     isn't sent roughly every 40-200ms (5-25Hz per DJI's docs), so this owns a dedicated
 *     command loop rather than sending reactively on every upstream state change.
 *  2. Two callers racing to send stick commands is a real hazard, not just a style issue,
 *     given what this is actually flying toward.
 *
 * [overrideActiveFlow] is checked independently on every tick, not just trusted from
 * upstream — per the plan, the manual-override interrupt needs to work even if
 * FlightStateMachine has a bug, so this class treats it as its own hard gate rather than
 * assuming [commandFlow] already reflects it.
 *
 * NOTE: VirtualStickManager/VirtualStickFlightControlParam field names follow DJI's
 * documented MSDK V5 pattern (advanced mode, 5-25Hz send loop, single-use params);
 * verify exact signatures against the pinned SDK version's API reference at first build.
 */
class VirtualStickController(
    private val commandFlow: StateFlow<VirtualStickCommand>,
    private val overrideActiveFlow: StateFlow<Boolean>,
) {
    private var loopJob: Job? = null

    init {
        // Previously enableVirtualStick(null)/disableVirtualStick(null) passed no callback,
        // so a joystick session that silently failed to engage (or never actually held
        // flight-control authority) looked identical to a working one -- there was no signal
        // at all. This listener is the ground truth: isVirtualStickEnable() /
        // isVirtualStickAdvancedModeEnabled() confirm the mode actually engaged, and
        // getCurrentFlightControlAuthorityOwner() is the real answer to "did commands
        // actually reach the aircraft" -- if it's still RC (or reverts to RC, e.g. via the
        // RC_NOT_P_MODE reason DJI defines) after enableVirtualStick reports success, the
        // aircraft is ignoring VirtualStick commands regardless of what sendVirtualStickAdvancedParam
        // returns, which looks exactly like "the joystick didn't work" from the UI.
        VirtualStickManager.getInstance().setVirtualStickStateListener(object : VirtualStickStateListener {
            override fun onVirtualStickStateUpdate(state: VirtualStickState) {
                Log.i(
                    TAG,
                    "state: enabled=${state.isVirtualStickEnable} advancedMode=${state.isVirtualStickAdvancedModeEnabled} " +
                        "authorityOwner=${state.currentFlightControlAuthorityOwner}",
                )
            }

            override fun onChangeReasonUpdate(reason: FlightControlAuthorityChangeReason) {
                Log.i(TAG, "authority change reason: $reason")
            }
        })
    }

    /** Idempotent: a second call (e.g. manual-joystick flight started after Start Following
     *  already ran, or vice versa) replaces the existing loop instead of leaking a second
     *  one racing it at 10Hz. */
    fun start(scope: CoroutineScope) {
        loopJob?.cancel()

        VirtualStickManager.getInstance().enableVirtualStick(object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() { Log.i(TAG, "enableVirtualStick onSuccess") }
            override fun onFailure(error: IDJIError) { Log.w(TAG, "enableVirtualStick onFailure: $error") }
        })
        VirtualStickManager.getInstance().setVirtualStickAdvancedModeEnabled(true)

        loopJob = scope.launch {
            while (isActive) {
                val command = if (overrideActiveFlow.value) VirtualStickCommand.ZERO else commandFlow.value
                sendOnce(command)
                delay(COMMAND_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
        VirtualStickManager.getInstance().disableVirtualStick(object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() { Log.i(TAG, "disableVirtualStick onSuccess") }
            override fun onFailure(error: IDJIError) { Log.w(TAG, "disableVirtualStick onFailure: $error") }
        })
    }

    /** Immediate, out-of-band zero — used by ManualOverrideGate for the very next tick,
     *  without waiting for the loop's own poll of [overrideActiveFlow]. */
    fun emergencyZero() {
        sendOnce(VirtualStickCommand.ZERO)
    }

    private fun sendOnce(command: VirtualStickCommand) {
        val param = VirtualStickFlightControlParam().apply {
            pitch = command.pitchMetersPerSecond
            roll = command.rollMetersPerSecond
            yaw = command.yawDegreesPerSecond
            verticalThrottle = command.verticalMetersPerSecond
            rollPitchControlMode = RollPitchControlMode.VELOCITY
            yawControlMode = YawControlMode.ANGULAR_VELOCITY
            verticalControlMode = VerticalControlMode.VELOCITY
        }
        VirtualStickManager.getInstance().sendVirtualStickAdvancedParam(param)
    }

    private companion object {
        /** Mid-range of DJI's documented 5-25Hz window; fast enough for smooth following,
         *  slow enough to leave headroom on a phone also running live video + inference. */
        const val COMMAND_INTERVAL_MS = 100L
    }
}

/** App-owned command model so flightcontrol/ never constructs a DJI param type directly. */
data class VirtualStickCommand(
    val pitchMetersPerSecond: Double,
    val rollMetersPerSecond: Double,
    val yawDegreesPerSecond: Double,
    val verticalMetersPerSecond: Double,
) {
    companion object {
        val ZERO = VirtualStickCommand(0.0, 0.0, 0.0, 0.0)
    }
}

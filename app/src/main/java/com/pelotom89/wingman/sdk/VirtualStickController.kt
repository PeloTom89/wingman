package com.pelotom89.wingman.sdk

import android.util.Log
import dji.sdk.keyvalue.value.flightcontroller.FlightCoordinateSystem
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
import kotlinx.coroutines.Dispatchers
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

        // Dispatchers.Default, not the caller's own (viewModelScope defaults to
        // Dispatchers.Main.immediate): DJI's single-use advanced-mode params need a fresh
        // send roughly every 40-200ms or the aircraft reverts to hover (see class header).
        // Running this loop on the main thread put it in direct scheduling contention with
        // Compose recomposition and the live video feed -- confirmed on-device (2026-07-23)
        // as visible micro-stuttering while holding a stick steady in flight, smooth
        // directional movement otherwise. Off the main thread, a UI-thread stall can no
        // longer delay a send past the timeout.
        loopJob = scope.launch(Dispatchers.Default) {
            // Fixed-SCHEDULE loop, not fixed-DELAY: `delay(100)` after each send only
            // guarantees a 100ms gap AFTER sendOnce() returns, so if any single send is slow
            // (JNI/IPC variance, GC, scheduler contention) that time stacks on top instead of
            // being absorbed -- real-world cadence can drift even though each individual wait
            // is exactly 100ms. Tracking an absolute nextSendAtMs and sleeping only the
            // remainder self-corrects instead of accumulating drift. Added after a real
            // flight test showed continued micro-stuttering while holding a stick steady
            // even after moving this loop off the main thread (2026-07-23) -- logs when a
            // send falls behind schedule so the next test can confirm whether this loop's
            // own cadence is actually the cause.
            var nextSendAtMs = System.currentTimeMillis()
            while (isActive) {
                val command = if (overrideActiveFlow.value) VirtualStickCommand.ZERO else commandFlow.value
                sendOnce(command)
                nextSendAtMs += COMMAND_INTERVAL_MS
                val remainingMs = nextSendAtMs - System.currentTimeMillis()
                if (remainingMs > 0) {
                    delay(remainingMs)
                } else {
                    Log.w(TAG, "command loop behind schedule by ${-remainingMs}ms")
                    nextSendAtMs = System.currentTimeMillis()
                }
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

    // Peak-magnitude tracking for the log below -- see its comment.
    private var peakPitch = 0.0
    private var peakRoll = 0.0
    private var peakYaw = 0.0
    private var peakVertical = 0.0
    private var sendingNonZero = false

    private fun sendOnce(command: VirtualStickCommand) {
        // The UI only logs the FIRST sample after a stick crosses zero (see
        // WingmanViewModel.onManualStickChanged), which is necessarily tiny -- it's sampled
        // right at the touch-slop boundary, not the drag's actual peak. This is the
        // authoritative record instead: the real magnitude physically handed to
        // sendVirtualStickAdvancedParam every 100ms, logged once per zero-to-zero episode so
        // it doesn't flood at 10Hz. Added after a real flight test where the aircraft didn't
        // visibly respond despite the UI reporting non-zero commands -- this settles whether
        // the actual sent values ever got large, or stayed small the whole drag.
        val isZero = command.pitchMetersPerSecond == 0.0 && command.rollMetersPerSecond == 0.0 &&
            command.yawDegreesPerSecond == 0.0 && command.verticalMetersPerSecond == 0.0
        if (!isZero) {
            peakPitch = maxOf(peakPitch, kotlin.math.abs(command.pitchMetersPerSecond))
            peakRoll = maxOf(peakRoll, kotlin.math.abs(command.rollMetersPerSecond))
            peakYaw = maxOf(peakYaw, kotlin.math.abs(command.yawDegreesPerSecond))
            peakVertical = maxOf(peakVertical, kotlin.math.abs(command.verticalMetersPerSecond))
            sendingNonZero = true
        } else if (sendingNonZero) {
            Log.i(
                TAG,
                "sent peak this episode: pitch=$peakPitch roll=$peakRoll yaw=$peakYaw vertical=$peakVertical",
            )
            peakPitch = 0.0
            peakRoll = 0.0
            peakYaw = 0.0
            peakVertical = 0.0
            sendingNonZero = false
        }

        val param = VirtualStickFlightControlParam().apply {
            // SWAPPED, confirmed via a real flight test (2026-07-23) after the
            // rollPitchCoordinateSystem fix let commands finally reach the aircraft:
            // app.pitchMetersPerSecond (forward/back) consistently produced left/right
            // motion, and app.rollMetersPerSecond (left/right) consistently produced
            // forward/back motion -- a clean 90-degree swap across all four directions, not
            // a sign error. DJI's pitch/roll fields don't match this codebase's own
            // pitch=forward/roll=right convention (see VirtualStickCommand's doc and
            // FlightCommandCalculator), so compensate for it here, the one place that
            // constructs the DJI param, rather than at either caller.
            pitch = command.rollMetersPerSecond
            roll = command.pitchMetersPerSecond
            yaw = command.yawDegreesPerSecond
            verticalThrottle = command.verticalMetersPerSecond
            rollPitchControlMode = RollPitchControlMode.VELOCITY
            yawControlMode = YawControlMode.ANGULAR_VELOCITY
            verticalControlMode = VerticalControlMode.VELOCITY
            // MISSING FIELD, found via javap against the real 5.17.0 jar (2026-07-23): this
            // param has an 8th field, rollPitchCoordinateSystem, that was never set here --
            // left null on every send. Without it the flight controller can't know whether
            // pitch/roll mean aircraft-relative or north-relative, which plausibly explains
            // a real flight test where peak commands hit ~max (pitch/roll ~3 m/s, yaw ~60
            // deg/s, vertical 1.5 m/s -- confirmed via the peak-magnitude log above) with
            // VirtualStick authority genuinely held (authorityOwner=MSDK) and the aircraft
            // still not visibly responding. BODY matches this app's existing pitch/roll
            // convention (aircraft-relative forward/right -- see FlightCommandCalculator).
            rollPitchCoordinateSystem = FlightCoordinateSystem.BODY
        }
        VirtualStickManager.getInstance().sendVirtualStickAdvancedParam(param)
    }

    private companion object {
        /** 20Hz. Raised from 100ms/10Hz (2026-07-27) after a flight test confirmed the app's
         *  command output was already clean and steady -- one continuous 6.6s stick hold, a
         *  rock-steady velocity command reaching ~3 m/s, and zero behind-schedule warnings --
         *  yet the aircraft still "rocked back and forth" and wouldn't fly continuously. With
         *  the command stream ruled out as the cause, the remaining suspect is the aircraft
         *  reverting to hover between commands: DJI's advanced-mode params are single-use with
         *  a ~200ms revert timeout, and although our SEND cadence is a steady 100ms, arrival
         *  at the aircraft over the RC-N3 radio link has jitter -- an occasional gap past
         *  200ms makes the aircraft brake to hover, then the next command surges it again,
         *  which reads as the observed rocking. 50ms gives 4x margin against the revert
         *  timeout instead of 10Hz's 2x, at 2.5x the JNI call rate (fine on Dispatchers.Default).
         *  Still well within DJI's documented 5-25Hz window. */
        const val COMMAND_INTERVAL_MS = 50L
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

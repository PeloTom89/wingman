package com.pelotom89.wingman.flightcontrol

import android.util.Log
import com.pelotom89.wingman.sdk.VirtualStickController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Highest-priority interrupt in the app. Per the plan: "wired as close to
 * VirtualStickController as possible so a bug in state-machine logic can't block it" —
 * [activeFlow] is read independently by VirtualStickController's own command loop on
 * every tick (not just trusted to flow through FlightStateMachine's output), and [trip]
 * additionally calls [VirtualStickController.emergencyZero] synchronously so the very
 * next radio packet is zeroed rather than waiting for the loop's next scheduled poll.
 *
 * [activeFlowHolder] is INJECTED, not owned here, and must be the exact same
 * MutableStateFlow instance passed to [VirtualStickController]'s constructor. FOUND ON
 * REAL HARDWARE (2026-07-19): an earlier version had this class own a private
 * MutableStateFlow and hand VirtualStickController an unrelated, separate one --
 * VirtualStickController's own independent per-tick safety check silently never fired
 * because it was reading a flow [trip] never touched, even though [trip]'s flow (and
 * therefore FlightStateMachine, which does read the correct one) worked fine. Sharing one
 * instance is the only way both readers ("VirtualStickController checks every tick" and
 * "FlightStateMachine checks every combine() tick") observe the same state.
 *
 * No confirmation step on [trip] — a confirmation dialog on a stop control is actively
 * dangerous, per the plan.
 */
class ManualOverrideGate(
    private val activeFlowHolder: MutableStateFlow<Boolean>,
    private val virtualStickController: VirtualStickController,
) {

    val activeFlow: StateFlow<Boolean> get() = activeFlowHolder.asStateFlow()

    fun trip() {
        Log.i("WingmanUI", "ManualOverrideGate.trip() called")
        activeFlowHolder.value = true
        virtualStickController.emergencyZero()
    }

    /** Explicit operator action only — never called automatically by the state machine. */
    fun clear() {
        activeFlowHolder.value = false
    }
}

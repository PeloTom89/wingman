package com.pelotom89.wingman.flightcontrol

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
 * No confirmation step on [trip] — a confirmation dialog on a stop control is actively
 * dangerous, per the plan.
 */
class ManualOverrideGate(private val virtualStickController: VirtualStickController) {

    private val _activeFlow = MutableStateFlow(false)
    val activeFlow: StateFlow<Boolean> get() = _activeFlow.asStateFlow()

    fun trip() {
        _activeFlow.value = true
        virtualStickController.emergencyZero()
    }

    /** Explicit operator action only — never called automatically by the state machine. */
    fun clear() {
        _activeFlow.value = false
    }
}

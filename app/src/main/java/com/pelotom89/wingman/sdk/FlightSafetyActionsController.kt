package com.pelotom89.wingman.sdk

import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.v5.manager.KeyManager

/**
 * The native go-home/land actions FlightStateMachine's ReturnToHome/EmergencyStop states
 * intentionally do NOT drive via VirtualStick (see FlightStateMachine's header comment) —
 * this is that out-of-band path, wired from the composition root (WingmanViewModel)
 * observing flight-state transitions, not from FlightStateMachine itself, so that class
 * stays testable without an SDK dependency.
 */
class FlightSafetyActionsController {

    fun startGoHome() {
        KeyManager.getInstance().performAction(KeyTools.createKey(FlightControllerKey.KeyStartGoHome), EmptyMsg(), null)
    }

    fun startAutoLanding() {
        KeyManager.getInstance().performAction(KeyTools.createKey(FlightControllerKey.KeyStartAutoLanding), EmptyMsg(), null)
    }
}

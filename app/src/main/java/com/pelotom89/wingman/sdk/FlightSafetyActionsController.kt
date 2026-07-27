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

    /** DJI pauses autonomous landing at a low hover (observed ~1ft) and waits for this before
     *  cutting motors and touching down -- a deliberate safety check so a human can confirm
     *  the spot below is actually clear (KeyIsLandingConfirmationNeeded /
     *  KeyConfirmLanding, verified via javap against the real 5.17.0 jar). startAutoLanding()
     *  alone never sends this, so without it the aircraft just hovers there indefinitely --
     *  confirmed on-device 2026-07-27 (Land pressed, aircraft stopped ~1ft up and never
     *  touched down). Wired to an explicit "Confirm Landing" UI button for manual test
     *  flights, and auto-called after a timeout for the automatic battery-critical
     *  EmergencyStop landing (see WingmanViewModel) -- battery is already critical there, so
     *  waiting indefinitely for a human tap risks running out of power mid-air instead of
     *  completing a controlled landing. */
    fun confirmLanding() {
        KeyManager.getInstance().performAction(KeyTools.createKey(FlightControllerKey.KeyConfirmLanding), EmptyMsg(), null)
    }

    /** Aborts an in-progress autonomous landing (KeyStopAutoLanding) and returns to a normal
     *  hover -- the escape hatch for "I pressed Land by mistake" or "I want manual control
     *  back instead of touching down here," as opposed to confirmLanding() which completes
     *  the descent. */
    fun stopAutoLanding() {
        KeyManager.getInstance().performAction(KeyTools.createKey(FlightControllerKey.KeyStopAutoLanding), EmptyMsg(), null)
    }

    /** DJI's own autonomous takeoff: arms, climbs, and auto-hovers at DJI's fixed default
     *  height (~1.2m) using the flight controller's own GPS-or-vision position hold — no
     *  height parameter exists on this key (verified via javap against the real 5.17.0 jar).
     *  Works indoors on vision positioning the same way DJI Fly/Maven/etc. do; nothing here
     *  requires GPS. */
    fun startTakeoff() {
        KeyManager.getInstance().performAction(KeyTools.createKey(FlightControllerKey.KeyStartTakeoff), EmptyMsg(), null)
    }
}

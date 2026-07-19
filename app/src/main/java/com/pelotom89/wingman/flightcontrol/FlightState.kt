package com.pelotom89.wingman.flightcontrol

sealed class FlightState {
    data object Idle : FlightState()
    data object ManualOverride : FlightState()

    /** GPS-only following (see README — vision-based tracking was removed): holds a
     *  standoff distance from the subject's phone-GPS position, aircraft yaw facing them,
     *  gimbal pitched down at their ground-level position. The only active tracking mode. */
    data object Following : FlightState()

    data class ReturnToHome(val reason: String) : FlightState()
    data class EmergencyStop(val reason: String) : FlightState()
}

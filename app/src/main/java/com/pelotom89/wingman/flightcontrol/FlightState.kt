package com.pelotom89.wingman.flightcontrol

sealed class FlightState {
    data object Idle : FlightState()
    data object ManualOverride : FlightState()
    data object VisualTrack : FlightState()
    data object GpsGuided : FlightState()
    data class ReturnToHome(val reason: String) : FlightState()
    data class EmergencyStop(val reason: String) : FlightState()
}

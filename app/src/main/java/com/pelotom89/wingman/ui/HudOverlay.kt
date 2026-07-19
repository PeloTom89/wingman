package com.pelotom89.wingman.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.pelotom89.wingman.flightcontrol.FlightState
import com.pelotom89.wingman.sdk.AircraftTelemetry

@Composable
fun HudOverlay(flightState: FlightState, telemetry: AircraftTelemetry?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.5f))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(text = "MODE: ${flightState.label()}", color = flightState.color())
        telemetry?.let {
            Text(
                text = "Battery ${it.batteryPercent}%  Alt ${"%.1f".format(it.altitudeMeters)}m  " +
                    "GPS ${"%.6f".format(it.latitude)}, ${"%.6f".format(it.longitude)}",
                color = Color.White,
            )
        }
    }
}

private fun FlightState.label(): String = when (this) {
    is FlightState.Idle -> "IDLE"
    is FlightState.ManualOverride -> "MANUAL OVERRIDE"
    is FlightState.VisualTrack -> "VISUAL TRACK"
    is FlightState.GpsGuided -> "GPS GUIDED"
    is FlightState.ReturnToHome -> "RETURN TO HOME — $reason"
    is FlightState.EmergencyStop -> "EMERGENCY STOP — $reason"
}

private fun FlightState.color(): Color = when (this) {
    is FlightState.Idle -> Color.Gray
    is FlightState.ManualOverride -> Color.Yellow
    is FlightState.VisualTrack -> Color.Green
    is FlightState.GpsGuided -> Color.Cyan
    is FlightState.ReturnToHome -> Color(0xFFFFA500) // orange
    is FlightState.EmergencyStop -> Color.Red
}

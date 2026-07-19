package com.pelotom89.wingman.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.pelotom89.wingman.flightcontrol.FlightState

/**
 * Replaces the old tap-to-select-a-subject gesture: the subject is always "whoever is
 * carrying this phone" (see WingmanViewModel), so there's nothing to select — just a
 * single operator decision to start. Disabled once following (or in any state other than
 * Idle) so a repeat press can't re-arm the launch point mid-flight.
 */
@Composable
fun StartFollowingButton(flightState: FlightState, onPressed: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onPressed,
        enabled = flightState is FlightState.Idle,
        modifier = modifier
            .padding(16.dp)
            .size(width = 160.dp, height = 64.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
    ) {
        Text("START FOLLOWING", color = Color.White)
    }
}

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
 * A single toggle: START FOLLOWING when idle, STOP FOLLOWING while following (2026-07-27 --
 * replaced a separate stop button). The subject is always "whoever is carrying this phone"
 * (see WingmanViewModel), so there's nothing to select — just start/stop. Enabled only in
 * Idle (can start) or Following (can stop); disabled in the safety states (RTH/EmergencyStop)
 * where the operator shouldn't be toggling follow.
 */
@Composable
fun StartFollowingButton(
    flightState: FlightState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val following = flightState is FlightState.Following
    Button(
        onClick = { if (following) onStop() else onStart() },
        enabled = following || flightState is FlightState.Idle,
        modifier = modifier.size(width = 180.dp, height = 52.dp),
        // Explicit disabled colors: the default M3 light-scheme disabled colors are low-alpha
        // near-black -- invisible over the camera preview (see PreflightChecklistScreen.kt).
        colors = ButtonDefaults.buttonColors(
            containerColor = if (following) Color(0xFFC62828) else Color(0xFF2E7D32),
            disabledContainerColor = Color.DarkGray,
            disabledContentColor = Color.LightGray,
        ),
    ) {
        Text(if (following) "STOP FOLLOWING" else "START FOLLOWING", color = Color.White)
    }
}

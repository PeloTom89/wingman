package com.pelotom89.wingman.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.pelotom89.wingman.sdk.SdkRegistrationState

/**
 * Gates Idle -> VisualTrack on SDK registration + a live aircraft connection + a GPS fix
 * being available, AND an explicit VLOS acknowledgment EVERY session — per the plan, this
 * is the one legal responsibility (the pilot keeping the aircraft itself within visual
 * line of sight, regardless of whether the subject is in frame) that software cannot
 * absorb, so it's a real on-screen gate here, not just a README note.
 */
@Composable
fun PreflightChecklistScreen(
    registrationState: SdkRegistrationState,
    hasGpsFix: Boolean,
    onProceed: () -> Unit,
    onTestVisionPipeline: () -> Unit,
) {
    var vlosAcknowledged by remember { mutableStateOf(false) }

    val sdkReady = registrationState is SdkRegistrationState.ProductConnected
    val allReady = sdkReady && hasGpsFix && vlosAcknowledged

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Wingman preflight", style = MaterialTheme.typography.headlineSmall, color = Color.White)

        ChecklistRow("DJI SDK registered & aircraft connected", sdkReady)
        ChecklistRow("Phone GPS fix acquired", hasGpsFix)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = vlosAcknowledged, onCheckedChange = { vlosAcknowledged = it })
            Text(
                "I will maintain visual line of sight of the AIRCRAFT at all times, " +
                    "per FAA Part 107 (or local equivalent) — this app's GPS-guided fallback " +
                    "keeps the subject framed when the camera loses them, it does not relax " +
                    "my own line-of-sight obligation on the drone itself.",
                color = Color.White,
            )
        }

        Button(onClick = onProceed, enabled = allReady) {
            Text("Begin flight")
        }

        // No DJI/GPS gating -- phone-camera-only, see ui/VisionTestScreen.kt. Exists so
        // the detect/track pipeline (Milestone 2) is testable before any drone hardware
        // is available, not just after the checklist above is satisfied.
        Button(onClick = onTestVisionPipeline) {
            Text("Test vision pipeline (phone camera, no drone)")
        }
    }
}

@Composable
private fun ChecklistRow(label: String, ready: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (ready) "✓" else "…", color = if (ready) Color.Green else Color.Gray)
        Text(" $label", color = Color.White)
    }
}

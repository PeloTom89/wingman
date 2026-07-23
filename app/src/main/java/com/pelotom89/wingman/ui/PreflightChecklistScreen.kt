package com.pelotom89.wingman.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
 * Gates Idle -> Following on SDK registration + a live aircraft connection + a GPS fix
 * being available, AND an explicit VLOS acknowledgment EVERY session — per the plan, this
 * is the one legal responsibility (the pilot keeping the aircraft itself within visual
 * line of sight, regardless of whether the subject is in frame) that software cannot
 * absorb, so it's a real on-screen gate here, not just a README note.
 */
@Composable
fun PreflightChecklistScreen(
    registrationState: SdkRegistrationState,
    flightControllerConnected: Boolean,
    remoteControllerConnected: Boolean,
    aircraftLinkStalled: Boolean,
    hasGpsFix: Boolean,
    onProceed: () -> Unit,
    onTestVideo: () -> Unit,
) {
    var vlosAcknowledged by remember { mutableStateOf(false) }

    // Split into two rows deliberately -- VERIFIED on-device (2026-07-19) that these are
    // genuinely independent: SDKManager's ProductConnected only means the RC-N3 is linked
    // to the phone over USB, and can be true while the aircraft's flight controller itself
    // is unconnected (observed: aircraft LEDs flashing red/error, zero FlightController
    // telemetry, while this app's own registrationState still read ProductConnected). A
    // single combined checkmark here would have silently lied about the aircraft being
    // ready. See AircraftConnectionRepository.flightControllerConnectedFlow's header comment.
    val sdkReady = registrationState is SdkRegistrationState.ProductConnected
    val allReady = sdkReady && flightControllerConnected && hasGpsFix && vlosAcknowledged

    // Landscape-locked (see MainActivity's screenOrientation) means very little vertical
    // height to work with -- scrollable so the "Begin flight" button (last item) can never
    // be silently clipped below the screen on a shorter phone, which is exactly what was
    // happening with a plain non-scrolling Column here.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Wingman preflight", style = MaterialTheme.typography.headlineSmall, color = Color.White)

        ChecklistRow("DJI SDK registered, RC-N3 connected", sdkReady)
        // Raw state, not just the collapsed ready/not-ready dot above -- SdkRegistrationState
        // has real diagnostic value (Registering vs. Failed(message) vs. Registered-but-not-
        // yet-ProductConnected) that was previously only visible by pulling logcat.
        Text("  state: $registrationState", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
        ChecklistRow("Aircraft flight controller connected", flightControllerConnected)
        // Diagnostic-only (doesn't gate allReady -- if it's false the FC row above is
        // necessarily also false): splits the two hops apart so a stall is attributable.
        // True here + FC row stuck = the RC<->aircraft hop (issue-#427 firmware state);
        // false here = the phone<->RC USB hop itself, despite ProductConnected.
        Text(
            "  RC-N3 key channel: ${if (remoteControllerConnected) "responding" else "not responding"}",
            color = Color.Gray,
            style = MaterialTheme.typography.bodySmall,
        )
        // The FC link either comes up within a few seconds of ProductConnected or not at
        // all (RC firmware stuck in the state DJI acknowledges in Mobile-SDK-Android-V5
        // issue #427 and says only force-stopping DJI Fly clears -- there's no API to fix
        // it from here). Waiting silently was the previous behavior; this tells the
        // operator when waiting has become pointless and exactly what to do about it.
        if (aircraftLinkStalled) {
            Text(
                if (remoteControllerConnected) {
                    "Aircraft link stalled (known DJI RC firmware issue): the RC is talking " +
                        "to the phone, but the aircraft link won't come up on its own. " +
                        "Fix: Android Settings → Apps → DJI Fly → Force stop, then unplug " +
                        "and replug the USB cable. If the aircraft LEDs still flash red, " +
                        "power-cycle the RC and aircraft."
                } else {
                    "RC-N3 is not responding on the key channel despite the USB link " +
                        "reporting connected — unplug and replug the USB cable (try a " +
                        "different cable/port if it persists)."
                },
                color = Color(0xFFFFB74D),
            )
        }
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

        // Explicit disabled colors: MaterialTheme here is the unmodified M3 default (no
        // app-wide dark ColorScheme defined -- see ui/theme), so a disabled Button's
        // default colors come from a LIGHT scheme's low-alpha onSurface. Against this
        // screen's hardcoded black background that's indistinguishable from invisible --
        // this button WAS rendering the whole time, just impossible to see, before this.
        Button(
            onClick = onProceed,
            enabled = allReady,
            colors = ButtonDefaults.buttonColors(
                disabledContainerColor = Color.DarkGray,
                disabledContentColor = Color.LightGray,
            ),
        ) {
            Text("Begin flight")
        }

        // Diagnostic (always enabled, no connection gate): opens the aircraft camera feed
        // directly. If video shows, the aircraft link is genuinely up and the checklist's
        // FlightControllerKey.KeyConnection gate above is the unreliable signal, not the
        // connection. See MainActivity's VIDEO_TEST branch.
        Button(
            onClick = onTestVideo,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0)),
        ) {
            Text("Test video feed (bypass connection gate)")
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

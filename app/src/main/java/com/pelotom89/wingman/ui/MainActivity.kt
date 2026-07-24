package com.pelotom89.wingman.ui

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pelotom89.wingman.flightcontrol.SafetyLimits
import dji.sdk.keyvalue.value.common.ComponentIndexType

private enum class Screen { PREFLIGHT, FLIGHT, VIDEO_TEST }

/** Single source of truth for the joystick's max output -- reads SafetyLimits' own defaults
 *  rather than duplicating the numbers, so the stick's proportional feel always matches the
 *  clamp WingmanViewModel.onManualStickChanged applies to every command anyway. */
private val JOYSTICK_SAFETY_LIMITS = SafetyLimits()

class MainActivity : ComponentActivity() {

    private val viewModel: WingmanViewModel by viewModels()

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { /* PreflightChecklistScreen re-checks permission state on next recomposition */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestPermissions.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
            ),
        )

        setContent {
            MaterialTheme {
                var screen by remember { mutableStateOf(Screen.PREFLIGHT) }

                val registrationState by viewModel.registrationState.collectAsStateWithLifecycle()
                val flightControllerConnected by viewModel.flightControllerConnected.collectAsStateWithLifecycle()
                val remoteControllerConnected by viewModel.remoteControllerConnected.collectAsStateWithLifecycle()
                val aircraftLinkStalled by viewModel.aircraftLinkStalled.collectAsStateWithLifecycle()
                val flightState by viewModel.flightState.collectAsStateWithLifecycle()
                val telemetry by viewModel.telemetry.collectAsStateWithLifecycle()

                Box(modifier = Modifier.fillMaxSize()) {
                    when (screen) {
                        Screen.PREFLIGHT -> PreflightChecklistScreen(
                            registrationState = registrationState,
                            flightControllerConnected = flightControllerConnected,
                            remoteControllerConnected = remoteControllerConnected,
                            aircraftLinkStalled = aircraftLinkStalled,
                            hasGpsFix = telemetry != null,
                            onProceed = { screen = Screen.FLIGHT },
                            onTestVideo = { screen = Screen.VIDEO_TEST },
                        )
                        // Diagnostic: show the aircraft camera feed with NO connection gate.
                        // If video appears here, the aircraft link is genuinely working and
                        // FlightControllerKey.KeyConnection (what the checklist gates on) is
                        // just an unreliable signal -- meaning the "not connected" state is a
                        // gating bug, not a real connection failure. Tap anywhere to go back.
                        Screen.VIDEO_TEST -> Box(modifier = Modifier.fillMaxSize()) {
                            val manualFlightActive by viewModel.manualFlightActive.collectAsStateWithLifecycle()
                            var stickPitchRoll by remember { mutableStateOf(Offset.Zero) }
                            var stickVertical by remember { mutableStateOf(0f) }

                            CameraPreviewScreen(cameraIndex = ComponentIndexType.LEFT_OR_MAIN)
                            HudOverlay(flightState = flightState, telemetry = telemetry)
                            Button(
                                onClick = { screen = Screen.PREFLIGHT },
                                modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
                            ) { Text("Back") }
                            // Indoor command-path test: moves the gimbal (visible in the feed)
                            // to prove aircraft COMMANDS reach the drone, independent of the
                            // telemetry getValue path. See WingmanViewModel.onTestGimbalPressed.
                            Button(
                                onClick = { viewModel.onTestGimbalPressed() },
                                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                            ) { Text("Test gimbal (tilt camera)") }
                            // Real-flight command test: DJI's own autonomous takeoff/landing
                            // (KeyStartTakeoff/KeyStartAutoLanding), not VirtualStick -- see
                            // WingmanViewModel.onTestTakeoffPressed. Works indoors on vision
                            // positioning the same way DJI Fly does.
                            Row(
                                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Button(
                                    onClick = { viewModel.onTestTakeoffPressed() },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                                ) { Text("Takeoff") }
                                Button(
                                    onClick = { viewModel.onTestLandPressed() },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
                                ) { Text("Land") }
                            }
                            // Manual joystick flight test -- see WingmanViewModel's
                            // manualFlightActiveHolder comment. STOP/Resume mirror the flight
                            // screen's ManualOverrideButton/clear so a runaway stick has the
                            // same immediate, no-confirmation escape hatch real flight does.
                            Row(
                                modifier = Modifier.align(Alignment.TopStart).padding(16.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Button(
                                    onClick = { viewModel.onManualFlightToggled(!manualFlightActive) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (manualFlightActive) Color(0xFF2E7D32) else Color.DarkGray,
                                    ),
                                ) { Text(if (manualFlightActive) "Manual control: ON" else "Enable manual control") }
                                if (manualFlightActive) {
                                    Button(
                                        onClick = { viewModel.onManualOverridePressed() },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                                    ) { Text("STOP") }
                                    Button(
                                        onClick = { viewModel.onManualOverrideCleared() },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
                                    ) { Text("Resume") }
                                }
                            }
                            if (manualFlightActive) {
                                Row(
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(bottom = 88.dp),
                                    horizontalArrangement = Arrangement.spacedBy(48.dp),
                                ) {
                                    // Left stick: up/down only.
                                    Joystick(lockHorizontal = true, size = 120.dp) { _, y ->
                                        stickVertical = -y * JOYSTICK_SAFETY_LIMITS.maxVerticalSpeedMetersPerSecond.toFloat()
                                        viewModel.onManualStickChanged(
                                            pitchMetersPerSecond = stickPitchRoll.y.toDouble(),
                                            rollMetersPerSecond = stickPitchRoll.x.toDouble(),
                                            verticalMetersPerSecond = stickVertical.toDouble(),
                                        )
                                    }
                                    // Right stick: forward/back + left/right.
                                    Joystick(size = 140.dp) { x, y ->
                                        val maxHorizontal = JOYSTICK_SAFETY_LIMITS.maxHorizontalSpeedMetersPerSecond.toFloat()
                                        stickPitchRoll = Offset(x = x * maxHorizontal, y = -y * maxHorizontal)
                                        viewModel.onManualStickChanged(
                                            pitchMetersPerSecond = stickPitchRoll.y.toDouble(),
                                            rollMetersPerSecond = stickPitchRoll.x.toDouble(),
                                            verticalMetersPerSecond = stickVertical.toDouble(),
                                        )
                                    }
                                }
                            }
                        }
                        Screen.FLIGHT -> {
                            // CameraPreviewScreen is composed ONLY here, on the flight screen,
                            // which is unreachable until flightControllerConnected (the "Begin
                            // flight" gate). This is deliberate (reverted an earlier attempt to
                            // compose it behind both screens): requesting the aircraft camera
                            // stream (enableStream / putCameraStreamSurface) BEFORE the aircraft
                            // is connected is the third pre-connection aircraft request Wingman
                            // was uniquely making (alongside VirtualStick and gimbal, both now
                            // gated) that no working competitor makes until after connection --
                            // Litchi's own code waits for onAvailableCameraUpdated. Sending it
                            // during the handshake window competes with / disrupts the RC<->
                            // aircraft link and leaves the FlightController handlers only
                            // partially registered (serial reads, but zero telemetry/KeyConnection).
                            CameraPreviewScreen(cameraIndex = ComponentIndexType.LEFT_OR_MAIN)
                            HudOverlay(flightState = flightState, telemetry = telemetry)
                            StartFollowingButton(
                                flightState = flightState,
                                onPressed = { viewModel.onStartFollowingPressed() },
                                modifier = Modifier.align(Alignment.BottomStart),
                            )
                            ManualOverrideButton(
                                onPressed = { viewModel.onManualOverridePressed() },
                                modifier = Modifier.align(Alignment.BottomEnd),
                            )
                        }
                    }
                }
            }
        }
    }
}

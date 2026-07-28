package com.pelotom89.wingman.ui

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
                val landingConfirmationNeeded by viewModel.landingConfirmationNeeded.collectAsStateWithLifecycle()
                val manualFlightActive by viewModel.manualFlightActive.collectAsStateWithLifecycle()

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
                            // Phase 1 vision: run person detection over the live feed and draw
                            // the boxes -- ground-testable (point the drone camera at a person).
                            // Started/stopped with this screen's lifecycle; drives nothing yet.
                            val detections by viewModel.detections.collectAsStateWithLifecycle()
                            val visionMs by viewModel.visionInferenceMs.collectAsStateWithLifecycle()
                            DisposableEffect(Unit) {
                                viewModel.startVisionDetection()
                                onDispose { viewModel.stopVisionDetection() }
                            }

                            CameraPreviewScreen(cameraIndex = ComponentIndexType.LEFT_OR_MAIN)
                            DetectionOverlay(detections = detections)
                            HudOverlay(flightState = flightState, telemetry = telemetry)
                            Text(
                                text = "Vision: ${detections.size} person(s)  ${visionMs}ms",
                                color = Color(0xFF00E676),
                                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
                            )
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
                            // DJI pauses autonomous landing at a low hover awaiting this --
                            // see FlightSafetyActionsController.confirmLanding()'s header
                            // comment. Without it Land alone leaves the aircraft hovering
                            // indefinitely (observed on-device 2026-07-27). Centered and
                            // large since this is exactly the state that stranded a real
                            // flight test -- should be impossible to miss when it appears.
                            if (landingConfirmationNeeded) {
                                Row(
                                    modifier = Modifier.align(Alignment.Center),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Button(
                                        onClick = { viewModel.onConfirmLandingPressed() },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                                    ) { Text("Confirm Landing") }
                                    Button(
                                        onClick = { viewModel.onCancelLandingPressed() },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
                                    ) { Text("Cancel Landing") }
                                }
                            }
                            ManualControlBar(
                                viewModel = viewModel,
                                manualFlightActive = manualFlightActive,
                                modifier = Modifier.align(Alignment.TopStart).padding(16.dp),
                            )
                            if (manualFlightActive) ManualJoysticks(viewModel)
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
                            // HUD + all flight controls STACKED at the top (operator feedback
                            // 2026-07-27: top buttons overlapped the GPS/telemetry header, and
                            // the separate STOP should just be the Start Following toggle).
                            // The button row scrolls horizontally so it never overflows.
                            // Take off, then CLIMB (RC or the manual joysticks below) to a
                            // usable following altitude before Start Following -- the first
                            // outdoor follow only failed because the aircraft was stuck at
                            // ~1.1m where ground-proximity vision-hold blocks horizontal motion.
                            Column(modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth()) {
                                HudOverlay(flightState = flightState, telemetry = telemetry)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState())
                                        .padding(8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    StartFollowingButton(
                                        flightState = flightState,
                                        onStart = { viewModel.onStartFollowingPressed() },
                                        onStop = { viewModel.onStopPressed() },
                                    )
                                    ManualControlBar(
                                        viewModel = viewModel,
                                        manualFlightActive = manualFlightActive,
                                    )
                                    Button(
                                        onClick = { viewModel.onTestTakeoffPressed() },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                                    ) { Text("Takeoff") }
                                    Button(
                                        onClick = { viewModel.onTestLandPressed() },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
                                    ) { Text("Land") }
                                    Button(onClick = { screen = Screen.PREFLIGHT }) { Text("Back") }
                                }
                            }
                            if (landingConfirmationNeeded) {
                                Row(
                                    modifier = Modifier.align(Alignment.Center),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Button(
                                        onClick = { viewModel.onConfirmLandingPressed() },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                                    ) { Text("Confirm Landing") }
                                    Button(
                                        onClick = { viewModel.onCancelLandingPressed() },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
                                    ) { Text("Cancel Landing") }
                                }
                            }
                            if (manualFlightActive) ManualJoysticks(viewModel)
                        }
                    }
                }
            }
        }
    }
}

/** Manual-flight enable toggle, shared by the video-test and flight screens. Toggling on
 *  enables VirtualStick (ANGLE roll/pitch) and shows the joysticks; off releases VirtualStick
 *  so the RC flies. The old STOP/Resume sub-buttons were removed -- STOP is the flight
 *  screen's dedicated button now, and it fully stops+releases rather than latching an
 *  override the joysticks then had to fight. */
@Composable
private fun ManualControlBar(
    viewModel: WingmanViewModel,
    manualFlightActive: Boolean,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = { viewModel.onManualFlightToggled(!manualFlightActive) },
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (manualFlightActive) Color(0xFF2E7D32) else Color.DarkGray,
        ),
    ) { Text(if (manualFlightActive) "Manual control: ON" else "Enable manual control") }
}

/** The two manual-flight joysticks (Mode-2 RC layout), shared by the video-test and flight
 *  screens. Left = yaw (turn) + vertical (up/down); right = pitch/roll tilt (fwd/back +
 *  strafe). Each instance owns its own transient stick state. Roll/pitch run in ANGLE mode
 *  (tilt degrees) and yaw/vertical in their normal units -- see VirtualStickController. */
@Composable
private fun BoxScope.ManualJoysticks(viewModel: WingmanViewModel) {
    var stickPitchRoll by remember { mutableStateOf(Offset.Zero) }
    var stickYaw by remember { mutableStateOf(0f) }
    var stickVertical by remember { mutableStateOf(0f) }
    Row(
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 88.dp),
        horizontalArrangement = Arrangement.spacedBy(48.dp),
    ) {
        // Left stick: x = yaw (turn left/right), y = vertical (up/down).
        Joystick(size = 120.dp) { x, y ->
            stickYaw = x * JOYSTICK_SAFETY_LIMITS.maxYawDegreesPerSecond.toFloat()
            stickVertical = -y * JOYSTICK_SAFETY_LIMITS.maxVerticalSpeedMetersPerSecond.toFloat()
            viewModel.onManualStickChanged(
                pitchMetersPerSecond = stickPitchRoll.y.toDouble(),
                rollMetersPerSecond = stickPitchRoll.x.toDouble(),
                yawDegreesPerSecond = stickYaw.toDouble(),
                verticalMetersPerSecond = stickVertical.toDouble(),
            )
        }
        // Right stick: forward/back (pitch) + strafe (roll), scaled to TILT DEGREES (ANGLE mode).
        Joystick(size = 140.dp) { x, y ->
            val maxTilt = JOYSTICK_SAFETY_LIMITS.maxManualTiltDegrees.toFloat()
            stickPitchRoll = Offset(x = x * maxTilt, y = -y * maxTilt)
            viewModel.onManualStickChanged(
                pitchMetersPerSecond = stickPitchRoll.y.toDouble(),
                rollMetersPerSecond = stickPitchRoll.x.toDouble(),
                yawDegreesPerSecond = stickYaw.toDouble(),
                verticalMetersPerSecond = stickVertical.toDouble(),
            )
        }
    }
}

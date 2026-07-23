package com.pelotom89.wingman.ui

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dji.sdk.keyvalue.value.common.ComponentIndexType

private enum class Screen { PREFLIGHT, FLIGHT, VIDEO_TEST }

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

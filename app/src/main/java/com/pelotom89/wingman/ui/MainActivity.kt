package com.pelotom89.wingman.ui

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dji.sdk.keyvalue.value.common.ComponentIndexType

/**
 * CameraPreviewScreen is composed unconditionally behind BOTH [Screen]s, not just
 * [Screen.FLIGHT] -- see CameraPreviewScreen's header comment. Decompiling Dronelink,
 * Litchi Pilot, and Maven EVO (2026-07-22, all three confirmed reliably reaching
 * FlightControllerKey.KeyConnection where Wingman was stalling on the same aircraft/RC-N3)
 * found all three bind ICameraStreamManager.putCameraStreamSurface unconditionally, as
 * soon as their video surface exists -- never gated behind any FlightController connection
 * check. Wingman previously only ever reached CameraPreviewScreen (nested inside
 * Screen.FLIGHT) AFTER flightControllerConnected was already true, since
 * PreflightChecklistScreen's "Begin flight" button that switches to Screen.FLIGHT is
 * itself disabled until flightControllerConnected is true -- a structural chicken-and-egg
 * that meant Wingman never got a chance to test whether requesting the video stream early
 * helps establish the aircraft link, unlike every working competitor. Composing it behind
 * the checklist (invisible under its opaque black background, but still attached to the
 * window and laid out, so its TextureView still gets a real Surface and still calls
 * putCameraStreamSurface) removes that gate without weakening the "Begin flight" gate
 * itself, which still requires flightControllerConnected.
 */
private enum class Screen { PREFLIGHT, FLIGHT }

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
                    // Composed unconditionally, behind both screens -- see this file's
                    // header comment. On PREFLIGHT this sits under PreflightChecklistScreen's
                    // opaque black background (invisible, but still attached/laid out, so
                    // putCameraStreamSurface still fires as early as possible).
                    CameraPreviewScreen(cameraIndex = ComponentIndexType.LEFT_OR_MAIN)

                    when (screen) {
                        Screen.PREFLIGHT -> PreflightChecklistScreen(
                            registrationState = registrationState,
                            flightControllerConnected = flightControllerConnected,
                            remoteControllerConnected = remoteControllerConnected,
                            aircraftLinkStalled = aircraftLinkStalled,
                            hasGpsFix = telemetry != null,
                            onProceed = { screen = Screen.FLIGHT },
                        )
                        Screen.FLIGHT -> {
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

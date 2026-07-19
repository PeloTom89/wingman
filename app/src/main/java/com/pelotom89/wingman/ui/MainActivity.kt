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
                val flightState by viewModel.flightState.collectAsStateWithLifecycle()
                val telemetry by viewModel.telemetry.collectAsStateWithLifecycle()

                when (screen) {
                    Screen.PREFLIGHT -> PreflightChecklistScreen(
                        registrationState = registrationState,
                        hasGpsFix = telemetry != null,
                        onProceed = { screen = Screen.FLIGHT },
                    )
                    Screen.FLIGHT -> Box(modifier = Modifier.fillMaxSize()) {
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

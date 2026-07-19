package com.pelotom89.wingman.ui

import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pelotom89.wingman.vision.BoundingBox
import com.pelotom89.wingman.vision.TrackingResult

/**
 * Standalone vision-pipeline test screen — phone's own camera, no DJI connection, no
 * flight control. Exists to make Milestone 2 ("vision pipeline standalone... check
 * latency/frame-drop against live decode load") actually testable before any drone
 * hardware is available. Reachable from PreflightChecklistScreen independent of the
 * DJI-gated "Begin flight" flow.
 */
@Composable
fun VisionTestScreen(onBack: () -> Unit, viewModel: VisionTestViewModel = viewModel()) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }

    val trackingResult by viewModel.trackingResult.collectAsStateWithLifecycle()
    val fps by viewModel.framesPerSecond.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.start(lifecycleOwner, previewView)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        TapToSelectOverlay { left, top, right, bottom, w, h ->
            val frame = viewModel.latestFrame.value ?: return@TapToSelectOverlay
            viewModel.tapToSelectHandler.onBoxSelected(frame, left, top, right, bottom, w, h)
        }

        TrackedBoxOverlay(trackingResult)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.5f))
                    .padding(8.dp),
            ) {
                Text("VISION TEST — phone camera, no drone", color = Color.Yellow, style = MaterialTheme.typography.labelLarge)
                Text("Drag a box around a subject to start tracking", color = Color.White)
                Text("FPS: ${"%.1f".format(fps)}   State: ${trackingResult.label()}", color = Color.White)
            }

            Button(onClick = onBack, modifier = Modifier.align(Alignment.Start)) {
                Text("Back")
            }
        }
    }
}

@Composable
private fun TrackedBoxOverlay(trackingResult: TrackingResult) {
    val box: BoundingBox? = when (trackingResult) {
        is TrackingResult.Tracking -> trackingResult.box
        is TrackingResult.Lost -> trackingResult.lastKnownBox
        TrackingResult.NotStarted -> null
    }
    val color = when (trackingResult) {
        is TrackingResult.Tracking -> Color.Green
        is TrackingResult.Lost -> Color.Red
        TrackingResult.NotStarted -> Color.Transparent
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        if (box == null) return@Canvas
        val left = (box.centerX - box.width / 2f) * size.width
        val top = (box.centerY - box.height / 2f) * size.height
        drawRect(
            color = color,
            topLeft = Offset(left, top),
            size = Size(box.width * size.width, box.height * size.height),
            style = Stroke(width = 4.dp.toPx()),
        )
    }
}

private fun TrackingResult.label(): String = when (this) {
    TrackingResult.NotStarted -> "not started"
    is TrackingResult.Tracking -> "tracking (${"%.2f".format(confidence)})"
    is TrackingResult.Lost -> "lost (${framesSinceSeen} frames)"
}

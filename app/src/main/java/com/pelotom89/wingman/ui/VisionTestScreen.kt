package com.pelotom89.wingman.ui

import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pelotom89.wingman.vision.BoundingBox
import com.pelotom89.wingman.vision.Detection
import com.pelotom89.wingman.vision.TrackingResult

/**
 * Standalone vision-pipeline test screen — phone's own camera, no DJI connection, no
 * flight control. Exists to make Milestone 2 ("vision pipeline standalone... check
 * latency/frame-drop against live decode load") actually testable before any drone
 * hardware is available. Reachable from PreflightChecklistScreen independent of the
 * DJI-gated "Begin flight" flow.
 *
 * Selection flow: auto-detects people continuously and shows a box around each one
 * ([CandidateDetectionsOverlay]) — tap the one you want, no drag-a-box gesture needed.
 * Replaced the earlier drag-to-select flow after user feedback that it was unnecessary
 * friction when the detector already knows where the people are.
 */
@Composable
fun VisionTestScreen(onBack: () -> Unit, viewModel: VisionTestViewModel = viewModel()) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }

    val trackingResult by viewModel.trackingResult.collectAsStateWithLifecycle()
    val candidates by viewModel.candidateDetections.collectAsStateWithLifecycle()
    val fps by viewModel.framesPerSecond.collectAsStateWithLifecycle()
    val notYetSelected = trackingResult is TrackingResult.NotStarted

    // DisposableEffect, not LaunchedEffect(Unit): this screen is reachable multiple times
    // per app session (Preflight <-> VisionTest isn't a real back-stack, just a local
    // `screen` toggle in MainActivity), and the ViewModel is Activity-scoped rather than
    // screen-scoped -- so the camera needs an explicit stop() on leaving, or it keeps
    // running against a PreviewView nobody can see (see VisionTestViewModel.start's
    // header comment for the black-screen bug this fixes).
    DisposableEffect(lifecycleOwner, previewView) {
        viewModel.start(lifecycleOwner, previewView)
        onDispose { viewModel.stop() }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        if (notYetSelected) {
            // Tap-to-select only listens while nobody's been picked yet -- once tracking
            // starts, taps shouldn't accidentally re-seed a different subject.
            //
            // Keyed on Unit, NOT candidates: keying on the candidate list (which gets a new
            // instance every ~3 frames during pre-selection) restarted this pointerInput
            // effect constantly, cancelling and re-launching the gesture detector several
            // times a second -- a real tap could easily land in that restart gap and get
            // silently dropped (confirmed on-device: a tap squarely inside the drawn
            // candidate box did nothing). onScreenTapped reads the ViewModel's current
            // candidates at call time, so there was never a need to key on them at all.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            viewModel.onScreenTapped(offset.x, offset.y, size.width.toFloat(), size.height.toFloat())
                        }
                    },
            )
            CandidateDetectionsOverlay(candidates)
        } else {
            TrackedBoxOverlay(trackingResult)
        }

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
                Text(
                    if (notYetSelected) "Tap a detected person to track them" else "State: ${trackingResult.label()}",
                    color = Color.White,
                )
                Text("FPS: ${"%.1f".format(fps)}", color = Color.White)
            }

            Button(onClick = onBack, modifier = Modifier.align(Alignment.Start)) {
                Text("Back")
            }
        }
    }
}

/** Boxes around every live "person" detection before a subject is picked — the thing to
 *  tap. Orange, distinct from the tracked box's green/red, so it reads as "candidate,"
 *  not "locked on." */
@Composable
private fun CandidateDetectionsOverlay(candidates: List<Detection>) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        candidates.forEach { detection ->
            val box = detection.box
            val left = (box.centerX - box.width / 2f) * size.width
            val top = (box.centerY - box.height / 2f) * size.height
            drawRect(
                color = Color(0xFFFFA500), // orange
                topLeft = Offset(left, top),
                size = Size(box.width * size.width, box.height * size.height),
                style = Stroke(width = 3.dp.toPx()),
            )
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

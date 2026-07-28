package com.pelotom89.wingman.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import com.pelotom89.wingman.vision.DetectedSubject

/**
 * Diagnostic overlay for Phase 1 vision: draws the detected person boxes (normalized [0,1]
 * frame coords) over the camera preview so detection can be validated on the ground before
 * it drives anything. Assumes the preview fills this composable at the frame's aspect ratio;
 * good enough for a diagnostic (exact letterbox mapping matters only once it drives framing).
 */
@Composable
fun DetectionOverlay(detections: List<DetectedSubject>, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.fillMaxSize()) {
        detections.forEach { d ->
            val left = d.left * size.width
            val top = d.top * size.height
            val w = (d.right - d.left) * size.width
            val h = (d.bottom - d.top) * size.height
            drawRect(
                color = Color(0xFF00E676),
                topLeft = Offset(left, top),
                size = Size(w, h),
                style = Stroke(width = 4f),
            )
        }
    }
}

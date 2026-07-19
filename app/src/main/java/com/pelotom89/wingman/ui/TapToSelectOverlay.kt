package com.pelotom89.wingman.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * Drag-a-box gesture over the live preview, mirroring ActiveTrack's own tap-a-box UX (see
 * the plan) despite the tracking underneath being custom rather than DJI's. On release,
 * hands the box (in this composable's own pixel size) to [onBoxSelected], which is
 * expected to be wired to TapToSelectHandler.onBoxSelected via the current video frame.
 */
@Composable
fun TapToSelectOverlay(
    onBoxSelected: (left: Float, top: Float, right: Float, bottom: Float, widthPx: Float, heightPx: Float) -> Unit,
) {
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragCurrent by remember { mutableStateOf<Offset?>(null) }
    var canvasSize by remember { mutableStateOf(Offset(1f, 1f)) }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset -> dragStart = offset; dragCurrent = offset },
                    onDrag = { change, _ -> dragCurrent = change.position },
                    onDragEnd = {
                        val start = dragStart
                        val end = dragCurrent
                        if (start != null && end != null) {
                            onBoxSelected(
                                minOf(start.x, end.x),
                                minOf(start.y, end.y),
                                maxOf(start.x, end.x),
                                maxOf(start.y, end.y),
                                canvasSize.x,
                                canvasSize.y,
                            )
                        }
                        dragStart = null
                        dragCurrent = null
                    },
                )
            },
    ) {
        canvasSize = Offset(size.width, size.height)
        val start = dragStart
        val end = dragCurrent
        if (start != null && end != null) {
            drawRect(
                color = Color.Green,
                topLeft = Offset(minOf(start.x, end.x), minOf(start.y, end.y)),
                size = Size(kotlin.math.abs(end.x - start.x), kotlin.math.abs(end.y - start.y)),
                style = Stroke(width = 3.dp.toPx()),
            )
        }
    }
}

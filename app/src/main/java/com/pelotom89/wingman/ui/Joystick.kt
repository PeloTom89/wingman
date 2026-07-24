package com.pelotom89.wingman.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Spring-to-center touch joystick. Reports normalized x/y in [-1, 1] (screen convention:
 * +x right, +y DOWN) via [onChange] continuously while dragging, and (0, 0) on release --
 * callers convert to the app's body-frame command convention (pitch+/roll+/yaw+/vertical+ =
 * forward/right/clockwise/up) rather than this widget assuming one, since the two instances
 * on the video test screen (yaw+vertical, pitch+roll) use their axes differently.
 */
@Composable
fun Joystick(
    modifier: Modifier = Modifier,
    size: Dp = 140.dp,
    onChange: (x: Float, y: Float) -> Unit,
) {
    var knobOffset by remember { mutableStateOf(Offset.Zero) }
    val radiusPx = with(LocalDensity.current) { (size / 2).toPx() }

    Box(
        modifier = modifier
            .size(size)
            .background(Color.White.copy(alpha = 0.15f), CircleShape)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val raw = knobOffset + dragAmount
                        val distance = raw.getDistance()
                        knobOffset = if (distance > radiusPx) raw * (radiusPx / distance) else raw
                        onChange(knobOffset.x / radiusPx, knobOffset.y / radiusPx)
                    },
                    onDragEnd = {
                        knobOffset = Offset.Zero
                        onChange(0f, 0f)
                    },
                    onDragCancel = {
                        knobOffset = Offset.Zero
                        onChange(0f, 0f)
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .offset { IntOffset(knobOffset.x.roundToInt(), knobOffset.y.roundToInt()) }
                .size(size / 3)
                .background(Color.White.copy(alpha = 0.7f), CircleShape),
        )
    }
}

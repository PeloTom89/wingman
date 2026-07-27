package com.pelotom89.wingman.ui

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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

private const val TAG = "WingmanJoystick"

/**
 * Spring-to-center touch joystick. Reports normalized x/y in [-1, 1] (screen convention:
 * +x right, +y DOWN) via [onChange] while a finger is down, and (0, 0) on release --
 * callers convert to the app's body-frame command convention (pitch+/roll+/yaw+/vertical+ =
 * forward/right/clockwise/up) rather than this widget assuming one, since the two instances
 * on the video test screen (yaw+vertical, pitch+roll) use their axes differently.
 *
 * ABSOLUTE-POSITION tracking, rewritten 2026-07-27 (replaced a `detectDragGestures`
 * delta-accumulation implementation) after a real flight test where holding the right stick
 * produced the aircraft "rocking back and forth." Two problems with the old approach, both
 * confirmed in the flight log:
 *  1. It reported DRAG DELTAS accumulated from the touch-down point, so the commanded value
 *     depended on the finger's drag PATH, not its current position -- "how far out from
 *     center = speed" (the actual desired behavior) wasn't what it computed.
 *  2. Every `onDragEnd` (each genuine finger lift, which the log showed happening every
 *     1-3s during what the operator experienced as a continuous hold) snapped the command to
 *     a hard zero, then the next touch surged it back to full -- a surge/stop/surge cycle
 *     that IS the observed rocking. Peaks hit ~3 m/s then 0 then ~3 repeatedly.
 * This version tracks the finger's ABSOLUTE position relative to the pad center every event,
 * from touch-down through release: touch-down anywhere immediately gives a proportional
 * command (no touch-slop dead zone), holding the finger still holds a rock-steady command,
 * and the command only zeros on a genuine finger-up. It matches the operator's own mental
 * model exactly ("how far I move the joystick out from center controls the speed").
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
                val centerPx = this.size.width / 2f
                val center = Offset(centerPx, centerPx)

                // Clamp the finger position to the pad radius and report it as the deflection.
                fun report(position: Offset) {
                    val raw = position - center
                    val distance = raw.getDistance()
                    knobOffset = if (distance > radiusPx) raw * (radiusPx / distance) else raw
                    onChange(knobOffset.x / radiusPx, knobOffset.y / radiusPx)
                }

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    Log.i(TAG, "touch down")
                    report(down.position)
                    // Follow this pointer until it lifts. Absolute position each event, so a
                    // stationary finger holds a constant command and there's no surge/zero
                    // flicker.
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        change.consume()
                        report(change.position)
                    }
                    Log.i(TAG, "touch up")
                    knobOffset = Offset.Zero
                    onChange(0f, 0f)
                }
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

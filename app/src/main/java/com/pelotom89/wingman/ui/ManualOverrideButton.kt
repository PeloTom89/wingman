package com.pelotom89.wingman.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Always rendered on top, single tap, no confirmation dialog — per the plan, a
 * confirmation step on a stop control is actively dangerous. Wired to
 * WingmanViewModel.onStopPressed: exits following and releases VirtualStick so the aircraft
 * hovers and the RC (or, after re-enabling manual, the virtual sticks) can fly it.
 */
@Composable
fun ManualOverrideButton(onPressed: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onPressed,
        modifier = modifier
            .padding(16.dp)
            .size(width = 160.dp, height = 64.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
    ) {
        Text("STOP", color = Color.White)
    }
}

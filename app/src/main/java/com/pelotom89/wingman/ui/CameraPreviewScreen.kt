package com.pelotom89.wingman.ui

import android.view.TextureView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.interfaces.ICameraStreamManager
import dji.sdk.keyvalue.value.common.ComponentIndexType

/**
 * Renders the live feed via DJI's own camera-stream-to-Surface path (efficient native
 * decode-to-display), separate from sdk/VideoFeedRepository's raw frame listener that
 * feeds the vision pipeline — both read the same underlying stream without forcing a
 * second software decode for display purposes.
 */
@Composable
fun CameraPreviewScreen(cameraIndex: ComponentIndexType, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            TextureView(context).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(surfaceTexture: android.graphics.SurfaceTexture, width: Int, height: Int) {
                        val surface = android.view.Surface(surfaceTexture)
                        MediaDataCenter.getInstance().cameraStreamManager
                            .putCameraStreamSurface(cameraIndex, surface, width, height, ICameraStreamManager.ScaleType.CENTER_INSIDE)
                    }

                    override fun onSurfaceTextureSizeChanged(surfaceTexture: android.graphics.SurfaceTexture, width: Int, height: Int) = Unit

                    override fun onSurfaceTextureDestroyed(surfaceTexture: android.graphics.SurfaceTexture): Boolean {
                        MediaDataCenter.getInstance().cameraStreamManager.removeCameraStreamSurface(android.view.Surface(surfaceTexture))
                        return true
                    }

                    override fun onSurfaceTextureUpdated(surfaceTexture: android.graphics.SurfaceTexture) = Unit
                }
            }
        },
    )

    DisposableEffect(Unit) {
        onDispose { /* surface cleanup handled in onSurfaceTextureDestroyed above */ }
    }
}

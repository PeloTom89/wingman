package com.pelotom89.wingman.ui

import android.util.Log
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
 * Renders the aircraft's live camera feed via DJI's own camera-stream-to-Surface path
 * (efficient native decode-to-display) — purely for the operator's situational awareness.
 * GPS-only following (see README) reads nothing from this stream; it's display-only.
 *
 * IMPORTANT, discovered via decompiling three working third-party MSDK V5 apps
 * (Dronelink, Litchi Pilot, Maven EVO — 2026-07-22, real APKs pulled off the same test
 * device, decompiled with jadx and cross-checked against the real 5.18.0 jar): all three
 * bind `MediaDataCenter.getInstance().cameraStreamManager.putCameraStreamSurface(...)`
 * completely unconditionally, the moment their video Surface exists — never gated behind
 * `FlightControllerKey.KeyConnection` or any other "is the aircraft link up yet" check.
 * DJI's own official UXSDK `FPVWidget` (bundled inside Maven EVO's APK, source fully
 * decompiled) does the same in its `onSurfaceTextureAvailable`. This is the one concrete
 * code-level difference found after also checking SDKManager/KeyManager/registerApp
 * timing, manifest USB-accessory declarations, and DJI's UXSDK base classes — none of
 * which differed from what Wingman already does. Mechanistically plausible: MSDK V5
 * multiplexes video and FlightController telemetry over the same RC<->aircraft radio
 * link, and requesting the video stream is a much more aggressive, native-level "give me
 * the link" call than KeyManager's lazy per-key subscription — it may be what nudges the
 * RC-N3's firmware to complete the aircraft-side handshake in the issue-#427 stalled
 * state. UNPROVEN but consistent with all three working apps and with none of them
 * un-sticking Wingman's OWN state afterward (this is local-to-the-requesting-process
 * behavior, not a persistent RC-firmware side effect). This is why MainActivity now
 * composes [CameraPreviewScreen] unconditionally behind BOTH screens instead of only
 * inside the post-connection Flight screen — see MainActivity's header comment.
 */
private const val TAG = "WingmanCameraStream"

@Composable
fun CameraPreviewScreen(cameraIndex: ComponentIndexType, modifier: Modifier = Modifier) {
    // TEMP diagnostic (2026-07-22): comprehensive timestamped logging to establish exactly
    // when each stage of the camera-stream request happens relative to onProductConnect /
    // FlightControllerKey.KeyConnection, and whether video is REALLY flowing (onReceiveStream
    // firing) vs. just requested (putCameraStreamSurface called). Also now calls
    // enableStream(cameraIndex, true) explicitly and setKeepAliveDecoding(true) -- both real
    // ICameraStreamManager methods (verified via javap against the real 5.18.0 jar) that
    // Wingman never called before; the earlier competitor-app decompilation only found
    // putCameraStreamSurface, not these, so they were never tried. Remove/simplify once the
    // connection-reliability question is settled.
    DisposableEffect(Unit) {
        val streamManager = MediaDataCenter.getInstance().cameraStreamManager
        Log.i(TAG, "composed, calling enableStream(true)")
        streamManager.enableStream(cameraIndex, true)
        streamManager.setKeepAliveDecoding(true)

        var receivedFirstFrame = false
        val receiveListener = ICameraStreamManager.ReceiveStreamListener { data, offset, length, streamInfo ->
            if (!receivedFirstFrame) {
                receivedFirstFrame = true
                Log.i(TAG, "onReceiveStream FIRST FRAME: length=$length streamInfo=$streamInfo")
            }
        }
        streamManager.addReceiveStreamListener(cameraIndex, receiveListener)

        val availableCameraListener = object : ICameraStreamManager.AvailableCameraUpdatedListener {
            override fun onAvailableCameraUpdated(availableCameraList: MutableList<ComponentIndexType>) {
                Log.i(TAG, "onAvailableCameraUpdated: $availableCameraList")
            }

            override fun onCameraStreamEnableUpdate(cameraStreamEnableMap: MutableMap<ComponentIndexType, Boolean>) {
                Log.i(TAG, "onCameraStreamEnableUpdate: $cameraStreamEnableMap")
            }
        }
        streamManager.addAvailableCameraUpdatedListener(availableCameraListener)

        onDispose {
            streamManager.removeReceiveStreamListener(receiveListener)
            streamManager.removeAvailableCameraUpdatedListener(availableCameraListener)
        }
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            Log.i(TAG, "TextureView factory invoked")
            TextureView(context).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(surfaceTexture: android.graphics.SurfaceTexture, width: Int, height: Int) {
                        Log.i(TAG, "onSurfaceTextureAvailable ${width}x$height, calling putCameraStreamSurface")
                        val surface = android.view.Surface(surfaceTexture)
                        MediaDataCenter.getInstance().cameraStreamManager
                            .putCameraStreamSurface(cameraIndex, surface, width, height, ICameraStreamManager.ScaleType.CENTER_INSIDE)
                        Log.i(TAG, "putCameraStreamSurface returned")
                    }

                    override fun onSurfaceTextureSizeChanged(surfaceTexture: android.graphics.SurfaceTexture, width: Int, height: Int) = Unit

                    override fun onSurfaceTextureDestroyed(surfaceTexture: android.graphics.SurfaceTexture): Boolean {
                        Log.i(TAG, "onSurfaceTextureDestroyed")
                        MediaDataCenter.getInstance().cameraStreamManager.removeCameraStreamSurface(android.view.Surface(surfaceTexture))
                        return true
                    }

                    override fun onSurfaceTextureUpdated(surfaceTexture: android.graphics.SurfaceTexture) = Unit
                }
            }
        },
    )
}

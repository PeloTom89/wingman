package com.pelotom89.wingman.sdk

import android.graphics.Bitmap
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.interfaces.ICameraStreamManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.buffer

/**
 * Single decode point for the live video feed, shared by the UI preview surface and the
 * vision/ pipeline so the phone isn't decoding the same stream twice — a real concern
 * given decode + inference are already competing for the same GPU/NPU budget (see the
 * detect-then-track cadence tradeoff in vision/SubjectDetector.kt).
 *
 * Buffers only the latest frame (BufferOverflow.DROP_OLDEST): if vision inference or the
 * UI falls behind, we want the freshest frame next tick, not a backlog of stale ones.
 *
 * CORRECTED against the real MSDK V5 jar: `addFrameListener` takes a single
 * `FrameFormat` (not a `StreamInfo` — that class is unrelated metadata, not used by this
 * call at all), and `CameraFrameListener.onFrame` is a 6-arg callback
 * (data, offset, length, width, height, format).
 */
class VideoFeedRepository(private val cameraIndex: dji.sdk.keyvalue.value.common.ComponentIndexType) {

    val frameFlow: Flow<VideoFrame> = callbackFlow {
        val listener = ICameraStreamManager.CameraFrameListener { frameData, offset, length, width, height, format ->
            trySend(VideoFrame(frameData, offset, length, width, height, format))
        }
        MediaDataCenter.getInstance().cameraStreamManager
            .addFrameListener(cameraIndex, ICameraStreamManager.FrameFormat.NV21, listener)
        awaitClose {
            MediaDataCenter.getInstance().cameraStreamManager.removeFrameListener(listener)
        }
    }.buffer(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
}

/** Raw decoded frame; vision/SubjectDetector.kt converts this to whatever tensor/Bitmap
 *  shape the on-device model needs rather than this layer assuming one consumer's format. */
data class VideoFrame(
    val data: ByteArray,
    val offset: Int,
    val length: Int,
    val widthPx: Int,
    val heightPx: Int,
    val format: ICameraStreamManager.FrameFormat,
)

fun VideoFrame.toBitmapOrNull(): Bitmap? {
    // NV21 requested explicitly above (see addFrameListener call), so this should always
    // be android.graphics.YuvImage-convertible via NV21 -> JPEG -> Bitmap, or a direct
    // RenderScript/GPU YUV->RGB conversion for lower latency. Not yet implemented — see
    // README's known gaps; wire this before vision/SubjectDetector.kt can consume frames.
    return null
}

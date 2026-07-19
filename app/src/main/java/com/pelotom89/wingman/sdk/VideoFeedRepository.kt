package com.pelotom89.wingman.sdk

import android.graphics.Bitmap
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.datacenter.camera.StreamInfo
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
 * NOTE: ICameraStreamManager/MediaDataCenter shape per DJI's documented MSDK V5 camera
 * stream pattern; verify against the pinned SDK version's API reference at first build.
 */
class VideoFeedRepository(private val cameraIndex: dji.sdk.keyvalue.value.common.ComponentIndexType) {

    val frameFlow: Flow<VideoFrame> = callbackFlow {
        val listener = ICameraStreamManager.CameraFrameListener { frameData, offset, length, width, height, format, _ ->
            trySend(VideoFrame(frameData, offset, length, width, height, format))
        }
        MediaDataCenter.getInstance().cameraStreamManager.addFrameListener(cameraIndex, StreamInfo.DEFAULT_STREAM_TYPE, listener)
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
    val format: Int,
)

fun VideoFrame.toBitmapOrNull(): Bitmap? {
    // Placeholder conversion point: the real implementation depends on which `format`
    // MSDK V5 actually delivers (commonly NV21/YUV420) — wire through android.graphics.YuvImage
    // or a GPU-backed converter once the pinned SDK version's frame format is confirmed.
    return null
}

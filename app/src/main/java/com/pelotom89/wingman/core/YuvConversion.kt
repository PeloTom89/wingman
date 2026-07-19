package com.pelotom89.wingman.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import java.io.ByteArrayOutputStream

/**
 * Shared by sdk/VideoFeedRepository.kt (DJI aircraft camera stream, requests NV21 directly)
 * and vision/PhoneCameraFrameSource.kt (CameraX ImageAnalysis, which delivers YUV_420_888
 * as separate planes that get interleaved into NV21 before reaching here) — both frame
 * sources ultimately need "NV21 bytes -> Bitmap" and there's no reason to duplicate the
 * JPEG-round-trip logic.
 *
 * Simple and correct, not the fastest path available (a direct YUV->RGB conversion would
 * skip the JPEG encode/decode) — acceptable since both callers already only sample a
 * fraction of frames for detection (see SubjectDetector's cadence comment); revisit if
 * profiling on a real device shows this is the actual bottleneck rather than inference.
 */
fun nv21ToBitmapOrNull(nv21: ByteArray, width: Int, height: Int): Bitmap? {
    if (width <= 0 || height <= 0 || nv21.isEmpty()) return null

    val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
    val jpegBytes = ByteArrayOutputStream().use { out ->
        val ok = yuvImage.compressToJpeg(Rect(0, 0, width, height), JPEG_QUALITY, out)
        if (!ok) return null
        out.toByteArray()
    }
    return BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
}

private const val JPEG_QUALITY = 90

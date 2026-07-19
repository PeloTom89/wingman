package com.pelotom89.wingman.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.pelotom89.wingman.core.nv21ToBitmapOrNull
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/**
 * Phone's own camera (via CameraX), used ONLY by the standalone VisionTestScreen so the
 * detect/track pipeline (vision/SubjectDetector.kt, SubjectTracker.kt) is exercisable
 * without a DJI aircraft connected — the real flight path reads frames from
 * sdk/VideoFeedRepository (the DJI aircraft camera stream) instead, which requires the
 * drone powered on. This class has no DJI dependency at all.
 *
 * Binds a `Preview` use case (for on-screen display via the caller's [PreviewView]) and an
 * `ImageAnalysis` use case (for frame capture) to the same camera session, so the displayed
 * feed and the frames handed to the vision pipeline are the same stream, not double-decoded.
 */
class PhoneCameraFrameSource(private val context: Context) {

    private val analysisExecutor = Executors.newSingleThreadExecutor()

    /**
     * Emits decoded frames until the collecting coroutine is cancelled, at which point the
     * camera is unbound. Buffers only the latest frame (DROP_OLDEST) so a slow consumer
     * (detector inference) doesn't build up a backlog — same rationale as
     * sdk/VideoFeedRepository's identical buffering choice.
     */
    fun frameFlow(lifecycleOwner: LifecycleOwner, previewView: PreviewView?): Flow<Bitmap> = callbackFlow {
        // Set asynchronously once ProcessCameraProvider resolves; awaitClose below runs on
        // this producer coroutine's cancellation and must be the ONLY awaitClose call in
        // this callbackFlow block — it can't be nested inside the listener lambda, which
        // isn't a suspend context.
        var boundProvider: ProcessCameraProvider? = null

        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener(
            {
                val provider = providerFuture.get()
                boundProvider = provider

                val preview = Preview.Builder().build().apply {
                    previewView?.surfaceProvider?.let { setSurfaceProvider(it) }
                }

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .apply {
                        setAnalyzer(analysisExecutor) { imageProxy ->
                            imageProxy.toBitmapOrNull()?.let { trySend(it) }
                            imageProxy.close()
                        }
                    }

                try {
                    provider.unbindAll()
                    provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
                } catch (e: IllegalStateException) {
                    close(e) // e.g. lifecycleOwner already destroyed by the time binding runs
                }
            },
            ContextCompat.getMainExecutor(context),
        )

        awaitClose { boundProvider?.unbindAll() }
    }.buffer(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    fun close() = analysisExecutor.shutdown()
}

/**
 * CameraX's ImageAnalysis delivers YUV_420_888 (three separate Y/U/V planes, each with its
 * own row/pixel stride — NOT the interleaved NV21 layout DJI's stream uses directly), so
 * this interleaves them into NV21 before reusing the same [nv21ToBitmapOrNull] path as
 * sdk/VideoFeedRepository. Stride-aware (doesn't assume pixelStride == 1 on the chroma
 * planes, which isn't true on all devices).
 *
 * CRITICAL: also rotates (via [ImageProxy.imageInfo]'s `rotationDegrees`) and horizontally
 * mirrors the result to match what [PreviewView] actually displays. ImageAnalysis buffers
 * come back in the sensor's raw orientation — `PreviewView` applies the needed rotation/
 * mirroring internally for on-screen display, but that transform is invisible to this
 * separate analysis stream, so without reapplying it here the tracker/tap-to-select
 * coordinates silently drift out of sync with the visible feed as the phone is
 * tilted/rotated (confirmed on-device: tilting the phone left/right visibly detached the
 * tracked box from the actual face). Front camera specifically needs the mirror to match
 * PreviewView's "selfie" mirroring — [CameraSelector.DEFAULT_FRONT_CAMERA] is hardcoded
 * above, so the mirror is unconditional here; revisit together if the camera selector ever
 * becomes configurable.
 */
private fun ImageProxy.toBitmapOrNull(): Bitmap? {
    if (planes.size < 3) return null
    val nv21 = ByteArray(width * height * 3 / 2)
    var pos = 0

    val yPlane = planes[0]
    val yBuffer = yPlane.buffer
    for (row in 0 until height) {
        yBuffer.copyRow(nv21, pos, row * yPlane.rowStride, width)
        pos += width
    }

    val uPlane = planes[1]
    val vPlane = planes[2]
    val chromaHeight = height / 2
    val chromaWidth = width / 2
    for (row in 0 until chromaHeight) {
        for (col in 0 until chromaWidth) {
            val vIndex = row * vPlane.rowStride + col * vPlane.pixelStride
            val uIndex = row * uPlane.rowStride + col * uPlane.pixelStride
            nv21[pos++] = vPlane.buffer.get(vIndex)
            nv21[pos++] = uPlane.buffer.get(uIndex)
        }
    }

    val raw = nv21ToBitmapOrNull(nv21, width, height) ?: return null
    return raw.rotatedAndMirroredToMatchPreview(imageInfo.rotationDegrees)
}

private fun Bitmap.rotatedAndMirroredToMatchPreview(rotationDegrees: Int): Bitmap {
    // Two explicit steps rather than one combined Matrix: rotation can swap width/height,
    // and getting the mirror's pivot right pre- vs post-rotation is easy to get backwards.
    // Doing it in two Bitmap.createBitmap calls lets each step use the CURRENT bitmap's own
    // (already-correct) dimensions rather than hand-computing rotated bounds.
    val rotated = if (rotationDegrees == 0) {
        this
    } else {
        val rotateMatrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        Bitmap.createBitmap(this, 0, 0, width, height, rotateMatrix, true)
    }
    val mirrorMatrix = Matrix().apply { postScale(-1f, 1f, rotated.width / 2f, rotated.height / 2f) }
    return Bitmap.createBitmap(rotated, 0, 0, rotated.width, rotated.height, mirrorMatrix, true)
}

private fun ByteBuffer.copyRow(dest: ByteArray, destOffset: Int, sourceOffset: Int, length: Int) {
    val duplicate = duplicate() // avoid mutating the shared buffer's position across rows
    duplicate.position(sourceOffset)
    duplicate.get(dest, destOffset, length)
}

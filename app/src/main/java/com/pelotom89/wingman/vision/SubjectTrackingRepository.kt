package com.pelotom89.wingman.vision

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.interfaces.ICameraStreamManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.nio.ByteBuffer

private const val TAG = "WingmanVision"

/**
 * Phase 1 detection pipeline: pulls decoded frames from the aircraft camera
 * (ICameraStreamManager.addFrameListener, RGBA_8888) and runs [SubjectDetector] on a
 * throttled subset, publishing normalized person boxes as [detections].
 *
 * This is the GROUND-TESTABLE half of vision tracking -- point the aircraft camera at a
 * person and the boxes should appear on the preview (see DetectionOverlay), with zero flight
 * involved. It validates the two Phase-1 unknowns before anything drives the gimbal:
 * (1) MediaPipe coexists with the SecNeo-protected DJI runtime, (2) the budget phone can run
 * detection fast enough without starving the flight command loop. The GPS-prior gating and
 * the gimbal-framing hookup come only after this is proven.
 *
 * Frames arrive on DJI's decode thread; the byte[] is reused after onFrame returns, so we
 * copy into a Bitmap synchronously (fast) and run the (heavier) detection async on
 * Dispatchers.Default. A busy flag + min-interval throttle drop frames we can't keep up with
 * rather than queueing latency.
 */
class SubjectTrackingRepository(private val context: Context) {

    private var detector: SubjectDetector? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _detections = MutableStateFlow<List<DetectedSubject>>(emptyList())
    val detections: StateFlow<List<DetectedSubject>> = _detections.asStateFlow()

    /** The one box we treat as THE subject to frame on. For now the largest person (nearest /
     *  main subject) -- GPS gating (getLiveViewLocationWithGPS, pick the box nearest the
     *  subject's projected position) is the planned refinement for the multi-person case, and
     *  slots in right here. Null when nobody is detected. */
    private val _selectedSubject = MutableStateFlow<DetectedSubject?>(null)
    val selectedSubject: StateFlow<DetectedSubject?> = _selectedSubject.asStateFlow()

    private fun DetectedSubject.area() = (right - left) * (bottom - top)

    /** Rolling last inference time (ms), for the on-screen perf readout. */
    private val _lastInferenceMs = MutableStateFlow(0L)
    val lastInferenceMs: StateFlow<Long> = _lastInferenceMs.asStateFlow()

    @Volatile private var busy = false
    private var lastStartMs = 0L
    private var reusableBitmap: Bitmap? = null

    private val frameListener = ICameraStreamManager.CameraFrameListener { data, offset, length, width, height, _ ->
        val now = SystemClock.uptimeMillis()
        if (busy || now - lastStartMs < MIN_INTERVAL_MS || width <= 0 || height <= 0) return@CameraFrameListener
        lastStartMs = now
        busy = true

        // Copy the reused DJI buffer into a Bitmap synchronously, before it's recycled.
        val bitmap = (reusableBitmap?.takeIf { it.width == width && it.height == height }
            ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { reusableBitmap = it })
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(data, offset, length))

        scope.launch {
            try {
                val start = SystemClock.uptimeMillis()
                val boxes = detector?.detect(bitmap) ?: emptyList()
                _lastInferenceMs.value = SystemClock.uptimeMillis() - start
                _detections.value = boxes
                _selectedSubject.value = boxes.maxByOrNull { it.area() }
            } catch (t: Throwable) {
                Log.w(TAG, "detection failed", t)
            } finally {
                busy = false
            }
        }
    }

    fun start(cameraIndex: ComponentIndexType) {
        if (detector == null) {
            detector = try {
                SubjectDetector(context).also { Log.i(TAG, "SubjectDetector initialized") }
            } catch (t: Throwable) {
                Log.e(TAG, "SubjectDetector init FAILED", t)
                null
            }
        }
        MediaDataCenter.getInstance().cameraStreamManager.addFrameListener(
            cameraIndex,
            ICameraStreamManager.FrameFormat.RGBA_8888,
            frameListener,
        )
        Log.i(TAG, "frame listener registered on $cameraIndex")
    }

    fun stop() {
        MediaDataCenter.getInstance().cameraStreamManager.removeFrameListener(frameListener)
        _detections.value = emptyList()
        _selectedSubject.value = null
    }

    fun close() {
        stop()
        scope.cancel()
        detector?.close()
        detector = null
        reusableBitmap = null
    }

    private companion object {
        /** ~6-7Hz max; the detector can't sustain the full stream rate on a budget phone and
         *  framing doesn't need it. Tune once measured on-device. */
        const val MIN_INTERVAL_MS = 150L
    }
}

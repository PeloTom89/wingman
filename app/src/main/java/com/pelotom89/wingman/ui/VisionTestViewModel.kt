package com.pelotom89.wingman.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.camera.view.PreviewView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import com.pelotom89.wingman.vision.PhoneCameraFrameSource
import com.pelotom89.wingman.vision.SubjectDetector
import com.pelotom89.wingman.vision.SubjectTracker
import com.pelotom89.wingman.vision.TapToSelectHandler
import com.pelotom89.wingman.vision.TemplateMatchBoxTracker
import com.pelotom89.wingman.vision.TrackingResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Composition root for the standalone vision test screen — deliberately independent of
 * [WingmanViewModel]/[com.pelotom89.wingman.flightcontrol.FlightStateMachine]: this exists
 * specifically to exercise vision/SubjectDetector.kt + SubjectTracker.kt in isolation (per
 * the plan's Milestone 2), using the phone's own camera instead of the DJI aircraft stream,
 * with no flight-control machinery involved at all.
 */
class VisionTestViewModel(application: Application) : AndroidViewModel(application) {

    private val subjectDetector = SubjectDetector(application)
    private val subjectTracker = SubjectTracker(subjectDetector, TemplateMatchBoxTracker())
    val tapToSelectHandler = TapToSelectHandler(subjectTracker)

    private val phoneCameraFrameSource = PhoneCameraFrameSource(application)

    private val _latestFrame = MutableStateFlow<Bitmap?>(null)
    val latestFrame: StateFlow<Bitmap?> get() = _latestFrame.asStateFlow()

    private val _trackingResult = MutableStateFlow<TrackingResult>(TrackingResult.NotStarted)
    val trackingResult: StateFlow<TrackingResult> get() = _trackingResult.asStateFlow()

    /** Rolling once-per-second FPS of frames actually reaching the tracker — the thing
     *  Milestone 2 asks to check ("latency/frame-drop against live decode load"). */
    private val _framesPerSecond = MutableStateFlow(0.0)
    val framesPerSecond: StateFlow<Double> get() = _framesPerSecond.asStateFlow()

    private var framesSinceWindowStart = 0
    private var fpsWindowStartMillis = System.currentTimeMillis()
    private var cameraJob: Job? = null

    /**
     * This ViewModel is Activity-scoped (survives Compose navigating away and back — there's
     * no per-screen ViewModelStore here), but the [PreviewView] passed in is recreated fresh
     * every time VisionTestScreen re-enters composition. An earlier version of this method
     * guarded itself with a one-shot "started" flag, which meant the SECOND visit's fresh
     * PreviewView never got bound to the camera at all — the original binding kept running
     * in the background against the now-invisible first PreviewView, so the visible one
     * stayed black. Fixed by cancelling any prior binding and rebinding fresh on every call;
     * see [stop], which VisionTestScreen calls on leaving so the camera doesn't keep running
     * (and draining battery) while the screen isn't shown at all.
     */
    fun start(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        cameraJob?.cancel()
        cameraJob = viewModelScope.launch {
            phoneCameraFrameSource.frameFlow(lifecycleOwner, previewView).collect { frame ->
                _latestFrame.value = frame
                _trackingResult.value = subjectTracker.onFrame(frame)
                recordFrameForFpsWindow()
            }
        }
    }

    /** Unbinds the camera (via the collected flow's cancellation -> awaitClose in
     *  PhoneCameraFrameSource) without shutting down the reusable analysis executor —
     *  that only happens once, in [onCleared]. */
    fun stop() {
        cameraJob?.cancel()
        cameraJob = null
    }

    private fun recordFrameForFpsWindow() {
        framesSinceWindowStart++
        val now = System.currentTimeMillis()
        val elapsed = now - fpsWindowStartMillis
        if (elapsed >= 1000) {
            _framesPerSecond.value = framesSinceWindowStart * 1000.0 / elapsed
            framesSinceWindowStart = 0
            fpsWindowStartMillis = now
        }
    }

    override fun onCleared() {
        super.onCleared()
        subjectDetector.close()
        phoneCameraFrameSource.close()
    }
}

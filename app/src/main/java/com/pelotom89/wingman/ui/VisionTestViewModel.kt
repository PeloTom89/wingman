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
    private var started = false

    /** Idempotent: Compose may recompose/re-enter the screen without this needing to
     *  rebind the camera a second time. */
    fun start(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        if (started) return
        started = true
        viewModelScope.launch {
            phoneCameraFrameSource.frameFlow(lifecycleOwner, previewView).collect { frame ->
                _latestFrame.value = frame
                _trackingResult.value = subjectTracker.onFrame(frame)
                recordFrameForFpsWindow()
            }
        }
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

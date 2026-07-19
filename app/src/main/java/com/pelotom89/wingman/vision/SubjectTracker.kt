package com.pelotom89.wingman.vision

import android.graphics.Bitmap
import kotlin.math.hypot

/**
 * Detect-then-track orchestrator: runs [SubjectDetector] only every [detectionIntervalFrames]
 * frames and relies on a lightweight [BoxTracker] to bridge the gaps — see the plan's
 * rationale (running full detection every frame competes with live video decode for the
 * same GPU/NPU budget on a mid-range phone).
 *
 * The detection-matching math ([matchDetectionToPreviousBox]) is kept as a standalone pure
 * function specifically so it's unit-testable against recorded box sequences without any
 * camera/model dependency, per the plan's testing note.
 */
class SubjectTracker(
    private val detector: SubjectDetector,
    private val boxTracker: BoxTracker,
    private val detectionIntervalFrames: Int = DEFAULT_DETECTION_INTERVAL_FRAMES,
) {
    private var frameCount = 0
    private var lastKnownBox: BoundingBox? = null
    private var framesSinceSeen = 0

    fun seed(frame: Bitmap, box: BoundingBox) {
        lastKnownBox = box
        framesSinceSeen = 0
        frameCount = 0
        boxTracker.init(frame, box)
    }

    fun onFrame(frame: Bitmap): TrackingResult {
        val previousBox = lastKnownBox
        if (previousBox == null) return TrackingResult.NotStarted

        frameCount++
        val runDetectionThisFrame = frameCount % detectionIntervalFrames == 0

        val result: BoundingBox? = if (runDetectionThisFrame) {
            val detections = detector.detectPeople(frame)
            val matched = matchDetectionToPreviousBox(detections, previousBox)
            if (matched != null) {
                boxTracker.init(frame, matched.box) // re-anchor the bridge tracker on fresh detection
                matched.box
            } else {
                boxTracker.update(frame) // detector found nobody matching; fall back to the bridge
            }
        } else {
            boxTracker.update(frame)
        }

        return if (result != null) {
            lastKnownBox = result
            framesSinceSeen = 0
            TrackingResult.Tracking(box = result, confidence = if (runDetectionThisFrame) 1f else 0.5f)
        } else {
            framesSinceSeen++
            TrackingResult.Lost(framesSinceSeen = framesSinceSeen, lastKnownBox = previousBox)
        }
    }

    private companion object {
        const val DEFAULT_DETECTION_INTERVAL_FRAMES = 4 // ~6-10Hz detection at 30fps decode, per plan
    }
}

/**
 * Matches the closest-and-similarly-sized detection to where the subject was last seen —
 * a light appearance/proximity check per the plan, not full re-identification. Pure and
 * synchronous on purpose: this is the piece the plan calls out as unit-testable against
 * recorded box sequences.
 */
fun matchDetectionToPreviousBox(
    detections: List<Detection>,
    previousBox: BoundingBox,
    maxCenterDistance: Float = 0.25f,
    maxSizeRatioDelta: Float = 0.6f,
): Detection? {
    return detections
        .filter { d ->
            val sizeRatio = d.box.area / previousBox.area.coerceAtLeast(0.0001f)
            sizeRatio in (1f - maxSizeRatioDelta)..(1f + maxSizeRatioDelta)
        }
        .minByOrNull { d ->
            hypot((d.box.centerX - previousBox.centerX).toDouble(), (d.box.centerY - previousBox.centerY).toDouble())
        }
        ?.takeIf { d ->
            hypot(
                (d.box.centerX - previousBox.centerX).toDouble(),
                (d.box.centerY - previousBox.centerY).toDouble(),
            ) <= maxCenterDistance
        }
}

/**
 * Bridges the gap between detector calls. NOT a real implementation yet: a production
 * tracker needs actual frame-to-frame motion estimation (e.g. OpenCV CSRT/KCF or
 * Lucas-Kanade optical flow on the box corners/centroid, per the plan) — neither OpenCV
 * nor a hand-rolled optical-flow implementation is wired in yet. [CoastingBoxTracker]
 * below is an honest placeholder (assumes no motion between detections) so the rest of
 * the pipeline has a concrete type to build against; swap it out before Milestone 2
 * testing, since coasting will drift badly on anything but a near-stationary subject.
 */
interface BoxTracker {
    fun init(frame: Bitmap, box: BoundingBox)
    fun update(frame: Bitmap): BoundingBox?
}

class CoastingBoxTracker : BoxTracker {
    private var box: BoundingBox? = null
    override fun init(frame: Bitmap, box: BoundingBox) { this.box = box }
    override fun update(frame: Bitmap): BoundingBox? = box
}

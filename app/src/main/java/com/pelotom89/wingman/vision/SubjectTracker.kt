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
 *
 * Re-acquisition has two modes, gated on [REACQUISITION_RELAXED_AFTER_LOST_FRAMES]: while
 * only briefly lost, matching stays strict (proximity + size vs. the last known box) so a
 * one-frame hiccup doesn't snap onto a different nearby person; once genuinely lost for a
 * while, that proximity constraint is dropped entirely (confirmed on-device it otherwise
 * blocks re-acquisition forever if the subject leaves frame and returns anywhere other
 * than exactly where they were last seen).
 *
 * Tracked box size is fixed at whatever [seed] was called with, for the whole session — a
 * fresh detection's center repositions the box, but never its width/height (see the size
 * comment in [onFrame]'s detector-match branch). Confirmed on-device that adopting a raw
 * detection's own size caused a visible, unwanted resize mid-track.
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
            // Once genuinely lost (not just a brief bridge-frame gap), matchDetectionToPreviousBox's
            // proximity-to-last-known-position gate becomes counterproductive: the subject may have
            // walked fully out of frame and back in somewhere else entirely, and requiring the new
            // detection to land within maxCenterDistance of a now-stale position was blocking
            // re-acquisition indefinitely (confirmed on-device: leaving frame and returning never
            // re-locked). Past the threshold, fall back to the most confident detection with no
            // positional constraint at all.
            val matched = if (framesSinceSeen >= REACQUISITION_RELAXED_AFTER_LOST_FRAMES) {
                detections.maxByOrNull { it.confidence }
            } else {
                matchDetectionToPreviousBox(detections, previousBox)
            }
            if (matched != null) {
                // Keep the box's EXISTING width/height, only take the detection's center —
                // MediaPipe's "person" category covers head/shoulders/torso, not just a
                // face, so adopting a fresh detection's raw box size mid-track caused a
                // visible, unwanted resize the moment a detection (especially in relaxed
                // re-acquisition mode, which has no size constraint at all) matched
                // anything other than an identically-framed box. Confirmed on-device.
                // Matches TemplateMatchBoxTracker's own "position, not scale" philosophy
                // for bridging frames, so detector re-anchoring and bridging now behave
                // consistently: the box holds the size the user actually drew for the
                // whole session, not whatever shape the detector happens to return.
                val recentered = previousBox.copy(centerX = matched.box.centerX, centerY = matched.box.centerY)
                boxTracker.init(frame, recentered) // re-anchor the bridge tracker on fresh detection
                recentered
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

        // ~0.5-0.7s at 30fps / detectionIntervalFrames=4 (roughly 4-5 missed detection
        // cycles) -- long enough that a brief occlusion or one bad detection frame stays
        // in the strict proximity-matched mode (avoids snapping to a different nearby
        // person mid-track), short enough that "walked out of frame and back" doesn't
        // feel broken.
        const val REACQUISITION_RELAXED_AFTER_LOST_FRAMES = 15
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

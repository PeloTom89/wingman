package com.pelotom89.wingman.vision

import android.graphics.Bitmap

/**
 * Two selection flows, both ending in [SubjectTracker.seed]:
 *  - [onBoxSelected]: the UI's drag-a-box gesture (screen pixel coordinates) turned into a
 *    normalized [BoundingBox] directly — mirrors ActiveTrack's own tap-a-box UX. Used by the
 *    real DJI flight path (MainActivity), where there's no live "candidate" detection feed
 *    shown before selection.
 *  - [onDetectionTapped]: a single tap hit-tested against LIVE, currently-detected people
 *    (see VisionTestScreen — auto-detects and shows all candidates, tap the one you want)
 *    rather than the user drawing a box freehand. Added after user feedback that dragging a
 *    box was unnecessary friction when the detector already knows where the people are.
 */
class TapToSelectHandler(private val tracker: SubjectTracker) {

    fun onBoxSelected(
        frame: Bitmap,
        screenLeftPx: Float,
        screenTopPx: Float,
        screenRightPx: Float,
        screenBottomPx: Float,
        screenWidthPx: Float,
        screenHeightPx: Float,
    ) {
        val box = BoundingBox(
            centerX = (screenLeftPx + screenRightPx) / 2f / screenWidthPx,
            centerY = (screenTopPx + screenBottomPx) / 2f / screenHeightPx,
            width = (screenRightPx - screenLeftPx) / screenWidthPx,
            height = (screenBottomPx - screenTopPx) / screenHeightPx,
        )
        tracker.seed(frame, box)
    }

    /** Returns true if the tap landed on a detected candidate and tracking was seeded. */
    fun onDetectionTapped(
        frame: Bitmap,
        candidates: List<Detection>,
        tapXPx: Float,
        tapYPx: Float,
        screenWidthPx: Float,
        screenHeightPx: Float,
    ): Boolean {
        val tapped = findTappedDetection(candidates, tapXPx / screenWidthPx, tapYPx / screenHeightPx) ?: return false
        tracker.seed(frame, tapped.box)
        return true
    }
}

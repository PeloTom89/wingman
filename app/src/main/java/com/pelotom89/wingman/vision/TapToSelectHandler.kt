package com.pelotom89.wingman.vision

import android.graphics.Bitmap

/**
 * Converts the UI's drag-a-box gesture (screen pixel coordinates) into a normalized
 * [BoundingBox] and seeds [SubjectTracker] — mirrors ActiveTrack's own tap-a-box UX so the
 * operator interaction feels familiar despite this being custom tracking underneath.
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
}

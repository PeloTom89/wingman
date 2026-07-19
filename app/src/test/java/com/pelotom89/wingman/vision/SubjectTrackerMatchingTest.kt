package com.pelotom89.wingman.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [matchDetectionToPreviousBox] is the piece of the detect-then-track pipeline the plan
 * calls out as unit-testable against recorded/synthetic box sequences, independent of any
 * camera or model — these tests exercise it against hand-built detection lists.
 */
class SubjectTrackerMatchingTest {

    private val previousBox = BoundingBox(centerX = 0.5f, centerY = 0.5f, width = 0.2f, height = 0.3f)

    @Test
    fun `picks the closest detection when multiple candidates are similarly sized`() {
        val near = Detection(BoundingBox(0.52f, 0.51f, 0.2f, 0.3f), confidence = 0.9f)
        val far = Detection(BoundingBox(0.9f, 0.9f, 0.2f, 0.3f), confidence = 0.95f)

        val matched = matchDetectionToPreviousBox(listOf(far, near), previousBox)

        assertEquals(near, matched)
    }

    @Test
    fun `rejects a detection outside the max center distance even if it's the only one`() {
        val tooFar = Detection(BoundingBox(0.95f, 0.95f, 0.2f, 0.3f), confidence = 0.99f)

        val matched = matchDetectionToPreviousBox(listOf(tooFar), previousBox)

        assertNull(matched)
    }

    @Test
    fun `rejects a detection with a wildly different size even if nearby`() {
        // Same center, but 4x the area — plausibly a different, closer subject rather
        // than the same one that was being tracked.
        val wrongSize = Detection(BoundingBox(0.51f, 0.51f, 0.4f, 0.6f), confidence = 0.9f)

        val matched = matchDetectionToPreviousBox(listOf(wrongSize), previousBox)

        assertNull(matched)
    }

    @Test
    fun `returns null when there are no detections`() {
        assertNull(matchDetectionToPreviousBox(emptyList(), previousBox))
    }

    @Test
    fun `accepts a similarly sized detection at the same position`() {
        val same = Detection(BoundingBox(0.5f, 0.5f, 0.21f, 0.29f), confidence = 0.9f)

        val matched = matchDetectionToPreviousBox(listOf(same), previousBox)

        assertEquals(same, matched)
    }

    // --- findTappedDetection: the "tap the person you want to track" selection flow ---

    @Test
    fun `finds the detection whose box contains the tap point`() {
        val left = Detection(BoundingBox(0.2f, 0.5f, 0.2f, 0.3f), confidence = 0.9f)
        val right = Detection(BoundingBox(0.8f, 0.5f, 0.2f, 0.3f), confidence = 0.9f)

        val tapped = findTappedDetection(listOf(left, right), tapX = 0.78f, tapY = 0.5f)

        assertEquals(right, tapped)
    }

    @Test
    fun `returns null when the tap doesn't land inside any detection`() {
        val onlyDetection = Detection(BoundingBox(0.2f, 0.5f, 0.2f, 0.3f), confidence = 0.9f)

        val tapped = findTappedDetection(listOf(onlyDetection), tapX = 0.9f, tapY = 0.9f)

        assertNull(tapped)
    }

    @Test
    fun `prefers the smallest (most specific) box when the tap lands inside overlapping detections`() {
        // A closer/more specific person detection nested inside a larger, looser one at
        // the same point -- e.g. two overlapping "person" detections from a busy scene.
        val large = Detection(BoundingBox(0.5f, 0.5f, 0.6f, 0.6f), confidence = 0.8f)
        val small = Detection(BoundingBox(0.5f, 0.5f, 0.1f, 0.1f), confidence = 0.85f)

        val tapped = findTappedDetection(listOf(large, small), tapX = 0.5f, tapY = 0.5f)

        assertEquals(small, tapped)
    }

    @Test
    fun `returns null for an empty candidate list`() {
        assertNull(findTappedDetection(emptyList(), tapX = 0.5f, tapY = 0.5f))
    }
}

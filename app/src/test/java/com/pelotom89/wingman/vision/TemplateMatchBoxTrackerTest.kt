package com.pelotom89.wingman.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [findBestMatch] is the piece of TemplateMatchBoxTracker the plan's testing note calls
 * out as unit-testable independent of any Bitmap/camera dependency — pure array math over
 * synthetic [GrayscalePatch] fixtures.
 */
class TemplateMatchBoxTrackerTest {

    @Test
    fun `finds the exact offset where the template matches perfectly`() {
        // 6x6 search area, mostly mid-gray, with a distinct 2x2 dark block at (3,1).
        val search = GrayscalePatch(
            pixels = IntArray(36) { 128 }.also { pixels ->
                for (y in 1..2) for (x in 3..4) pixels[y * 6 + x] = 10
            },
            width = 6,
            height = 6,
        )
        val template = GrayscalePatch(pixels = intArrayOf(10, 10, 10, 10), width = 2, height = 2)

        val match = findBestMatch(template, search, step = 1)

        requireNotNull(match)
        assertEquals(3, match.offsetX)
        assertEquals(1, match.offsetY)
        assertEquals(0.0, match.meanAbsDifference, 0.0001)
    }

    @Test
    fun `returns null when the template is larger than the search area`() {
        val template = GrayscalePatch(IntArray(100) { 0 }, width = 10, height = 10)
        val search = GrayscalePatch(IntArray(16) { 0 }, width = 4, height = 4)

        assertNull(findBestMatch(template, search))
    }

    @Test
    fun `still returns the least-bad match when nothing matches well`() {
        // No offset can make this template (all 255) match a search area of all 0s --
        // findBestMatch itself doesn't threshold, that's TemplateMatchBoxTracker's job
        // (maxAcceptableMeanAbsDifference) -- it should still return its best attempt.
        val template = GrayscalePatch(IntArray(4) { 255 }, width = 2, height = 2)
        val search = GrayscalePatch(IntArray(16) { 0 }, width = 4, height = 4)

        val match = findBestMatch(template, search)

        requireNotNull(match)
        assertEquals(255.0, match.meanAbsDifference, 0.0001)
    }

    @Test
    fun `step size trades search resolution for speed but stays within bounds`() {
        val search = GrayscalePatch(IntArray(64) { 128 }, width = 8, height = 8)
        val template = GrayscalePatch(IntArray(4) { 128 }, width = 2, height = 2)

        val match = findBestMatch(template, search, step = 3)

        requireNotNull(match)
        assertTrue(match.offsetX in 0..6)
        assertTrue(match.offsetY in 0..6)
        assertEquals(0.0, match.meanAbsDifference, 0.0001) // uniform patch matches everywhere
    }
}

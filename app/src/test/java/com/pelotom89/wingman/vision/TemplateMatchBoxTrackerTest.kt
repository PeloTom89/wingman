package com.pelotom89.wingman.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [findBestMatch] is the piece of TemplateMatchBoxTracker the plan's testing note calls
 * out as unit-testable independent of any Bitmap/camera dependency — pure array math over
 * synthetic [ColorPatch] fixtures. Color, not grayscale: the real deployment target is
 * tracking a rider at a distance on a bike, where color (a jersey/helmet/bike against
 * road/grass/sky) is a much stronger discriminator than brightness alone.
 */
class TemplateMatchBoxTrackerTest {

    private fun argb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun `finds the exact offset where the template matches perfectly`() {
        // 6x6 search area, mostly neutral gray, with a distinct 2x2 red block at (3,1) --
        // a stand-in for e.g. a red jersey against a gray road.
        val gray = argb(128, 128, 128)
        val red = argb(200, 20, 20)
        val search = ColorPatch(
            pixels = IntArray(36) { gray }.also { pixels ->
                for (y in 1..2) for (x in 3..4) pixels[y * 6 + x] = red
            },
            width = 6,
            height = 6,
        )
        val template = ColorPatch(pixels = intArrayOf(red, red, red, red), width = 2, height = 2)

        val match = findBestMatch(template, search, step = 1)

        requireNotNull(match)
        assertEquals(3, match.offsetX)
        assertEquals(1, match.offsetY)
        assertEquals(0.0, match.meanAbsDifference, 0.0001)
    }

    @Test
    fun `distinguishes same-brightness different-color patches (the grayscale failure mode)`() {
        // Equal luminance, different color -- a grayscale matcher would score these as
        // identical; a color-aware one must not. This is exactly the reported failure:
        // the box drifting onto a same-brightness wall/pillow instead of a colored subject.
        val greenJersey = argb(60, 160, 60) // luma ~ 0.299*60 + 0.587*160 + 0.114*60 ~ 118
        val grayRoad = argb(118, 118, 118) // same luma, no color

        val diff = kotlin.math.abs(((greenJersey shr 16) and 0xFF) - ((grayRoad shr 16) and 0xFF)) +
            kotlin.math.abs(((greenJersey shr 8) and 0xFF) - ((grayRoad shr 8) and 0xFF)) +
            kotlin.math.abs((greenJersey and 0xFF) - (grayRoad and 0xFF))

        assertTrue("same-luma colors must score as different under color matching", diff > 0)
    }

    @Test
    fun `returns null when the template is larger than the search area`() {
        val template = ColorPatch(IntArray(100) { argb(0, 0, 0) }, width = 10, height = 10)
        val search = ColorPatch(IntArray(16) { argb(0, 0, 0) }, width = 4, height = 4)

        assertNull(findBestMatch(template, search))
    }

    @Test
    fun `still returns the least-bad match when nothing matches well`() {
        // No offset can make this all-white template match an all-black search area --
        // findBestMatch itself doesn't threshold, that's TemplateMatchBoxTracker's job
        // (maxAcceptableMeanAbsDifference) -- it should still return its best attempt.
        val template = ColorPatch(IntArray(4) { argb(255, 255, 255) }, width = 2, height = 2)
        val search = ColorPatch(IntArray(16) { argb(0, 0, 0) }, width = 4, height = 4)

        val match = findBestMatch(template, search)

        requireNotNull(match)
        assertEquals(255.0, match.meanAbsDifference, 0.0001) // (255+255+255)/3 per pixel
    }

    @Test
    fun `step size trades search resolution for speed but stays within bounds`() {
        val uniform = argb(128, 64, 200)
        val search = ColorPatch(IntArray(64) { uniform }, width = 8, height = 8)
        val template = ColorPatch(IntArray(4) { uniform }, width = 2, height = 2)

        val match = findBestMatch(template, search, step = 3)

        requireNotNull(match)
        assertTrue(match.offsetX in 0..6)
        assertTrue(match.offsetY in 0..6)
        assertEquals(0.0, match.meanAbsDifference, 0.0001) // uniform patch matches everywhere
    }
}

package com.pelotom89.wingman.vision

import android.graphics.Bitmap
import kotlin.math.roundToInt

/**
 * Bridges the gap between [SubjectDetector] calls (see plan rationale: detection runs at
 * reduced cadence, not every frame). Replaces the honest-placeholder `CoastingBoxTracker`
 * (which assumed zero motion and would drift badly on any moving subject).
 *
 * Approach: RGB color template matching (mean absolute difference across all three channels
 * over a downscaled patch, searched across a window around the last known position) rather
 * than optical flow or a library tracker (OpenCV CSRT/KCF) — deliberately: no new native
 * dependency, cheap enough to run every bridge frame on a mid-range phone, and simple enough
 * to keep the scoring math ([findBestMatch]) pure and unit-testable, independent of any
 * Bitmap/Android API. It's a short-term bridge only — [SubjectTracker] re-anchors the
 * template on every fresh detector match, so this never has to track for more than a few
 * frames unassisted.
 *
 * Color, not grayscale: this is the real target use case, not just a bench test — tracking
 * a rider at a distance on a bike, where luminance-only matching drifted onto same-brightness
 * background (a pillow/wall in early indoor testing; road/grass/sky in the field is the same
 * failure mode). Color is a much stronger discriminator than brightness alone for a
 * colored jersey/helmet/bike against a comparatively neutral background, and unlike a
 * skin-tone-based check (tried and reverted — a geared-up, helmeted, gloved rider at
 * distance shows little to no visible skin, so that heuristic actively works against the
 * real deployment scenario), color matching doesn't assume anything about what the subject
 * looks like.
 */
class TemplateMatchBoxTracker(
    private val patchSize: Int = 24,
    private val searchRadiusPatchPixels: Int = 10,
    private val searchStep: Int = 2,
    private val maxAcceptableMeanAbsDifference: Double = 45.0,
) : BoxTracker {

    private var template: ColorPatch? = null
    private var lastBox: BoundingBox? = null

    override fun init(frame: Bitmap, box: BoundingBox) {
        template = extractColorPatch(frame, box, patchSize)
        lastBox = box
    }

    override fun update(frame: Bitmap): BoundingBox? {
        val tmpl = template ?: return null
        val previousBox = lastBox ?: return null

        // Search area: the template patch size plus a border on each side, extracted
        // around the previous box's center so [findBestMatch] only has to scan a bounded
        // local window rather than the whole frame.
        val searchSize = patchSize + 2 * searchRadiusPatchPixels
        val searchArea = extractColorPatch(frame, previousBox, searchSize) ?: return null

        val match = findBestMatch(tmpl, searchArea, searchStep) ?: return null
        if (match.meanAbsDifference > maxAcceptableMeanAbsDifference) return null

        // Convert the match offset (in the downscaled search-patch's pixel space) back to
        // a normalized frame-relative box, keeping the box's original width/height —
        // this tracker follows position, not scale, between real detections.
        val searchWidthNorm = previousBox.width * (searchSize.toFloat() / patchSize)
        val searchHeightNorm = previousBox.height * (searchSize.toFloat() / patchSize)
        val searchLeftNorm = previousBox.centerX - searchWidthNorm / 2f
        val searchTopNorm = previousBox.centerY - searchHeightNorm / 2f

        val matchCenterFracX = (match.offsetX + patchSize / 2f) / searchSize
        val matchCenterFracY = (match.offsetY + patchSize / 2f) / searchSize

        val newBox = BoundingBox(
            centerX = searchLeftNorm + matchCenterFracX * searchWidthNorm,
            centerY = searchTopNorm + matchCenterFracY * searchHeightNorm,
            width = previousBox.width,
            height = previousBox.height,
        )
        lastBox = newBox
        return newBox
    }

    private fun extractColorPatch(frame: Bitmap, box: BoundingBox, targetSize: Int): ColorPatch? {
        val left = ((box.centerX - box.width / 2f) * frame.width).roundToInt().coerceIn(0, frame.width - 1)
        val top = ((box.centerY - box.height / 2f) * frame.height).roundToInt().coerceIn(0, frame.height - 1)
        val right = ((box.centerX + box.width / 2f) * frame.width).roundToInt().coerceIn(left + 1, frame.width)
        val bottom = ((box.centerY + box.height / 2f) * frame.height).roundToInt().coerceIn(top + 1, frame.height)
        val regionWidth = right - left
        val regionHeight = bottom - top
        if (regionWidth <= 0 || regionHeight <= 0) return null

        val scaled = Bitmap.createScaledBitmap(
            Bitmap.createBitmap(frame, left, top, regionWidth, regionHeight),
            targetSize,
            targetSize,
            /* filter = */ true,
        )
        val pixels = IntArray(targetSize * targetSize)
        scaled.getPixels(pixels, 0, targetSize, 0, 0, targetSize, targetSize)
        return ColorPatch(pixels, targetSize, targetSize)
    }
}

/** Packed ARGB pixels, row-major, [width] x [height]. */
data class ColorPatch(val pixels: IntArray, val width: Int, val height: Int)

data class MatchResult(val offsetX: Int, val offsetY: Int, val meanAbsDifference: Double)

/**
 * Slides [template] across [searchArea] at [step]-pixel intervals, scoring each position by
 * mean absolute difference across all three RGB channels (lower = better match; averaged
 * per-channel so the result stays on a familiar 0-255-ish scale regardless of using 3
 * channels instead of 1). Pure array math, no Bitmap/Android dependency — this is the piece
 * the plan's testing note calls out as unit-testable independent of any camera/model.
 */
fun findBestMatch(template: ColorPatch, searchArea: ColorPatch, step: Int = 2): MatchResult? {
    val maxOffsetX = searchArea.width - template.width
    val maxOffsetY = searchArea.height - template.height
    if (maxOffsetX < 0 || maxOffsetY < 0) return null

    var best: MatchResult? = null
    var offsetY = 0
    while (offsetY <= maxOffsetY) {
        var offsetX = 0
        while (offsetX <= maxOffsetX) {
            val score = meanAbsDifference(template, searchArea, offsetX, offsetY)
            if (best == null || score < best.meanAbsDifference) {
                best = MatchResult(offsetX, offsetY, score)
            }
            offsetX += step
        }
        offsetY += step
    }
    return best
}

private fun meanAbsDifference(template: ColorPatch, searchArea: ColorPatch, offsetX: Int, offsetY: Int): Double {
    var sum = 0L
    for (y in 0 until template.height) {
        val searchRowBase = (offsetY + y) * searchArea.width + offsetX
        val templateRowBase = y * template.width
        for (x in 0 until template.width) {
            val a = template.pixels[templateRowBase + x]
            val b = searchArea.pixels[searchRowBase + x]
            sum += channelAbsDifference(a, b)
        }
    }
    // /3 to average across channels, keeping the metric on a familiar 0-255-ish per-channel
    // scale (so maxAcceptableMeanAbsDifference's default didn't need re-tuning when this
    // moved from single-channel luminance to 3-channel color).
    return sum.toDouble() / (template.width * template.height * 3)
}

private fun channelAbsDifference(argbA: Int, argbB: Int): Int {
    val rDiff = kotlin.math.abs(((argbA shr 16) and 0xFF) - ((argbB shr 16) and 0xFF))
    val gDiff = kotlin.math.abs(((argbA shr 8) and 0xFF) - ((argbB shr 8) and 0xFF))
    val bDiff = kotlin.math.abs((argbA and 0xFF) - (argbB and 0xFF))
    return rDiff + gDiff + bDiff
}

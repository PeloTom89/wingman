package com.pelotom89.wingman.vision

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector

/**
 * Phase 1 on-device person detector: MediaPipe Object Detector over EfficientDet-Lite0
 * (bundled at assets/efficientdet_lite0.tflite), restricted to the COCO "person" class.
 *
 * IMAGE running mode (synchronous [detect]) rather than LIVE_STREAM: SubjectTrackingRepository
 * already throttles + runs this off the frame-callback thread, and synchronous detection is
 * far simpler to reason about (no result-callback timestamp bookkeeping). CPU delegate for
 * now -- correctness/perf baseline first; GPU is a later optimization if the budget phone
 * can't keep up.
 *
 * Detection is only HALF of Phase 1: the results are meaningless without the GPS prior
 * (getLiveViewLocationWithGPS) to pick which detected person is the subject and reject the
 * rest. This class stays dumb -- it just returns every person it sees, normalized to
 * [0,1] frame coordinates -- and the GPS gating lives in the tracking layer.
 */
class SubjectDetector(context: Context) {

    private val detector: ObjectDetector = ObjectDetector.createFromOptions(
        context,
        ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL_ASSET).build())
            .setRunningMode(RunningMode.IMAGE)
            .setScoreThreshold(SCORE_THRESHOLD)
            .setMaxResults(MAX_RESULTS)
            .setCategoryAllowlist(listOf("person"))
            .build(),
    )

    /** Detect people in [bitmap], returning boxes normalized to [0,1] frame coordinates.
     *  Synchronous -- call off the main/frame thread (see class header). */
    fun detect(bitmap: Bitmap): List<DetectedSubject> {
        val result = detector.detect(BitmapImageBuilder(bitmap).build())
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        return result.detections().mapNotNull { detection ->
            val score = detection.categories().firstOrNull()?.score() ?: return@mapNotNull null
            val box = detection.boundingBox()
            DetectedSubject(
                left = (box.left / w).coerceIn(0f, 1f),
                top = (box.top / h).coerceIn(0f, 1f),
                right = (box.right / w).coerceIn(0f, 1f),
                bottom = (box.bottom / h).coerceIn(0f, 1f),
                score = score,
            )
        }
    }

    fun close() = detector.close()

    private companion object {
        const val MODEL_ASSET = "efficientdet_lite0.tflite"
        const val SCORE_THRESHOLD = 0.4f
        const val MAX_RESULTS = 5
    }
}

/** A detected person, box normalized to [0,1] frame coordinates (+x right, +y down). */
data class DetectedSubject(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val score: Float,
) {
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
}

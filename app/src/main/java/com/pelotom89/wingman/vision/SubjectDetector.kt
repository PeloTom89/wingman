package com.pelotom89.wingman.vision

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector.ObjectDetectorOptions

/**
 * Runs full person detection against a single frame. Deliberately NOT called every frame —
 * SubjectTracker decides cadence (see its header comment for why: running this at full
 * frame rate would compete with live video decode for the same GPU/NPU budget on a
 * mid-range phone). This class only knows how to detect, not when.
 */
class SubjectDetector(context: Context) {

    private val detector: ObjectDetector = ObjectDetector.createFromOptions(
        context,
        ObjectDetectorOptions.builder()
            .setBaseOptions(
                BaseOptions.builder()
                    .setModelAssetPath(MODEL_ASSET_PATH)
                    .setDelegate(Delegate.GPU)
                    .build(),
            )
            .setRunningMode(RunningMode.IMAGE)
            .setCategoryAllowlist(listOf(PERSON_CATEGORY))
            .setMaxResults(MAX_RESULTS)
            .setScoreThreshold(MIN_CONFIDENCE)
            .build(),
    )

    fun detectPeople(frame: Bitmap): List<Detection> {
        val result = detector.detect(com.google.mediapipe.framework.image.BitmapImageBuilder(frame).build())
        return result.detections().map { d ->
            val rect = d.boundingBox()
            Detection(
                box = BoundingBox(
                    centerX = (rect.left + rect.right) / 2f / frame.width,
                    centerY = (rect.top + rect.bottom) / 2f / frame.height,
                    width = rect.width().toFloat() / frame.width,
                    height = rect.height().toFloat() / frame.height,
                ),
                confidence = d.categories().firstOrNull()?.score() ?: 0f,
            )
        }
    }

    fun close() = detector.close()

    private companion object {
        // EfficientDet-Lite0, quantized — see plan rationale on why a lightweight,
        // GPU-delegated model at reduced cadence rather than a heavier per-frame model.
        // Committed at app/src/main/assets/efficientdet_lite0.tflite (pulled from
        // storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/int8/1/).
        const val MODEL_ASSET_PATH = "efficientdet_lite0.tflite"
        const val PERSON_CATEGORY = "person"
        const val MAX_RESULTS = 5
        const val MIN_CONFIDENCE = 0.5f
    }
}

data class Detection(val box: BoundingBox, val confidence: Float)

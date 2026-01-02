package com.pushupcounter.app.detection

import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseDetector
import com.google.mlkit.vision.pose.accurate.AccuratePoseDetectorOptions

/**
 * Image dimensions data class for coordinate transformation
 */
data class ImageDimensions(
    val width: Int,
    val height: Int,
    val rotation: Int
)

/**
 * Processes camera frames for pose detection using ML Kit.
 */
class PoseDetectorProcessor(
    private val onPoseDetected: (Pose, ImageDimensions) -> Unit,
    private val onFailure: (Exception) -> Unit
) {

    private val detector: PoseDetector

    init {
        val options = AccuratePoseDetectorOptions.Builder()
            .setDetectorMode(AccuratePoseDetectorOptions.STREAM_MODE)
            .build()

        detector = PoseDetection.getClient(options)
    }

    @androidx.camera.core.ExperimentalGetImage
    fun processImageProxy(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees

            val image = InputImage.fromMediaImage(
                mediaImage,
                rotationDegrees
            )

            // Get actual image dimensions accounting for rotation
            val imageDimensions = if (rotationDegrees == 90 || rotationDegrees == 270) {
                // Swap width/height for portrait orientation
                ImageDimensions(
                    width = mediaImage.height,
                    height = mediaImage.width,
                    rotation = rotationDegrees
                )
            } else {
                ImageDimensions(
                    width = mediaImage.width,
                    height = mediaImage.height,
                    rotation = rotationDegrees
                )
            }

            detector.process(image)
                .addOnSuccessListener { pose ->
                    onPoseDetected(pose, imageDimensions)
                }
                .addOnFailureListener { exception ->
                    onFailure(exception)
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        } else {
            imageProxy.close()
        }
    }

    fun close() {
        detector.close()
    }
}

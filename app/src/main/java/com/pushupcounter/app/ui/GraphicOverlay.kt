package com.pushupcounter.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseLandmark
import com.pushupcounter.app.detection.ImageDimensions

/**
 * Custom view to overlay pose skeleton visualization on camera preview.
 * Handles coordinate transformation to align skeleton with mirrored camera.
 */
class GraphicOverlay(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private val lock = Any()
    private var pose: Pose? = null
    private var imageDimensions: ImageDimensions? = null

    // Transformation values
    private var scaleFactor = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private var imageWidth = 1f
    private var imageHeight = 1f

    private val dotPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val linePaint = Paint().apply {
        color = Color.YELLOW
        style = Paint.Style.STROKE
        strokeWidth = 8f
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
    }

    private val leftSidePaint = Paint().apply {
        color = Color.CYAN
        style = Paint.Style.STROKE
        strokeWidth = 8f
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
    }

    private val rightSidePaint = Paint().apply {
        color = Color.MAGENTA
        style = Paint.Style.STROKE
        strokeWidth = 8f
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
    }

    fun updatePose(newPose: Pose, newDimensions: ImageDimensions) {
        synchronized(lock) {
            pose = newPose
            imageDimensions = newDimensions
            calculateTransformation()
        }
        postInvalidate()
    }

    fun clear() {
        synchronized(lock) {
            pose = null
            imageDimensions = null
        }
        postInvalidate()
    }

    private fun calculateTransformation() {
        val dims = imageDimensions ?: return
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()

        if (viewWidth == 0f || viewHeight == 0f) return

        imageWidth = dims.width.toFloat()
        imageHeight = dims.height.toFloat()

        // Calculate scale to fill the view (match PreviewView's behavior)
        val viewAspect = viewWidth / viewHeight
        val imageAspect = imageWidth / imageHeight

        if (viewAspect > imageAspect) {
            // View is wider - scale by width
            scaleFactor = viewWidth / imageWidth
            offsetX = 0f
            offsetY = (viewHeight - imageHeight * scaleFactor) / 2f
        } else {
            // View is taller - scale by height
            scaleFactor = viewHeight / imageHeight
            offsetX = (viewWidth - imageWidth * scaleFactor) / 2f
            offsetY = 0f
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        synchronized(lock) {
            val currentPose = pose ?: return
            drawPose(canvas, currentPose)
        }
    }

    private fun drawPose(canvas: Canvas, pose: Pose) {
        val landmarks = pose.allPoseLandmarks
        if (landmarks.isEmpty()) return

        // Draw body connections with different colors for left/right
        // Left side (cyan)
        drawLine(canvas, pose, PoseLandmark.LEFT_SHOULDER, PoseLandmark.LEFT_ELBOW, leftSidePaint)
        drawLine(canvas, pose, PoseLandmark.LEFT_ELBOW, PoseLandmark.LEFT_WRIST, leftSidePaint)
        drawLine(canvas, pose, PoseLandmark.LEFT_SHOULDER, PoseLandmark.LEFT_HIP, leftSidePaint)
        drawLine(canvas, pose, PoseLandmark.LEFT_HIP, PoseLandmark.LEFT_KNEE, leftSidePaint)
        drawLine(canvas, pose, PoseLandmark.LEFT_KNEE, PoseLandmark.LEFT_ANKLE, leftSidePaint)

        // Right side (magenta)
        drawLine(canvas, pose, PoseLandmark.RIGHT_SHOULDER, PoseLandmark.RIGHT_ELBOW, rightSidePaint)
        drawLine(canvas, pose, PoseLandmark.RIGHT_ELBOW, PoseLandmark.RIGHT_WRIST, rightSidePaint)
        drawLine(canvas, pose, PoseLandmark.RIGHT_SHOULDER, PoseLandmark.RIGHT_HIP, rightSidePaint)
        drawLine(canvas, pose, PoseLandmark.RIGHT_HIP, PoseLandmark.RIGHT_KNEE, rightSidePaint)
        drawLine(canvas, pose, PoseLandmark.RIGHT_KNEE, PoseLandmark.RIGHT_ANKLE, rightSidePaint)

        // Center connections (yellow)
        drawLine(canvas, pose, PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER, linePaint)
        drawLine(canvas, pose, PoseLandmark.LEFT_HIP, PoseLandmark.RIGHT_HIP, linePaint)

        // Draw landmarks as dots
        landmarks.forEach { landmark ->
            if (landmark.inFrameLikelihood > 0.5) {
                val point = translatePoint(landmark.position.x, landmark.position.y)
                canvas.drawCircle(point.first, point.second, 10f, dotPaint)
            }
        }
    }

    private fun drawLine(canvas: Canvas, pose: Pose, startType: Int, endType: Int, paint: Paint) {
        val start = pose.getPoseLandmark(startType)
        val end = pose.getPoseLandmark(endType)

        if (start != null && end != null &&
            start.inFrameLikelihood > 0.5 && end.inFrameLikelihood > 0.5) {

            val startPoint = translatePoint(start.position.x, start.position.y)
            val endPoint = translatePoint(end.position.x, end.position.y)

            canvas.drawLine(
                startPoint.first, startPoint.second,
                endPoint.first, endPoint.second,
                paint
            )
        }
    }

    /**
     * Transform ML Kit coordinates to view coordinates.
     * Mirrors horizontally to match the flipped camera preview.
     */
    private fun translatePoint(x: Float, y: Float): Pair<Float, Float> {
        // Mirror the X coordinate (since camera preview is mirrored with scaleX=-1)
        val mirroredX = imageWidth - x

        // Scale and offset to match view
        val translatedX = mirroredX * scaleFactor + offsetX
        val translatedY = y * scaleFactor + offsetY

        return Pair(translatedX, translatedY)
    }
}

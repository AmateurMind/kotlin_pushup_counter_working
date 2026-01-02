package com.pushupcounter.app.detection

import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseLandmark
import kotlin.math.atan2
import kotlin.math.abs

/**
 * Advanced push-up counter using DUAL METRICS:
 * 1. Elbow angle (primary)
 * 2. Shoulder vertical movement (validation)
 *
 * This prevents false counts from arm waving or partial reps.
 */
class PushUpCounter {

    private var count = 0
    private var state = PushUpState.UNKNOWN
    private var isInPushUpPosition = false

    // Smoothing buffers
    private val angleBuffer = ArrayDeque<Double>(SMOOTHING_WINDOW)
    private val shoulderHeightBuffer = ArrayDeque<Float>(SMOOTHING_WINDOW)

    private var smoothedAngle = 0.0
    private var smoothedShoulderHeight = 0f
    private var currentElbowAngle = 0.0

    // Reference points for shoulder movement
    private var baselineShoulderY: Float? = null
    private var maxShoulderDrop = 0f

    // Cooldown
    private var lastStateChangeTime = 0L
    private var framesInCurrentState = 0
    private var framesWithValidArms = 0

    // IMPROVED THRESHOLDS
    private val DOWN_ANGLE_THRESHOLD = 110.0    // Elbow angle when down
    private val UP_ANGLE_THRESHOLD = 140.0      // Elbow angle when up

    private val MIN_SHOULDER_DROP = 40f         // Must drop at least 40 pixels
    private val HYSTERESIS = 8.0

    // Stability settings
    private val MIN_COOLDOWN_MS = 400L
    private val MIN_FRAMES_IN_STATE = 3
    private val MIN_FRAMES_VALID = 5
    private val MIN_CONFIDENCE = 0.5f

    companion object {
        private const val SMOOTHING_WINDOW = 3
    }

    enum class PushUpState {
        UP, DOWN, UNKNOWN
    }

    data class RepQuality(val wasDeepEnough: Boolean, val depthPixels: Float)
    private var lastRepQuality: RepQuality? = null

    fun processPose(pose: Pose): Int {
        val armData = getArmData(pose)

        if (armData == null) {
            framesWithValidArms = 0
            isInPushUpPosition = false
            return count
        }

        framesWithValidArms++
        isInPushUpPosition = validatePushUpPosition(pose, armData)

        if (!isInPushUpPosition) {
            framesWithValidArms = 0
            return count
        }

        if (framesWithValidArms < MIN_FRAMES_VALID) {
            return count
        }

        // Get shoulder height
        val shoulderY = getAverageShoulderY(pose)

        // Initialize baseline on first valid frame
        if (baselineShoulderY == null && shoulderY != null) {
            baselineShoulderY = shoulderY
        }

        // Apply smoothing
        smoothedAngle = smoothAngle(armData.angle)
        if (shoulderY != null) {
            smoothedShoulderHeight = smoothShoulderHeight(shoulderY)
        }

        currentElbowAngle = smoothedAngle

        // Track maximum drop for rep quality
        if (baselineShoulderY != null && shoulderY != null) {
            val currentDrop = shoulderY - baselineShoulderY!!
            if (currentDrop > maxShoulderDrop) {
                maxShoulderDrop = currentDrop
            }
        }

        // Process with DUAL validation
        processStateMachineWithValidation(smoothedAngle, shoulderY)

        return count
    }

    private fun getArmData(pose: Pose): ArmData? {
        val leftArm = getArmAngle(pose, isLeft = true)
        val rightArm = getArmAngle(pose, isLeft = false)

        return when {
            leftArm != null && rightArm != null -> {
                ArmData(
                    angle = (leftArm.angle + rightArm.angle) / 2,
                    confidence = (leftArm.confidence + rightArm.confidence) / 2,
                    wristY = (leftArm.wristY + rightArm.wristY) / 2,
                    shoulderY = (leftArm.shoulderY + rightArm.shoulderY) / 2,
                    elbowY = (leftArm.elbowY + rightArm.elbowY) / 2
                )
            }
            leftArm != null -> leftArm
            rightArm != null -> rightArm
            else -> null
        }
    }

    private fun getArmAngle(pose: Pose, isLeft: Boolean): ArmData? {
        val shoulder = pose.getPoseLandmark(
            if (isLeft) PoseLandmark.LEFT_SHOULDER else PoseLandmark.RIGHT_SHOULDER
        )
        val elbow = pose.getPoseLandmark(
            if (isLeft) PoseLandmark.LEFT_ELBOW else PoseLandmark.RIGHT_ELBOW
        )
        val wrist = pose.getPoseLandmark(
            if (isLeft) PoseLandmark.LEFT_WRIST else PoseLandmark.RIGHT_WRIST
        )

        if (shoulder == null || elbow == null || wrist == null) return null

        val minConfidence = minOf(
            shoulder.inFrameLikelihood,
            elbow.inFrameLikelihood,
            wrist.inFrameLikelihood
        )

        if (minConfidence < MIN_CONFIDENCE) return null

        val angle = calculateAngle(
            shoulder.position.x, shoulder.position.y,
            elbow.position.x, elbow.position.y,
            wrist.position.x, wrist.position.y
        )

        return ArmData(
            angle = angle,
            confidence = minConfidence,
            wristY = wrist.position.y,
            shoulderY = shoulder.position.y,
            elbowY = elbow.position.y
        )
    }

    private fun getAverageShoulderY(pose: Pose): Float? {
        val leftShoulder = pose.getPoseLandmark(PoseLandmark.LEFT_SHOULDER)
        val rightShoulder = pose.getPoseLandmark(PoseLandmark.RIGHT_SHOULDER)

        val leftY = if (leftShoulder?.inFrameLikelihood ?: 0f > MIN_CONFIDENCE) {
            leftShoulder?.position?.y
        } else null

        val rightY = if (rightShoulder?.inFrameLikelihood ?: 0f > MIN_CONFIDENCE) {
            rightShoulder?.position?.y
        } else null

        return when {
            leftY != null && rightY != null -> (leftY + rightY) / 2
            leftY != null -> leftY
            rightY != null -> rightY
            else -> null
        }
    }

    private fun validatePushUpPosition(pose: Pose, armData: ArmData): Boolean {
        val angleInRange = armData.angle in 50.0..175.0
        return angleInRange && armData.confidence >= MIN_CONFIDENCE
    }

    private fun smoothAngle(rawValue: Double): Double {
        angleBuffer.addLast(rawValue)
        if (angleBuffer.size > SMOOTHING_WINDOW) {
            angleBuffer.removeFirst()
        }
        return if (angleBuffer.isNotEmpty()) angleBuffer.average() else rawValue
    }

    private fun smoothShoulderHeight(rawValue: Float): Float {
        shoulderHeightBuffer.addLast(rawValue)
        if (shoulderHeightBuffer.size > SMOOTHING_WINDOW) {
            shoulderHeightBuffer.removeFirst()
        }
        return if (shoulderHeightBuffer.isNotEmpty()) {
            shoulderHeightBuffer.average().toFloat()
        } else rawValue
    }

    private fun processStateMachineWithValidation(angle: Double, shoulderY: Float?) {
        val currentTime = System.currentTimeMillis()
        val timeSinceLastChange = currentTime - lastStateChangeTime

        when (state) {
            PushUpState.UNKNOWN -> {
                state = if (angle > UP_ANGLE_THRESHOLD) PushUpState.UP else PushUpState.DOWN
                lastStateChangeTime = currentTime
                framesInCurrentState = 0
            }
            PushUpState.UP -> {
                // Going DOWN - must satisfy BOTH conditions
                val angleLowEnough = angle < DOWN_ANGLE_THRESHOLD - HYSTERESIS

                if (angleLowEnough) {
                    framesInCurrentState++
                    if (framesInCurrentState >= MIN_FRAMES_IN_STATE &&
                        timeSinceLastChange > MIN_COOLDOWN_MS) {
                        state = PushUpState.DOWN
                        lastStateChangeTime = currentTime
                        framesInCurrentState = 0
                        maxShoulderDrop = 0f  // Reset drop tracker
                    }
                } else {
                    framesInCurrentState = 0
                }
            }
            PushUpState.DOWN -> {
                // Going UP - must satisfy angle + depth requirement
                val angleHighEnough = angle > UP_ANGLE_THRESHOLD + HYSTERESIS
                val depthSufficient = maxShoulderDrop >= MIN_SHOULDER_DROP

                if (angleHighEnough) {
                    framesInCurrentState++
                    if (framesInCurrentState >= MIN_FRAMES_IN_STATE &&
                        timeSinceLastChange > MIN_COOLDOWN_MS) {

                        // Only count if depth was sufficient
                        if (depthSufficient) {
                            count++
                            lastRepQuality = RepQuality(true, maxShoulderDrop)
                        } else {
                            // Shallow rep - don't count but track it
                            lastRepQuality = RepQuality(false, maxShoulderDrop)
                        }

                        state = PushUpState.UP
                        lastStateChangeTime = currentTime
                        framesInCurrentState = 0
                        maxShoulderDrop = 0f
                    }
                } else {
                    framesInCurrentState = 0
                }
            }
        }
    }

    private fun calculateAngle(
        ax: Float, ay: Float,
        bx: Float, by: Float,
        cx: Float, cy: Float
    ): Double {
        val radians = atan2(cy - by, cx - bx) - atan2(ay - by, ax - bx)
        var angle = Math.toDegrees(radians.toDouble())
        angle = abs(angle)
        if (angle > 180.0) {
            angle = 360.0 - angle
        }
        return angle
    }

    fun reset() {
        count = 0
        state = PushUpState.UNKNOWN
        isInPushUpPosition = false
        smoothedAngle = 0.0
        smoothedShoulderHeight = 0f
        currentElbowAngle = 0.0
        angleBuffer.clear()
        shoulderHeightBuffer.clear()
        framesInCurrentState = 0
        framesWithValidArms = 0
        lastStateChangeTime = 0L
        baselineShoulderY = null
        maxShoulderDrop = 0f
        lastRepQuality = null
    }

    fun processSimulatedAngle(angle: Double): Int {
        isInPushUpPosition = true
        framesWithValidArms = MIN_FRAMES_VALID + 1
        smoothedAngle = smoothAngle(angle)
        currentElbowAngle = smoothedAngle

        // For test mode, bypass depth check
        processStateMachineSimple(smoothedAngle)
        return count
    }

    private fun processStateMachineSimple(angle: Double) {
        val currentTime = System.currentTimeMillis()
        val timeSinceLastChange = currentTime - lastStateChangeTime

        when (state) {
            PushUpState.UNKNOWN -> {
                state = if (angle > UP_ANGLE_THRESHOLD) PushUpState.UP else PushUpState.DOWN
                lastStateChangeTime = currentTime
            }
            PushUpState.UP -> {
                if (angle < DOWN_ANGLE_THRESHOLD - HYSTERESIS) {
                    framesInCurrentState++
                    if (framesInCurrentState >= MIN_FRAMES_IN_STATE &&
                        timeSinceLastChange > MIN_COOLDOWN_MS) {
                        state = PushUpState.DOWN
                        lastStateChangeTime = currentTime
                        framesInCurrentState = 0
                    }
                } else {
                    framesInCurrentState = 0
                }
            }
            PushUpState.DOWN -> {
                if (angle > UP_ANGLE_THRESHOLD + HYSTERESIS) {
                    framesInCurrentState++
                    if (framesInCurrentState >= MIN_FRAMES_IN_STATE &&
                        timeSinceLastChange > MIN_COOLDOWN_MS) {
                        state = PushUpState.UP
                        count++
                        lastStateChangeTime = currentTime
                        framesInCurrentState = 0
                    }
                } else {
                    framesInCurrentState = 0
                }
            }
        }
    }

    fun getTestInfo(): TestInfo {
        return TestInfo(
            downThreshold = DOWN_ANGLE_THRESHOLD - HYSTERESIS,
            upThreshold = UP_ANGLE_THRESHOLD + HYSTERESIS,
            minCooldownMs = MIN_COOLDOWN_MS,
            minFramesInState = MIN_FRAMES_IN_STATE
        )
    }

    data class TestInfo(
        val downThreshold: Double,
        val upThreshold: Double,
        val minCooldownMs: Long,
        val minFramesInState: Int
    )

    fun getCount() = count
    fun getCurrentState() = state
    fun isInPushUpPosition() = isInPushUpPosition
    fun getCurrentAngle() = currentElbowAngle
    fun getLastRepQuality() = lastRepQuality

    data class ArmData(
        val angle: Double,
        val confidence: Float,
        val wristY: Float,
        val shoulderY: Float,
        val elbowY: Float
    )
}

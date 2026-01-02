package com.pushupcounter.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.pushupcounter.app.databinding.ActivityMainBinding
import com.pushupcounter.app.detection.PoseDetectorProcessor
import com.pushupcounter.app.detection.PushUpCounter
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var poseProcessor: PoseDetectorProcessor
    private val pushUpCounter = PushUpCounter()

    private var isTestMode = false
    private val handler = Handler(Looper.getMainLooper())

    companion object {
        private const val TAG = "PushUpCounter"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        // Check camera permissions
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }

        setupButtons()
        setupTestMode()
    }

    private fun setupButtons() {
        // Reset button
        binding.resetButton.setOnClickListener {
            pushUpCounter.reset()
            updateCounter(0)
            updateStatus(false, 0.0)
        }

        // Test mode toggle button
        binding.testModeButton.setOnClickListener {
            toggleTestMode()
        }
    }

    private fun setupTestMode() {
        // Angle slider
        binding.angleSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (isTestMode) {
                    val angle = progress.toDouble()
                    binding.angleText.text = "Angle: ${angle.toInt()}°"

                    // Feed simulated angle to counter
                    val count = pushUpCounter.processSimulatedAngle(angle)
                    updateCounter(count)
                    updateStatus(true, angle)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Quick buttons
        binding.simulateDownButton.setOnClickListener {
            binding.angleSlider.progress = 90
        }

        binding.simulateUpButton.setOnClickListener {
            binding.angleSlider.progress = 160
        }

        // Auto test button - simulates full push-up cycle
        binding.autoTestButton.setOnClickListener {
            runAutoTest()
        }

        // Single rep test
        binding.singleRepTestButton.setOnClickListener {
            runSingleRepTest()
        }

        // Double count test - tests that rapid movements don't cause double counts
        binding.doubleCountTestButton.setOnClickListener {
            runDoubleCountTest()
        }
    }

    private fun toggleTestMode() {
        isTestMode = !isTestMode

        if (isTestMode) {
            // Enter test mode
            binding.testModePanel.visibility = View.VISIBLE
            binding.previewView.visibility = View.GONE
            binding.graphicOverlay.visibility = View.GONE
            binding.testModeButton.text = "📷 CAMERA"
            binding.testModeButton.setBackgroundColor(Color.parseColor("#4CAF50"))

            pushUpCounter.reset()
            updateCounter(0)

            Toast.makeText(this, "Test Mode: Use slider to simulate angles", Toast.LENGTH_SHORT).show()
        } else {
            // Exit test mode
            binding.testModePanel.visibility = View.GONE
            binding.previewView.visibility = View.VISIBLE
            binding.graphicOverlay.visibility = View.VISIBLE
            binding.testModeButton.text = "🧪 TEST"
            binding.testModeButton.setBackgroundColor(Color.parseColor("#FFA726"))

            pushUpCounter.reset()
            updateCounter(0)

            Toast.makeText(this, "Camera Mode", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Properly designed auto test that respects timing constraints:
     * - MIN_COOLDOWN_MS = 400ms between state changes
     * - MIN_FRAMES_IN_STATE = 3 frames in transition zone
     * - DOWN threshold = 102° (need angle < 102 to trigger DOWN)
     * - UP threshold = 148° (need angle > 148 to trigger UP)
     *
     * Each rep needs:
     * 1. Start in UP state (angle > 148)
     * 2. Stay in DOWN zone (< 102) for 3+ frames AND 400+ ms
     * 3. Return to UP zone (> 148) for 3+ frames AND 400+ ms → COUNT!
     */
    private fun runAutoTest() {
        disableTestButtons()
        pushUpCounter.reset()
        updateCounter(0)

        val testInfo = pushUpCounter.getTestInfo()
        val expectedReps = 5

        // Calculate timing: need 400ms cooldown + 3 frames
        // Using 150ms per frame means 3 frames = 450ms > 400ms ✓
        val frameDelayMs = 150L

        // Build a proper sequence
        // Each rep: hold UP → transition to DOWN → hold DOWN → transition to UP → count!
        val sequence = mutableListOf<TestFrame>()

        // Initial UP position to establish state
        repeat(3) { sequence.add(TestFrame(160, "Init UP")) }

        // 5 reps
        repeat(expectedReps) { rep ->
            // Transition DOWN (gradual)
            sequence.add(TestFrame(140, "Rep${rep + 1}: Going down"))
            sequence.add(TestFrame(120, "Rep${rep + 1}: Going down"))
            sequence.add(TestFrame(100, "Rep${rep + 1}: Going down"))

            // Hold in DOWN zone (< 102) for enough frames
            repeat(4) { sequence.add(TestFrame(85, "Rep${rep + 1}: DOWN hold")) }

            // Transition UP (gradual)
            sequence.add(TestFrame(110, "Rep${rep + 1}: Going up"))
            sequence.add(TestFrame(130, "Rep${rep + 1}: Going up"))

            // Hold in UP zone (> 148) for enough frames - this triggers COUNT
            repeat(4) { sequence.add(TestFrame(160, "Rep${rep + 1}: UP hold ✓")) }
        }

        runTestSequence(sequence, expectedReps, frameDelayMs, "5 Reps")
    }

    data class TestFrame(val angle: Int, val label: String)

    /**
     * Single rep test - validates one complete push-up is counted correctly
     */
    private fun runSingleRepTest() {
        disableTestButtons()
        pushUpCounter.reset()
        updateCounter(0)

        val frameDelayMs = 150L
        val expectedReps = 1

        val sequence = mutableListOf<TestFrame>()

        // Initialize UP
        repeat(3) { sequence.add(TestFrame(160, "Init: UP state")) }

        // Go DOWN
        sequence.add(TestFrame(140, "Going down..."))
        sequence.add(TestFrame(120, "Going down..."))
        sequence.add(TestFrame(100, "Going down..."))
        repeat(4) { sequence.add(TestFrame(80, "Holding DOWN")) }

        // Go UP - should trigger count
        sequence.add(TestFrame(110, "Going up..."))
        sequence.add(TestFrame(130, "Going up..."))
        repeat(4) { sequence.add(TestFrame(165, "Holding UP - COUNT!")) }

        runTestSequence(sequence, expectedReps, frameDelayMs, "Single Rep")
    }

    /**
     * Double-count prevention test - rapid up/down should NOT cause multiple counts
     * This simulates someone bouncing quickly at the bottom
     */
    private fun runDoubleCountTest() {
        disableTestButtons()
        pushUpCounter.reset()
        updateCounter(0)

        val frameDelayMs = 100L  // Faster to test rapid movements
        val expectedReps = 1    // Should only count ONCE despite rapid movements

        val sequence = mutableListOf<TestFrame>()

        // Initialize UP
        repeat(3) { sequence.add(TestFrame(160, "Init: UP state")) }

        // Go DOWN normally
        repeat(4) { sequence.add(TestFrame(85, "DOWN")) }

        // RAPID bouncing at bottom - should NOT count extra
        sequence.add(TestFrame(100, "Bounce up"))
        sequence.add(TestFrame(85, "Bounce down"))
        sequence.add(TestFrame(105, "Bounce up"))
        sequence.add(TestFrame(80, "Bounce down"))
        sequence.add(TestFrame(95, "Bounce up"))
        sequence.add(TestFrame(85, "Bounce down"))

        // Now properly go UP - should count ONCE
        repeat(4) { sequence.add(TestFrame(160, "Going UP - should be 1")) }

        // Hold to ensure stable
        repeat(3) { sequence.add(TestFrame(160, "Holding UP")) }

        runTestSequence(sequence, expectedReps, frameDelayMs, "Double-Count Prevention")
    }

    private fun disableTestButtons() {
        binding.autoTestButton.isEnabled = false
        binding.singleRepTestButton.isEnabled = false
        binding.doubleCountTestButton.isEnabled = false
    }

    private fun enableTestButtons() {
        binding.autoTestButton.isEnabled = true
        binding.singleRepTestButton.isEnabled = true
        binding.doubleCountTestButton.isEnabled = true
    }

    private fun runTestSequence(
        sequence: List<TestFrame>,
        expectedReps: Int,
        frameDelayMs: Long,
        testName: String
    ) {
        var index = 0

        val runnable = object : Runnable {
            override fun run() {
                if (index < sequence.size && isTestMode) {
                    val frame = sequence[index]
                    binding.angleSlider.progress = frame.angle
                    binding.angleText.text = "Angle: ${frame.angle}° | ${frame.label}"

                    val count = pushUpCounter.processSimulatedAngle(frame.angle.toDouble())
                    updateCounter(count)
                    updateStatus(true, frame.angle.toDouble())

                    index++
                    handler.postDelayed(this, frameDelayMs)
                } else {
                    // Test complete
                    val finalCount = pushUpCounter.getCount()
                    val passed = finalCount == expectedReps

                    val resultText = if (passed) {
                        "✅ $testName PASSED! Got: $finalCount"
                    } else {
                        "❌ $testName FAILED! Expected: $expectedReps, Got: $finalCount"
                    }

                    binding.angleText.text = resultText
                    binding.angleText.setTextColor(
                        if (passed) Color.parseColor("#00FF00") else Color.parseColor("#FF4444")
                    )

                    Toast.makeText(this@MainActivity, resultText, Toast.LENGTH_LONG).show()
                    enableTestButtons()

                    handler.postDelayed({
                        binding.angleText.setTextColor(Color.WHITE)
                    }, 3000)
                }
            }
        }

        Toast.makeText(this, "Running $testName test...", Toast.LENGTH_SHORT).show()
        handler.post(runnable)
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            // Preview
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(binding.previewView.surfaceProvider)
                }

            // Image analysis for pose detection
            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { analysis ->
                    poseProcessor = PoseDetectorProcessor(
                        onPoseDetected = { pose, imageDimensions ->
                            if (!isTestMode) { // Only process camera in camera mode
                                val count = pushUpCounter.processPose(pose)
                                val isInPosition = pushUpCounter.isInPushUpPosition()
                                val currentAngle = pushUpCounter.getCurrentAngle()

                                runOnUiThread {
                                    updateCounter(count)
                                    updateStatus(isInPosition, currentAngle)
                                    binding.graphicOverlay.updatePose(pose, imageDimensions)
                                }
                            }
                        },
                        onFailure = { exception ->
                            Log.e(TAG, "Pose detection failed", exception)
                        }
                    )

                    analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                        processImage(imageProxy)
                    }
                }

            // Select front camera for selfie-style push-ups
            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                // Unbind all use cases before rebinding
                cameraProvider.unbindAll()

                // Bind use cases to camera
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalyzer
                )

            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
                Toast.makeText(
                    this,
                    "Camera initialization failed",
                    Toast.LENGTH_SHORT
                ).show()
            }

        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.camera.core.ExperimentalGetImage
    private fun processImage(imageProxy: androidx.camera.core.ImageProxy) {
        poseProcessor.processImageProxy(imageProxy)
    }

    private fun updateCounter(count: Int) {
        binding.counterText.text = getString(R.string.push_ups, count)
    }

    private fun updateStatus(isInPushUpPosition: Boolean, angle: Double) {
        if (isInPushUpPosition) {
            val angleInt = angle.toInt()
            binding.statusText.text = "Ready! Angle: ${angleInt}°"
            binding.statusText.setTextColor(Color.parseColor("#00FF00"))  // Green
        } else {
            binding.statusText.text = getString(R.string.status_waiting)
            binding.statusText.setTextColor(Color.parseColor("#FFAA00"))  // Orange
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(
                    this,
                    getString(R.string.camera_permission_required),
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        cameraExecutor.shutdown()
        if (::poseProcessor.isInitialized) {
            poseProcessor.close()
        }
    }
}

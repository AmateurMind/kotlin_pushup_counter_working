# Push-Up Counter Android App

A native Android application built with Kotlin that counts push-ups in real-time using on-device pose detection.

## Features

- **Live Camera Preview**: Continuous camera feed displayed on screen
- **Real-Time Pose Detection**: Uses Google ML Kit Pose Detection API for on-device processing
- **Accurate Push-Up Counting**: Angle-based algorithm tracks elbow joint angles
- **Partial Body Support**: Works even when only upper body/arms are visible
- **No Internet Required**: All processing happens offline on the device
- **Visual Feedback**: Skeleton overlay shows detected pose landmarks
- **Reset Functionality**: Easy-to-use reset button to start new counting session

## Tech Stack

- **Language**: Kotlin
- **Camera**: CameraX API for modern camera handling
- **ML**: Google ML Kit Pose Detection (Accurate mode)
- **UI**: View Binding, ConstraintLayout
- **Min SDK**: 24 (Android 7.0)
- **Target SDK**: 34 (Android 14)

## How It Works

### Pose Detection
The app uses ML Kit's accurate pose detector in STREAM_MODE for real-time video analysis. It detects 33 body landmarks including shoulders, elbows, and wrists.

### Push-Up Algorithm
The `PushUpCounter` class implements a state machine that:

1. **Calculates Elbow Angle**: Measures the angle between shoulder-elbow-wrist joints
2. **Averages Both Arms**: Uses data from both arms when visible, or whichever arm is detected
3. **Detects States**: Tracks two states:
   - **UP**: Elbow angle > 160° (arms extended)
   - **DOWN**: Elbow angle < 90° (arms bent)
4. **Counts Reps**: Increments counter when transitioning from DOWN → UP
5. **Prevents Double Counting**: Uses hysteresis (10° buffer) to avoid jittery state changes

### Key Parameters
```kotlin
DOWN_THRESHOLD = 90.0°   // Arms bent (bottom position)
UP_THRESHOLD = 160.0°    // Arms straight (top position)
HYSTERESIS = 10.0°       // Buffer to prevent flickering
```

## Project Structure

```
app/src/main/
├── java/com/pushupcounter/app/
│   ├── MainActivity.kt                    # Main activity with camera setup
│   ├── detection/
│   │   ├── PushUpCounter.kt              # Push-up counting logic
│   │   └── PoseDetectorProcessor.kt      # ML Kit pose detection wrapper
│   └── ui/
│       └── GraphicOverlay.kt             # Pose skeleton visualization
├── res/
│   ├── layout/
│   │   └── activity_main.xml             # Main UI layout
│   └── values/
│       └── strings.xml                    # String resources
└── AndroidManifest.xml                    # App configuration
```

## Building the App

### Prerequisites
- Android Studio Hedgehog (2023.1.1) or later
- JDK 8 or higher
- Android SDK with API 34

### Steps

1. **Open Project**
   ```bash
   # Open this directory in Android Studio
   File -> Open -> Select kotlin_ML folder
   ```

2. **Sync Gradle**
   - Android Studio will automatically sync Gradle files
   - Wait for dependencies to download (ML Kit, CameraX, etc.)

3. **Connect Device or Emulator**
   - Enable USB debugging on your Android device, OR
   - Create an AVD (Android Virtual Device) with camera support

4. **Run**
   - Click the "Run" button (green triangle) in Android Studio
   - Select your target device
   - Grant camera permissions when prompted

## Usage

1. **Launch the app** - Camera preview starts automatically
2. **Position yourself** - Get your upper body in frame (arms, shoulders visible)
3. **Perform push-ups** - The counter increments when you complete each rep
4. **View skeleton** - Yellow/green overlay shows detected body landmarks
5. **Reset count** - Tap the red "RESET" button to start over

## Tips for Accurate Counting

- Ensure good lighting for better pose detection
- Keep your upper body in frame (full body not required)
- Perform push-ups at a consistent pace
- Make sure elbows are clearly visible
- The app works best with the front camera positioned to see your side profile

## Customization

### Adjust Sensitivity
Edit thresholds in `PushUpCounter.kt:14-16`:
```kotlin
private val DOWN_THRESHOLD = 90.0  // Lower = stricter down position
private val UP_THRESHOLD = 160.0   // Higher = stricter up position
private val HYSTERESIS = 10.0      // Increase for more stability
```

### Switch Camera
Edit `MainActivity.kt:103` to use back camera:
```kotlin
val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
```

## Performance

- Runs at 15-30 FPS on modern devices (Snapdragon 600 series or better)
- ML Kit's accurate mode provides better landmark precision
- Single-threaded executor prevents frame queue buildup
- KEEP_ONLY_LATEST backpressure strategy ensures real-time performance

## Dependencies

```kotlin
// CameraX 1.3.1
implementation("androidx.camera:camera-core")
implementation("androidx.camera:camera-camera2")
implementation("androidx.camera:camera-lifecycle")
implementation("androidx.camera:camera-view")

// ML Kit Pose Detection 18.0.0-beta4
implementation("com.google.mlkit:pose-detection")
implementation("com.google.mlkit:pose-detection-accurate")
```

## License

This project is provided as-is for educational and development purposes.

## Troubleshooting

**Camera permission denied**: Grant camera permission in Settings -> Apps -> Push-Up Counter

**Pose not detected**: Ensure adequate lighting and full upper body is visible

**Laggy performance**: Try reducing to base pose detector (non-accurate mode) in `PoseDetectorProcessor.kt`

**Build errors**: Ensure you have Android SDK 34 installed and Gradle is properly synced

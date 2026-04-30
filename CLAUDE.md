# echoAI — Claude Code Context Document

## Project Overview

**echoAI** is an Android mobile application designed to help deaf and hard-of-hearing users navigate their surroundings safely. The app runs entirely on-device, using LiteRT (formerly TensorFlow Lite) models accelerated by the Qualcomm Hexagon NPU on a Samsung Galaxy S25 Ultra. It continuously listens to the acoustic environment, classifies sounds, estimates their 2D spatial position, and assigns urgency levels to warn the user of nearby hazards or events.

This project is submitted to the **Google × Qualcomm Hackathon** under **Track 2: LiteRT — Classical Models (Audio)**.

---

## Hackathon Constraints & Requirements

- **Device:** Samsung Galaxy S25 Ultra (Snapdragon 8 Elite)
- **Runtime:** Google LiteRT (TFLite-compatible `.tflite` or `.task` models)
- **Hardware acceleration:** Qualcomm Hexagon NPU via LiteRT NNAPI/QNN delegate
- **IDE:** Android Studio (Kotlin)
- **Models:** Must NOT require quantization or conversion at runtime — use pre-quantized, NPU-friendly models from Qualcomm AI Hub or LiteRT Hugging Face Model Zoo
- **Repo:** Public GitHub with open-source license, README, and setup instructions
- **Project must be newly created** during the hackathon submission window

---

## Problem Statement

Deaf and hard-of-hearing individuals cannot rely on hearing to detect environmental hazards (sirens, car horns, alarms, shouting, approaching footsteps, etc.). Existing solutions either require dedicated hardware or cloud connectivity. echoAI solves this with a fully offline, real-time, phone-native solution that:

1. **Classifies** up to 1–3 concurrent sounds from the environment
2. **Localizes** each sound in a 2D plane (left/right azimuth + near/far distance) using stereo or multi-mic input
3. **Assigns urgency** (LOW / MEDIUM / HIGH / CRITICAL) to each detected sound
4. **Alerts** the user via visual indicators and haptic feedback

---

## Technical Architecture

### High-Level Components

```
Microphone Input (Stereo / Multi-mic)
        │
        ▼
  Audio Capture Service (AudioRecord, 16kHz, stereo)
        │
        ▼
  Preprocessing Pipeline
  ├─ Frame windowing (e.g. 1s windows, 50% overlap)
  ├─ Log-Mel Spectrogram extraction (on-device, CPU/GPU)
  └─ ILD / ITD feature extraction for spatial estimation
        │
        ▼
┌───────────────────────────────────┐
│        LiteRT Inference Engine     │
│                                   │
│  ┌─────────────────────────────┐  │
│  │  Sound Classification Model │  │  ← YAMNet or equivalent
│  │  (.tflite, INT8 quantized)  │  │     from Qualcomm AI Hub
│  └─────────────────────────────┘  │
│                                   │
│  ┌─────────────────────────────┐  │
│  │  Spatial Localization Model │  │  ← Custom or GCC-PHAT based
│  │  (ILD/ITD → azimuth, dist)  │  │     lightweight regression
│  └─────────────────────────────┘  │
└───────────────────────────────────┘
        │
        ▼
  Post-Processing & Urgency Engine
  ├─ Top-K sound class selection (K = 1–3)
  ├─ Urgency classification (rule-based + configurable)
  └─ De-duplication / temporal smoothing
        │
        ▼
  UI Layer
  ├─ 2D Spatial Radar View (custom Canvas view)
  ├─ Sound label cards with urgency color coding
  └─ Haptic feedback (VibrationEffect patterns per urgency)
```

### Model Strategy

| Model | Purpose | Source | Format |
|---|---|---|---|
| YAMNet (INT8) | Sound event classification (521 classes) | Qualcomm AI Hub or TF Hub | `.tflite` |
| Lightweight azimuth regression | 2D spatial localization from ILD/ITD stereo features | Custom-trained or DSP-based | `.tflite` |

> **Note:** If a pre-built localization model is unavailable, implement GCC-PHAT (Generalized Cross-Correlation with Phase Transform) as a deterministic DSP fallback in Kotlin/C++ for azimuth estimation. Distance estimation can be approximated from signal energy (RMS).

### Urgency Level Taxonomy

| Urgency | Color | Haptic | Example Sounds |
|---|---|---|---|
| CRITICAL | Red 🔴 | Strong, rapid pulse | Car horn, siren, smoke alarm, scream |
| HIGH | Orange 🟠 | Medium pulse | Dog bark, loud knock, train |
| MEDIUM | Yellow 🟡 | Single tap | Doorbell, phone ringing, speech |
| LOW | Blue 🔵 | Subtle tick | Music, ambient noise, HVAC |

Urgency is determined by a configurable lookup table keyed on YAMNet class labels. This table should be user-editable in Settings.

---

## Project Structure

```
echoAI/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/echoai/
│   │   │   │   ├── audio/
│   │   │   │   │   ├── AudioCaptureService.kt       # Foreground service, AudioRecord
│   │   │   │   │   ├── AudioPreprocessor.kt         # Framing, Mel spectrogram, ILD/ITD
│   │   │   │   │   └── GccPhatLocalizer.kt          # DSP fallback for azimuth
│   │   │   │   ├── ml/
│   │   │   │   │   ├── SoundClassifier.kt           # LiteRT YAMNet wrapper
│   │   │   │   │   ├── SoundLocalizer.kt            # LiteRT or DSP localizer wrapper
│   │   │   │   │   └── LiteRTModelManager.kt        # Model loading, delegate config
│   │   │   │   ├── domain/
│   │   │   │   │   ├── SoundEvent.kt                # Data class: label, confidence, azimuth, distance, urgency
│   │   │   │   │   ├── UrgencyClassifier.kt         # Rule-based urgency assignment
│   │   │   │   │   └── SoundEventProcessor.kt       # Top-K, smoothing, dedup
│   │   │   │   ├── ui/
│   │   │   │   │   ├── MainActivity.kt
│   │   │   │   │   ├── RadarView.kt                 # Custom 2D spatial canvas view
│   │   │   │   │   ├── SoundCardAdapter.kt          # RecyclerView for sound events
│   │   │   │   │   └── SettingsActivity.kt          # Urgency table customization
│   │   │   │   └── util/
│   │   │   │       ├── HapticManager.kt             # VibrationEffect patterns
│   │   │   │       └── PermissionHelper.kt
│   │   │   ├── assets/
│   │   │   │   ├── yamnet.tflite                    # Classification model
│   │   │   │   ├── localizer.tflite                 # Localization model (if available)
│   │   │   │   └── urgency_map.json                 # Default urgency label mappings
│   │   │   └── res/
│   │   │       └── layout/ ...
│   └── build.gradle.kts
├── README.md
├── LICENSE                                           # Apache 2.0 recommended
└── CLAUDE.md                                         # This file
```

---

## Key Dependencies (app/build.gradle.kts)

```kotlin
// LiteRT (TFLite successor)
implementation("com.google.ai.edge.litert:litert:1.0.1")
implementation("com.google.ai.edge.litert:litert-support:1.0.1")
implementation("com.google.ai.edge.litert:litert-gpu:1.0.1")       // GPU delegate
implementation("com.google.ai.edge.litert:litert-api:1.0.1")

// NNAPI delegate (routes to Hexagon NPU on Snapdragon)
// Included in litert core — enable via NnApiDelegate() in model config

// Kotlin coroutines for async audio pipeline
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

// Lifecycle & ViewModel
implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
```

---

## LiteRT Model Configuration

### Enabling Hexagon NPU (NNAPI Delegate)

```kotlin
// LiteRTModelManager.kt
val nnApiDelegate = NnApiDelegate(
    NnApiDelegate.Options().apply {
        acceleratorName = "qti-default"        // Qualcomm Hexagon NPU
        executionPreference = NnApiDelegate.Options.EXECUTION_PREFERENCE_SUSTAINED_SPEED
        allowFp16 = true
    }
)

val interpreter = Interpreter(
    modelBuffer,
    Interpreter.Options().apply {
        addDelegate(nnApiDelegate)
        setNumThreads(2)
    }
)
```

### Fallback Chain
1. Hexagon NPU (via NNAPI delegate)
2. GPU delegate
3. CPU (multithreaded)

---

## Audio Pipeline Details

### Capture Parameters
- **Sample rate:** 16,000 Hz (required by YAMNet)
- **Channels:** Stereo (2-channel) — required for ILD/ITD spatial features
- **Buffer size:** ~1 second of audio (16,000 samples per channel)
- **Overlap:** 50% (process every 500ms)
- **Audio source:** `MediaRecorder.AudioSource.MIC` or `UNPROCESSED` (to disable AGC/noise suppression)

### Mel Spectrogram (for YAMNet input)
- YAMNet expects a 1D waveform float array of 15,600 samples (0.975s @ 16kHz)
- Input normalization: values in [-1.0, 1.0]
- Use the mono mix of the stereo signal for classification

### Spatial Features (for Localization)
- **ILD (Interaural Level Difference):** RMS energy ratio between left/right channels → distance proxy + coarse left/right
- **ITD (Interaural Time Difference):** Cross-correlation peak lag between channels → azimuth angle
- **GCC-PHAT:** Preferred over raw cross-correlation for robustness in reverberant environments
- Output: azimuth in degrees (−180° to +180°), distance estimate (near/medium/far)

---

## UI Design Notes

### Radar View (RadarView.kt)
- Full-width circular canvas component
- User represented at the center
- Detected sounds drawn as icons/dots at their estimated azimuth and distance
- Color-coded by urgency level
- Animate dot appearance/fade-out for temporal smoothing
- Accessibility: include content descriptions for all elements (for screen readers / switch access)

### Sound Cards
- Displayed below the radar as a scrollable list
- Each card: sound label, confidence %, urgency badge, directional arrow icon
- Sort by urgency (CRITICAL first)
- Auto-dismiss cards after ~3 seconds of absence

### Haptic Patterns
```kotlin
// HapticManager.kt
val CRITICAL_PATTERN = VibrationEffect.createWaveform(
    longArrayOf(0, 100, 50, 100, 50, 100), intArrayOf(0, 255, 0, 255, 0, 255), -1
)
val HIGH_PATTERN = VibrationEffect.createWaveform(
    longArrayOf(0, 200, 100, 200), intArrayOf(0, 200, 0, 200), -1
)
```

---

## Permissions Required (AndroidManifest.xml)

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.VIBRATE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
<uses-feature android:name="android.hardware.microphone" android:required="true" />
```

---

## Development Priorities & Build Order

Build in this order to enable incremental testing at each stage:

1. **[ ] Audio capture** — `AudioCaptureService.kt`, confirm stereo PCM frames are captured correctly
2. **[ ] Model loading** — `LiteRTModelManager.kt`, load YAMNet `.tflite` and confirm inference runs on NPU
3. **[ ] Classification pipeline** — `AudioPreprocessor.kt` + `SoundClassifier.kt`, end-to-end audio → top label
4. **[ ] Urgency engine** — `UrgencyClassifier.kt`, map labels to urgency levels from `urgency_map.json`
5. **[ ] Localization (DSP)** — `GccPhatLocalizer.kt`, azimuth from stereo ILD/ITD
6. **[ ] UI — Sound cards** — display classified sounds with urgency badges
7. **[ ] UI — Radar view** — 2D spatial display of sounds
8. **[ ] Haptic feedback** — `HapticManager.kt`
9. **[ ] Settings screen** — user-configurable urgency map
10. **[ ] Polish & testing** — latency profiling, NPU vs CPU benchmarks, accessibility review

---

## Performance Targets

| Metric | Target |
|---|---|
| End-to-end latency (capture → alert) | < 500ms |
| YAMNet inference time (NPU) | < 30ms |
| Battery impact | < 5% / hour in background |
| Concurrent sounds tracked | 1–3 |
| Azimuth resolution | ~15° bins |

---

## Known Constraints & Open Questions

- **Stereo mic availability:** S25 Ultra has multiple microphones but Android may not expose true stereo `AudioRecord` channels. Investigate `AudioRecord` channel config and test `CHANNEL_IN_STEREO` availability. Fallback: use two sequential mono recordings or device-specific audio HAL.
- **YAMNet class filtering:** YAMNet has 521 classes — many are irrelevant (e.g., musical instruments). Filter to a curated ~50-class subset for the urgency map.
- **Localization model:** If a pre-trained LiteRT localization model is not available from Qualcomm AI Hub, implement GCC-PHAT in Kotlin/JNI as a deterministic DSP solution and skip the second LiteRT model.
- **Background operation:** App must run as a Foreground Service with a persistent notification to prevent Android from killing the audio capture.

---

## Resources & References

- [LiteRT Documentation](https://ai.google.dev/edge/litert)
- [LiteRT Hugging Face Model Zoo](https://huggingface.co/litert-community)
- [Qualcomm AI Hub](https://aihub.qualcomm.com) — pre-optimized NPU-ready models
- [YAMNet on TF Hub](https://tfhub.dev/google/yamnet/1)
- [YAMNet class map](https://github.com/tensorflow/models/blob/master/research/audioset/yamnet/yamnet_class_map.csv)
- [GCC-PHAT reference](https://ieeexplore.ieee.org/document/1165664)
- [Android AudioRecord docs](https://developer.android.com/reference/android/media/AudioRecord)
- [Hackathon brief](https://devpost.com) — Track 2: Classical Models — Vision & Audio

---

## Hackathon Submission Checklist

- [ ] Public GitHub repo with open-source license (Apache 2.0) detectable in About section
- [ ] README with: app description, team names/emails, setup instructions, run instructions
- [ ] Text description of features and functionality
- [ ] App runs on Samsung Galaxy S25 Ultra without modification
- [ ] LiteRT used for all ML inference
- [ ] Models run on Qualcomm Hexagon NPU (via NNAPI delegate)
- [ ] App is installable via APK or Play Store-ready build
- [ ] (Optional) Demo video showing real-time sound detection and spatial display
- [ ] (Optional) Unit tests for urgency classifier and audio preprocessor

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

## Development arc: v2 → current

The `v2localization` branch was the working baseline: dual-CAMCORDER capture, YAMNet classification, GCC-PHAT localization, FusionStage event tracker, urgency map, radar UI with per-event dots, foreground capture only. The pipeline ran end-to-end but the localization signal was geometry-only and the spatial display was static.

Since v2, the project has evolved along five parallel tracks. They are documented in detail in their respective sections below; this is the at-a-glance summary of *what changed* and *why*.

### 1. Localization: per-window geometric → per-label rotational-aperture Bayesian belief
- **Was (v2):** one `LocalizationResult` per window, attached to the top-1 YAMNet label. Direction was the geometric ITD lag from a single window — noisy, mirror-symmetric, and reset between windows.
- **Now:** `BeliefDistribution` (1° world-frame bins) per active YAMNet label. Each window's `bot_ild` ILD measurement and the IMU-fused yaw from `RotationVectorProvider` update every confident label's belief via cosine-bias likelihood × Bayesian decay. As the user rotates the phone, evidence from multiple device-frame views accumulates into a single world-frame peak per label — the rotational-aperture trick that resolves the front/back ambiguity geometric ITD can't.
- **Why:** raw within-pair ILD on this device's HAL is noisy and direction-ambiguous. Treating it as a likelihood and integrating across rotation is what makes a stable spatial track possible.

### 2. Multi-label fusion: top-1 attribution → multi-label noisy-OR pooling
- **Was:** top-1 YAMNet label gets the current direction; siblings discarded.
- **Now:** YAMNet's 521 raw classes are consolidated to 42 application-meaningful groups via `assets/yamnet_consolidation_map.json`. Per-group score is **top-k noisy-OR** over member sigmoid scores (top-3 by default), so co-firing siblings (Music + Classical, Dog + Animal, Speech + Shouting) compound into one strong group score instead of splitting evidence. Every group above the confidence threshold receives a belief update each window.
- **Why:** YAMNet is independent-sigmoid multi-label. Top-1 attribution effectively threw away the rest. Noisy-OR + per-label belief means a real sound — even if it triggers three sibling labels — converges to a single world-frame direction across all of them.

### 3. UI: static dots → per-label belief halos with IMU-anchored peak arrows
- **Was:** `RadarView` drew per-event dots positioned by ILD (Y-axis only), color-coded by urgency.
- **Now:** every active label renders its own **belief halo** — an arc segment per bin (alpha modulated by belief intensity) plus a peak-marker arrow at the smoothed argmax — all tinted by urgency color. World-frame angles are rotated by `-phoneYawDegrees` every frame at ~60 fps via a `Choreographer` callback so the halos stay visually anchored to the world as the phone turns. Per-event chips snap to the halo peak once the belief is sharp enough to trust; otherwise they fall back to the legacy ILD-only Y-axis position.
- **Why:** geometry from one window is unreliable; the halo is a literal visualization of *what the model believes about direction*, integrated over rotation. The user sees the system's confidence directly.

### 4. Personalization & alert lifecycle
- **Scene profiles** (`SoundProfile`, `ProfileManager`, `ProfileActivity`): user-managed list of profiles, each with a priority-label set and per-label urgency overrides. `FusionStage.applyProfile` translates these into `EventTracker` filters. The "Default" preset checks all 42 groups; users can create lighter profiles ("Home", "Office", "Café") that subscribe to only the labels they care about and re-prioritize per context. Profiles are draggable/reorderable and persist in `SharedPreferences`.
- **Pinned alerts** (`PinnedAlertTracker`): HIGH/CRITICAL events go into a persistent unacknowledged set keyed on `(label, urgency)`. They survive app dismiss/restart and stay visible until the user explicitly clears them — so an alert that fired while the user was looking away or away from the phone isn't silently lost.
- **Sound history** (`SoundHistoryManager`): rolling 24-hour log of HIGH/CRITICAL detections with per-event profile context, viewable in `HistoryActivity`. Same-label events within a dedup window collapse to a single entry to keep the log readable.
- **Urgency map customization**: `assets/urgency_map.json` is the default mapping; per-profile overrides layer on top. The `UrgencyPickerSheet` UI lets users reassign any of the 42 groups to any of the four tiers.

### 5. Background passive monitoring
- **Foreground service** `PassiveMonitoringService` (Android `foregroundServiceType="microphone"` with persistent notification): when the user puts the app in the background, `MainActivity` hands off to the service so the alert pipeline keeps running.
- **Recent simplification (post-multilabel):** the passive service runs **classification only** — no `LocalizationStage`, no IMU, no radar UI. HIGH/CRITICAL detections still surface via system notifications + haptic patterns + history + pinned alerts; but localization is foreground-only because the radar view is the only place direction-of-arrival is consumed, and skipping it cuts background battery cost roughly in half (the GCC-PHAT correlation pass and the IMU sensor listener are the bulk of non-classification CPU).
- **`AppForegroundTracker`**: arbitrates the hand-off — the service starts when the user backgrounds the app while listening, and is stopped before `MainActivity`'s pipeline restarts on resume.

### Things that did *not* change
- Dual-CAMCORDER capture topology and the "two `AudioRecord`, one per `BUILTIN_MIC` device address" recipe (still the only path that gives independent-stream stereo on the S25 Ultra).
- YAMNet inference path (NNAPI delegate first, multi-threaded CPU fallback).
- Diagnostic CSV logger format.
- `AudioCaptureManager` window cadence (1 s windows, 50% overlap, 8 Hz emission).

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

### Capture topology — verified on Samsung Galaxy S25 Ultra (Snapdragon 8 Elite)

The S25 Ultra exposes **two routable `BUILTIN_MIC` device addresses** via `AudioManager.getDevices(GET_DEVICES_INPUTS)`:
- `id=22 address='bottom'` — bottom-edge mic array
- `id=24 address='back'` — rear mic array near the camera bar

Public Android APIs do **not** expose 4 individually addressable mic streams on this device. Single-stream `AudioRecord` capture caps at stereo. However, **two concurrent `AudioRecord` instances bound to different `setPreferredDevice()` targets work**, giving 4 effectively-distinct channels.

The 4-channel positional mask `0x60000C` advertised by `AudioDeviceInfo` is rejected at the SDK layer (`AudioFormat.Builder` throws `UnsupportedOperationException: Unsupported channel configuration`). The 4-channel index mask `0xF` is accepted but the HAL only fills channels 0/1 regardless of source or preferred device. Both 4-channel paths are dead-ends.

### Recommended capture: dual-stream CAMCORDER (locked)

```kotlin
val format16kStereo = AudioFormat.Builder()
    .setSampleRate(16_000)
    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
    .build()

val bottomRec = AudioRecord.Builder()
    .setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
    .setAudioFormat(format16kStereo)
    .setBufferSizeInBytes(/* >= 1s of stereo s16 */)
    .build().apply { setPreferredDevice(bottomMicDevice) }

val backRec = AudioRecord.Builder()
    .setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
    .setAudioFormat(format16kStereo)
    .setBufferSizeInBytes(/* same */)
    .build().apply { setPreferredDevice(backMicDevice) }

bottomRec.startRecording()
backRec.startRecording()
// Read each on its own IO coroutine; align by frame count, not wall clock.
// Discard the first ~200 ms of frames per recorder as HAL warmup.
```

`AudioSource.CAMCORDER` chosen over `MIC` because it produces meaningful within-pair L/R differentiation (cross-correlation ~0.60) — needed for left/right within-array azimuth. MIC source within-pair correlates at 0.99 (L≈R), which makes within-pair GCC-PHAT useless. The cost of CAMCORDER is HAL post-processing (AGC/NS) on the audio that feeds YAMNet — accepted tradeoff. If classification quality A/B-tests poorly, swap `CAMCORDER` → `MIC` in `AudioCaptureManager` (one-line change) at the cost of losing left/right localization.

Verified physically independent across the two recorders (full check in `app/src/main/java/com/echoai/audio/MultiStreamProbe.kt`): pairwise normalized cross-correlation < 0.25 between recorders, no bit-identical pairs.

### Within-pair vs cross-pair signal characteristics — read this before writing the spatial code

Verified correlations on the S25 Ultra:

| Source | Within-pair (e.g., bottom_L↔bottom_R) | Cross-pair (bottom↔back) |
|---|---|---|
| `AudioSource.CAMCORDER` (chosen) | ~0.60 | ~0.12 |
| `AudioSource.MIC` | ~0.99 | ~0.22 |
| `AudioSource.UNPROCESSED` | ~1.00 (often bit-identical mono) | n/a |
| `AudioSource.VOICE_RECOGNITION` | ~1.00 (bit-identical mono) | n/a |

**Where the spatial signal lives with the locked CAMCORDER recipe:**

| Computation | Channels used | What it tells you | Sync quality |
|---|---|---|---|
| Cross-pair RMS ratio | `bottomRec` mono vs `backRec` mono | Front/back orientation; occlusion when user covers one array | Instantaneous, no sync needed |
| Cross-pair GCC-PHAT | `bottomRec` mono vs `backRec` mono, ~1 s windows | Coarse front-back direction-of-arrival from inter-array time delay | Buffer-level coherent only (~few ms jitter); search ±100 samples |
| Within-pair GCC-PHAT (bottom) | `bottomRec` L vs R | Left/right azimuth of the front (bottom-array) hemisphere | HAL-sample-accurate within recorder; CAMCORDER processing introduces some uncertainty |
| Within-pair GCC-PHAT (back) | `backRec` L vs R | Left/right azimuth of the rear (back-array) hemisphere | Same as bottom |

**Pipeline integration:**

1. **YAMNet (mono classification)**: downmix all 4 channels to one mono stream. More mic coverage = more robust to phone orientation in pocket/hand.
2. **Front/back + occlusion**: `bottomRMS / backRMS`. Hand covering the rear nulls `backRec`; bottom stays steady.
3. **Coarse DoA along front-back axis**: cross-pair GCC-PHAT over a 1 s window. The lag sign indicates which array heard the sound first.
4. **Within-array left/right azimuth**: within-pair GCC-PHAT on each recorder independently. Two estimates per window — useful for cross-validation: a real source seen from both arrays should give a consistent world-frame angle (after IMU rotation correction, see "IMU integration" below).

### IMU integration (planned, deferred)

`AudioWindow` carries an optional `worldOrientation: FloatArray?` (quaternion `[w, x, y, z]` in world frame). It defaults to `null` via `NullWorldOrientation`. When `Sensor.TYPE_ROTATION_VECTOR` is wired up, capture the latest quaternion at window emit time and pass it through.

What the IMU buys you (rotation only — translation needs SLAM, out of scope):

- **Artifact rejection**: a real sound source has a fixed world-frame direction. As the user rotates the phone, the device-frame DoA must rotate by the inverse rotation. If it doesn't, the estimate is a HAL channel-bias artifact, not a real direction.
- **Cross-window smoothing**: transform each window's noisy device-frame DoA into world frame, average over a sliding window (e.g., 2 s), transform back to device frame for display. Suppresses single-window jitter.
- **Continuity through head turns**: the radar should show the source fixed in the world while the user rotates around it.

`SoundEvent.devicePosition` carries device-frame estimates; `SoundEvent.worldOrientation` carries the orientation snapshot from the window the event was last refreshed in. World-frame conversion happens at the fusion stage when IMU is wired.

### Sample rate

Verified: dual-stream stereo capture works at both 16 kHz and 48 kHz. Use 16 kHz for the YAMNet feed (its required input rate; no resampling needed). If higher ITD resolution is later needed for cross-pair DoA, run a separate 48 kHz capture (one sample = 21 µs vs 62 µs at 16 kHz).

### 5th-channel optimization — DEAD on this device

Originally deferred as a 3rd concurrent stream using the `0x30` (FRONT|BACK) channel mask. **`MultiStreamProbe` ruled this out:** at 3+ concurrent `AudioRecord` clients, the S25 Ultra HAL accepts every client at the API level (`recordingState == RECORDING`) but **silently multiplexes a single capture buffer to most of them**. The cross-correlation matrix shows bit-identical sample buffers across most of the "different" streams. The active-mic metadata reports different addresses but is misleading.

The 2-stream cap is hard. Any future spatial enhancement must fit inside two concurrent recorders, or accept time-multiplexing (which loses transient events — see CLAUDE.md history for why).

The `0x30` mask itself does work in single-stream mode and would route to a different mic combination than `CHANNEL_IN_STEREO`. If a teammate's deep-learning approach ever wants that data offline, capture it standalone in a separate session.

### YAMNet input

- YAMNet expects a 1D waveform float array of 15,600 samples (0.975 s @ 16 kHz)
- Input normalization: values in [−1.0, 1.0]
- Feed it the **mono mix of all 4 channels** from the dual-stream capture, not just one stream

### Capture parameters summary

- **Sample rate:** 16,000 Hz (matches YAMNet; both `bottom` and `back` mic groups support it)
- **Channels per recorder:** 2 (`CHANNEL_IN_STEREO` / `0xC`)
- **Recorders:** 2 concurrent — one bound to each `BUILTIN_MIC` device. Hard 2-stream cap on this device.
- **Source:** `MediaRecorder.AudioSource.CAMCORDER` for both. (Do **not** use `UNPROCESSED` — collapses to single-mic mono. Do not use `VOICE_RECOGNITION` — bit-identical mono. `MIC` is the fallback if CAMCORDER classification quality is unacceptable.)
- **Window:** 1 s with 50% overlap, processed every 500 ms
- **Warmup discard:** ~200 ms after `startRecording` on each recorder before emitting the first window
- **Buffer size:** ~1 s of stereo s16 per recorder (≥ 64 KB)

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

Build in this order to enable incremental testing at each stage. Reflects the locked dual-CAMCORDER architecture and stage-(a) attribution from "Sound classification + localization fusion" below.

1. **[x] Mic capability probes** — `StereoMicTest`, `MicCapabilityProbe`, `MultiStreamProbe`. Resolved the recipe and concurrency cap.
2. **[x] AudioCaptureManager (dual CAMCORDER)** — `AudioCaptureManager.kt`, emits `AudioWindow` via `SharedFlow` with 1 s windows / 50% overlap / ~200 ms warmup discard. World-orientation hook plumbed through `WorldOrientationProvider` (currently `NullWorldOrientation`).
3. **[x] LocalizationStage with multi-scale** — `GccPhatLocalizer.kt` + `LocalizationStage.kt`. Cross-pair RMS ratio + cross-pair time-domain cross-correlation (~±100 sample search) + within-pair lag for each recorder (~±16 sample search). `localizeMultiScale` adds a 250 ms peak-energy sub-window pass alongside the 1 s pass for transient-event accuracy. Time-domain only; FFT/PHAT upgrade pending.
4. **[x] ClassificationStage with TFLite YAMNet** — `SoundClassifier` interface; `YamnetClassifier` implementation loads `assets/yamnet.tflite` + `assets/yamnet_class_map.csv`, tries the NNAPI delegate first (Hexagon NPU), falls back to multi-threaded CPU. Mono downmix of all 4 channels feeds the model. `StubSoundClassifier` retained as a fallback when the model fails to load.
5. **[x] FusionStage + EventTracker** — multi-label attribution (every confident YAMNet label gets the current direction). Rolling tracker keyed on label, 3 s memory, drops events not refreshed. Profile-aware: priority labels and urgency overrides applied via `applyProfile`.
6. **[x] Live UI in MainActivity** — Start/Stop toggle; `RadarView` with per-label belief halos, peak arrows, and urgency-colored chips. Pinned alerts banner, scene-chip strip with reorder, history button, profile editor.
7. **[x] CSV diagnostics** — `DiagnosticsLogger` writes per-window pipeline state (per-channel RMS, fb_bias, all six lag/confidence pairs at both scales, displayed azimuth) to `<app external files dir>/diag_<timestamp>.csv` while live capture is running. Pull via adb for offline analysis.
8. **[x] Urgency mapping** — `UrgencyClassifier.kt` + `assets/urgency_map.json`, ~50 curated YAMNet labels mapped to LOW/MEDIUM/HIGH/CRITICAL. Per-profile overrides layer on top.
9. **[x] Foreground service migration** — `PassiveMonitoringService` (`foregroundServiceType="microphone"`, persistent notification). Hand-off arbitrated by `AppForegroundTracker`. Service runs classification only; localization is foreground-only.
10. **[x] HapticManager** — `VibrationEffect` patterns per urgency. HIGH = short warning buzz, CRITICAL = stronger repeated buzz, LOW/MEDIUM silent. Cooldown to avoid sustained buzz.
11. **[x] IMU integration** — `RotationVectorProvider` reads `Sensor.TYPE_ROTATION_VECTOR` at SENSOR_DELAY_GAME and exposes yaw + quaternion via `WorldOrientationProvider`. Drives the per-label `BeliefDistribution` and the radar's world-frame halo rotation (60 fps via Choreographer).
12. **[x] Bayesian belief distribution** — `BeliefDistribution` per YAMNet label. Cosine-bias likelihood + multiplicative decay-toward-uniform; `update(bot_ild, yaw)` per window. `EventTracker` derives world-frame DoA from the per-label argmax with a smoothed peak (`smoothedPeakDegrees` rate-limits step-to-step movement).
13. **[x] Multi-label noisy-OR consolidation** — `YamnetConsolidationMap` collapses YAMNet's 521 raw classes into 42 application groups via top-k noisy-OR. Loaded from `assets/yamnet_consolidation_map.json`; falls back to raw 521 if loading fails.
14. **[x] Personalization** — `ProfileManager` + `SoundProfile` (priority labels, per-label urgency overrides, drag-reorder). `ProfileActivity` UI for CRUD. Default preset checks all groups; user creates lighter context profiles.
15. **[x] Pinned alerts + history** — `PinnedAlertTracker` (persistent HIGH/CRITICAL banners until dismissed). `SoundHistoryManager` (24 h rolling log, viewable in `HistoryActivity`).
16. **[ ] Spectral-shadow front/back** — high-band energy ratio. Required to overcome CAMCORDER AGC's RMS-bias suppression. See Future Work.
17. **[ ] Per-pair YAMNet (stage b)** — class-level front/back attribution. See Future Work.
18. **[ ] Polish & profiling** — end-to-end latency, NPU vs CPU benchmarks, accessibility review, A/B classification quality CAMCORDER vs MIC.

### Sound classification + localization fusion

YAMNet returns top-K labels for the entire window with no per-source segmentation. The current pipeline pairs that with multi-label noisy-OR consolidation and a per-label rotational-aperture Bayesian belief — so each detected sound class accumulates its own world-frame direction independently, even when several classes co-fire from different positions.

**Current — multi-label noisy-OR + per-label belief (shipped):**
- YAMNet's 521 classes are consolidated into 42 application groups (`YamnetConsolidationMap`, top-k noisy-OR over member sigmoid scores).
- Every group above `BELIEF_UPDATE_THRESHOLD = 0.3` receives a `BeliefDistribution.update(bot_ild, yaw)` for the current window. Inactive groups decay toward uniform; near-uniform beliefs are pruned to keep the per-label map bounded.
- `EventTracker` keeps a rolling 3 s memory keyed by label; refreshes update direction; un-refreshed events drop.
- Profile-aware filtering and urgency overrides applied via `FusionStage.applyProfile`.
- Solves the original v2 problem (single-dominant-source attribution) by giving every co-firing class its own direction track instead of forcing them to share or compete.

**Stage-(b) — per-pair classification [next upgrade, not shipped]:** run YAMNet twice per window — once on `bottomMono`, once on `backMono`. For each detected class, the pair with higher confidence indicates which side of the device the source is on. Gives semantic front/back attribution without depending on continuous geometry. ~30 ms extra inference per window on NPU.

**Stage-(c) and beyond — per-band GCC-PHAT, source separation:** out of scope for the prototype. A deep-learning joint classification+localization approach is the right home for that complexity; this baseline exists as the working demo and a comparison floor.

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

## Future Work & Exploration Avenues

The dual-CAMCORDER pipeline ships a working baseline. These are deferred upgrades, ordered by expected payoff. Read the empirical findings in "Known Constraints" first — most of these exist *because* the CSV diagnostics revealed limits in the current signal sources.

### Localization signal upgrades

1. **Spectral-shadow front/back** *(highest value — directly addresses the AGC limitation)*
    Diagnosed limitation: CAMCORDER's per-recorder AGC suppresses raw `frontBackBias` to ±0.17 max in real sessions, so the radar's y-axis dynamic range is fundamentally weak from RMS alone. **Implementation:** FFT (or band-pass filter) `bottomMono` and `backMono`, compute high-band (2–8 kHz) log-energy ratio. The phone body shadows high frequencies — sources in front have more high-freq energy in the bottom mics relative to back, vice versa for sources behind. Add `spectralFrontBack: Float` to `LocalizationResult`; combine with RMS bias for the radar y-axis (e.g., `0.4 * rmsBias + 0.6 * spectralBias`, tunable from CSV). Pulls in `com.github.wendykierp:JTransforms:3.1` as a dependency, which also covers (4).

2. **Per-pair YAMNet (stage b)**
    Run YAMNet twice per window — once on `bottomMono`, once on `backMono`. For each detected class, the pair with higher confidence indicates which side of the device the source is on. Gives **semantic per-class front/back** without depending on continuous geometry. Cost: ~30 ms extra inference per window on NNAPI; well under the 500 ms latency budget. Adds `bottomTopK` and `backTopK` fields to `ClassificationResult`; `FusionStage` picks the side per class when both pairs detect the same class above threshold.

3. **Per-band GCC-PHAT**
    Split `bottomMono` and `backMono` into frequency bands (low: 80–500 Hz, mid: 500–2 kHz, high: 2–8 kHz) and compute GCC-PHAT independently in each band. Different sources in different bands → different lags. With a class→typical-band lookup, attribute lag per detected class. Helps when sources don't overlap spectrally (siren + footsteps separate cleanly; speech + barking won't, since both span similar bands).

4. **FFT-based GCC-PHAT with PHAT weighting**
    Current `GccPhatLocalizer` is plain time-domain normalized cross-correlation despite the class name. PHAT-weighted FFT version is more robust to reverberation in indoor environments. Same JTransforms dep as (1). Drop-in replacement: same `localize(a, b, maxLag) → LagResult` signature; rewrite the body.

5. **Source separation preprocessor (conv-tasnet etc.)**
    Theoretically clean answer for overlapping-source attribution: separate the audio into N streams, classify and localize each independently. Likely blows the latency budget with on-device inference. The team's deep-learning joint classification+localization approach is probably the right home for this complexity, not the GCC-PHAT baseline.

### IMU integration

6. **Already shipped:** `RotationVectorProvider` wires `Sensor.TYPE_ROTATION_VECTOR` into `WorldOrientationProvider`. Yaw + quaternion expose live to `MainActivity` (per-window updates) and to `RadarView` directly (continuous Choreographer-driven refresh at ~60 fps). The whole `BeliefDistribution`-per-label design depends on this.

7. **Already shipped (alternate form):** world-frame smoothing happens inside the per-label `BeliefDistribution` rather than `EventTracker`. Each window's measurement contributes to a Bayesian posterior over world-frame angle bins; rotation provides multiple device-frame views of the same world-frame source, which is what concentrates the posterior. `smoothedPeakDegrees` rate-limits the peak angle's step-to-step movement for display stability.

8. **IMU-based artifact rejection [not shipped, next upgrade]**
    A real source has a fixed world-frame direction. As the user rotates the phone, the device-frame DoA must rotate by the inverse rotation. If it doesn't (e.g., a HAL channel-bias artifact stays at the same device-frame angle no matter how the phone moves), gate it out of the tracker. Free artifact filter on top of the existing belief pipeline.

### Window-length experiments

9. **Already shipped:** multi-scale 1 s + 250 ms peak-energy sub-window. CSV columns `*_lag_1s` and `*_lag_250` allow direct A/B.

10. **Try shorter scales (50–100 ms) for transient-only analysis.**
    Direct-path arrival of a sharp transient is in ~30 ms of audio; everything after is reverb tails. A 50 ms window centered on the energy onset would give the cleanest possible GCC-PHAT peak. Would need an onset detector (energy first-derivative threshold) to find the transient start. Useful specifically for door slams, claps, gunshots — events where direction matters most.

### Calibration & tuning knobs

These constants are placeholders; revisit with broader CSV data:

- `LAG_SCALE_FOR_AZIMUTH = 16f` in `DevicePosition` — lag value that maps to ±90° on the radar. CSVs show confident detections concentrate at ±8 samples; lowering to 8–10 makes the radar more dynamic but amplifies noise jitter on quiet windows. Tune from real data, not from geometry — the lag isn't pure geometric ITD on this device (see Known Constraints).
- `FRONT_BACK_SENSITIVITY = 8f` in `RadarView` — multiplier on `frontBackBias` before clamping. Tuned for CAMCORDER AGC's ±0.17 max bias. Lower this when (1) spectral-shadow front/back lands, since combined signal will have larger raw range.
- Azimuth confidence floor `0.15f` in `DevicePosition.azimuthDegrees()` — above this, an angle is shown. CSV suggests cleaner cut at 0.5 (silence ~0.39, real signal ~1.0), but more aggressive thresholding hides marginal events.
- `EventTracker.refreshConfidenceThreshold = 0.20f` and `staleAfterNanos = 3_000_000_000L` — tracker accepts refreshes above the threshold; events drop after the timeout. Worth A/B testing for the demo.
- `MAX_LAG_CROSS = 100`, `MAX_LAG_WITHIN = 16` in `LocalizationStage` — search ranges. CROSS is wide because cross-recorder sync jitter is buffer-level (~few ms = ~50+ samples; see Known Constraints). WITHIN is narrow because intra-recorder channels are sample-coherent.
- Empirical mic spacing on the S25 Ultra is unknown. The `LAG_SCALE_FOR_AZIMUTH` workaround sidesteps this, but a calibration session against known-direction sources would let us pin down the actual ITD-vs-angle relationship and whether HAL processing is adding inter-channel delay on top of geometric ITD.

### Diagnostics infrastructure

- **CSV diagnostics** (`DiagnosticsLogger.kt`) writes one row per window to `<app-private external storage>/diag_<timestamp>.csv` whenever live capture is running. Columns capture both 1 s and 250 ms localization scales for A/B analysis, plus per-channel RMS for calibration checks. Pull with `adb -s <serial> pull "/storage/emulated/0/Android/data/com.echoai/files/<file>"`. **Use this before guessing at calibration values.**
- **In-app diagnostic buttons** (Stereo mic test, mic capability probe, multi-stream concurrency probe) — kept around for re-running on different device units / firmware versions. The architecture lockdown was driven from these probes; treat them as the test suite for "is this device's HAL behaving like the S25 Ultra we calibrated against?"

### Production-grade plumbing — all shipped

- **Foreground service**: `PassiveMonitoringService` (`foregroundServiceType="microphone"`, persistent notification). `AppForegroundTracker` arbitrates the hand-off between foreground `MainActivity` and background service. Service runs classification only; localization is foreground-only to halve background battery cost.
- **Urgency mapping**: `UrgencyClassifier` + `assets/urgency_map.json`. Per-profile overrides layer on top.
- **Haptic patterns**: `HapticManager` — HIGH = short warning buzz, CRITICAL = stronger repeated buzz; LOW/MEDIUM silent. Cooldown to avoid sustained buzzing during repeated detections.
- **Settings / personalization**: `ProfileActivity` with full profile CRUD (priority labels, urgency overrides, drag-reorder), backed by `ProfileManager` and `assets/urgency_map.json`. `UrgencyPickerSheet` lets users reassign any of the 42 groups to any of the four tiers per profile.

---

## Known Constraints & Open Questions

- **Multi-mic access — RESOLVED:** the S25 Ultra exposes two routable `BUILTIN_MIC` device addresses (`bottom`, `back`). Concurrent dual-`AudioRecord` capture verified independent. Direct 4-channel capture is not reachable.
- **Recipe lockdown — RESOLVED:** `AudioSource.CAMCORDER` is the chosen source. CAMCORDER within-pair correlation is ~0.60 (usable for left/right ITD); MIC is ~0.99 (useless for ITD). Tradeoff is HAL post-processing on classification audio; revisit if YAMNet quality is unacceptable.
- **3+ stream concurrent capture — DEAD:** `MultiStreamProbe` confirmed that the HAL silently collapses streams beyond 2 (bit-identical sample buffers across "different" streams; metadata lies). Hard 2-stream cap. The 5th-channel `0x30` mask optimization that depended on a 3rd stream is also dead.

### Empirical limits surfaced by CSV diagnostics

These came out of running real test sessions through `DiagnosticsLogger`. They drive the priorities in "Future Work":

- **CAMCORDER per-recorder AGC suppresses RMS-based front/back signal.** `frontBackBias` rarely exceeds ±0.17 in real sessions because the HAL's automatic gain levels both arrays toward similar magnitudes regardless of source position. Visualization amplification (`FRONT_BACK_SENSITIVITY` in `RadarView`) helps, but cannot recover what AGC has already normalized. **Mitigation**: spectral-shadow front/back (Future Work § 1) — operates in the frequency domain so AGC can't flatten it — plus per-pair YAMNet (§ 2) for class-level side attribution.
- **Cross-recorder sync jitter dominates geometric front/back ITD.** Buffer-level coherence between the two `AudioRecord` instances has ~few-ms jitter (~50+ samples at 16 kHz). The geometric ITD between the bottom and back arrays is at most ~7 samples (~437 µs over ~15 cm). Signal-to-jitter ratio is unfavorable; cross-pair lag in CSVs has high variance even with strong sources. **Mitigation**: don't use cross-pair lag for direction-of-arrival; rely on cross-pair RMS ratio (current) or spectral-shadow (Future Work § 1).
- **Within-pair lag exceeds the pure-geometric ITD bound.** Confident detections show within-pair lags up to ±8 samples even though a 10 cm mic-spacing assumption implies max ±4. The HAL is adding inter-channel processing delay on top of (or in place of) geometric ITD. Treat within-pair lag as an empirical proxy for direction, not a physically-grounded ITD; calibrate angle mapping from observed lag distribution rather than from spacing × sound-speed math (which is what the current `LAG_SCALE_FOR_AZIMUTH` workaround does).
- **Confidence threshold has a clean separation point.** Quiet/silent windows produce GCC-PHAT correlation around 0.4 (random noise correlations); strong sources produce 0.9–1.0. The threshold for "trust this angle" cuts cleanly at ~0.5 in observed sessions. The current azimuth gate at `0.15` is conservative — raise it once we have more data on edge cases (and especially before the demo).

### Other open items

- **YAMNet class filtering:** YAMNet has 521 classes — many are irrelevant (e.g., musical instruments). Filter to a curated ~50-class subset for the urgency map.
- **Localization model:** If a pre-trained LiteRT localization model is not available from Qualcomm AI Hub, implement GCC-PHAT in Kotlin/JNI as a deterministic DSP solution and skip the second LiteRT model.
- **Background operation:** App must run as a Foreground Service with a persistent notification to prevent Android from killing the audio capture. With dual-stream capture, both `AudioRecord` instances run under the same service.

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

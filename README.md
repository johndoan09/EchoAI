# echoAI

**Real-time, on-device environmental sound awareness for deaf and hard-of-hearing users.**

Submitted to the **Google × Qualcomm Hackathon — Track 2: LiteRT Classical Models (Audio)**.
[DevPost](https://devpost.com/software/echoai-t7ol0v)
echoAI continuously listens to the world around the user, classifies what it hears, tracks where each sound is coming from, ranks each one by safety urgency, and alerts the user through visual indicators and haptic feedback — all running entirely offline on the phone, with model inference accelerated on the Qualcomm Hexagon NPU via Google LiteRT.

---

## The problem

Deaf and hard-of-hearing individuals face significant challenges when relying on hearing to detect environmental hazards like sirens, car horns, alarms, or approaching footsteps. While hearing aids offer a solution, they are not universally accessible. According to the NIH, among US adults who could benefit from hearing aids, only a small proportion have ever used them (16% for adults ages 20–69, and 30% for adults 70+). Furthermore, studies indicate that hearing aids can distort spatial localization, be physically uncomfortable to wear if not perfectly calibrated, and remain financially inaccessible for many.

In the absence of hearing aids, traditional device accessibility alerts (such as screen flashes or device vibrations) signal that a sound occurred, but they fail to convey *what* the sound is or *where* it is coming from. Knowing that a sound occurred is helpful, but knowing its identity and origin in a fast, convenient fashion is an equally crucial factor for safety. Existing external solutions often require dedicated hardware (smartwatches, custom hearing aids) or constant cloud connectivity—compromising privacy and failing completely offline.

EchoAI bridges this gap. By consolidating a vast range of sound identification with visual urgency cue localization, it provides a fully on-device, phone-native accessibility tool. EchoAI empowers users who have difficulty accessing or using hearing aids with an everyday assistant that not only instantly identifies urgent sounds, but directs the user right to the source to address them. Simply install the APK, grant the mic permission, and the phone becomes a continuously listening, spatial-aware safety assistant.

---

## Hardware & software requirements

- **Target device:** Samsung Galaxy S25 Ultra (Snapdragon 8 Elite). Other recent Snapdragon devices with NNAPI support also work but the dual-mic capture topology is calibrated against this specific HAL.
- **Android Studio:** Ladybug (2024.2) or newer.
- **Android SDK:** Platform 35 installed.
- **JDK:** 17 or newer.
- **Permissions requested at runtime:** `RECORD_AUDIO`, `VIBRATE`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, `POST_NOTIFICATIONS` (Android 13+).

---

## Setup from scratch

### Prerequisites (Zero to Native Build)
To build this project from absolute scratch, you will need the following core toolchain installed on your machine:
1. **Git**: For version control (e.g., running `brew install git` on macOS).
2. **Java Development Kit (JDK) 17 or newer**: Required to run the Gradle build system.
3. **Android Studio (Ladybug 2024.2 or newer)**: The most reliable way to fetch the required Android SDKs.
4. **Android SDK Platform 35**: Installed via Android Studio's SDK Manager.

### Step 1: Clone and Configure
First, clone the repository to your local machine:
```sh
git clone https://github.com/johndoan09/EchoAI.git
cd EchoAI
```

Next, ensure the project knows where your Android SDK is located. If it isn't at the default location, you must create a `local.properties` file at the root of the project. For a standard macOS installation, run:
```sh
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
```

From here, choose the flow that matches your current setup:

### Flow A: Build and Run on a Connected Device
This flow is ideal if you have the target hardware—like a Samsung Galaxy S25 Ultra—on hand and want to deploy the app directly.

1. On your phone, enable **Developer Options** and turn on **USB Debugging**.
2. Connect the phone to your machine via USB.
3. Verify the device is recognized by the Android Debug Bridge (ADB):
   ```sh
   ~/Library/Android/sdk/platform-tools/adb devices
   ```
4. Compile the application and install it directly to your connected phone:
   ```sh
   ./gradlew installDebug
   ```
*(Alternatively: Open the cloned folder in Android Studio, allow Gradle to sync its dependencies, select your physical device from the target drop-down in the top toolbar, and click the green "Play" button to build and launch).*

### Flow B: Build an APK (No Device Connected)
If you do not have a device plugged in and simply want to compile the standalone Android Package Kit (APK) file for sideloading or distribution later:

1. From the root directory of the repository, execute the assemble task:
   ```sh
   ./gradlew assembleDebug
   ```
   *Note: The first time you run a Gradle command, it will download necessary distributions and project dependencies (~500 MB). Subsequent builds are incremental and much faster.*
2. Once the build reads `BUILD SUCCESSFUL`, you can locate your compiled APK at the following path:
   `app/build/outputs/apk/debug/app-debug.apk`

---

## Run & usage

1. **Launch echoAI.** Grant `RECORD_AUDIO` (and `POST_NOTIFICATIONS` on Android 13+) when prompted.
2. **Pick a scene profile** from the chip strip at the top, or stick with "Default" (listens for everything).
3. **Tap the "Start Live" pill button** at the bottom. The radar starts sweeping; the per-channel mic activity reflects in the haloed dots.
4. **Make a noise.** Speak, clap, ring a doorbell, play a siren clip — the corresponding chip will appear on the radar, color-coded by urgency. HIGH/CRITICAL events trigger a vibration pattern and add an entry to the pinned alerts banner.
5. **Rotate the phone slowly** if the radar shows a dot but no direction arrow — rotational-aperture localization needs ~15° of cumulative rotation to converge a per-label world-frame direction. A "rotate to localize" hint appears automatically when rotation is needed.
6. **Tap the clock icon** (top-right) to view the 24-hour sound history.
7. **Manage scene profiles** via the Profile tab at the bottom — add, rename, edit priority labels, override per-label urgency, reorder, delete.
8. **Background the app** to enter passive monitoring. A notification confirms the service is running; HIGH/CRITICAL events still surface as system notifications + haptic alerts. Re-open the app to resume the full radar pipeline.

To stop monitoring entirely, tap "Pause Live" inside the app — this stops both the foreground pipeline and the passive service.

---

## Features

### Sound understanding

- **On-device LiteRT inference, NPU-accelerated.** YAMNet runs on the Qualcomm Hexagon NPU through the NNAPI delegate; falls back transparently to multi-threaded CPU when the NPU isn't available. No cloud round-trip; no audio ever leaves the device.
- **42-class consolidated taxonomy with multi-label noisy-OR pooling.** YAMNet's 521 raw classes are mapped into 42 application-meaningful groups (Speech, Dog, Siren, Doorbell, Glass break, …) using **top-k noisy-OR** over member sigmoid scores. This compounds evidence when multiple sibling labels co-fire (Music + Classical, Dog + Animal) instead of splitting it across them.
- **Concurrent multi-source detection.** Up to 1–3 simultaneous sounds are tracked per window. Each label maintains its own evidence over time rather than competing for a single "top-1" slot.
- **Streaming pipeline with overlapping windows.** 1-second audio windows at 50 % overlap → 8 Hz emission rate. Classification runs at 2 Hz (every 4th window) with cached reuse, leaving headroom for localization at full pipeline rate.

### Spatial localization

- **Dual-stream stereo capture (4 effective channels).** Two concurrent `AudioRecord` instances, each pinned to a different `BUILTIN_MIC` device address (bottom array vs. back array). This is the only path that yields independent-stream audio on the S25 Ultra HAL.
- **Multi-scale temporal analysis.** GCC-PHAT-style cross-correlation runs in parallel at two scales: a 1-second window for stable continuous sources (sirens, speech) and a 250 ms peak-energy sub-window for transient events (door slams, claps, alarms).
- **Per-label rotational-aperture Bayesian belief.** Each detected sound class gets its own world-frame belief distribution (1° bins). Per-window ILD measurements + IMU-fused yaw update the belief through a cosine-bias likelihood; rotation provides multiple device-frame views of the same world-frame source, which is what concentrates the posterior. The trick that makes a stable spatial track possible from noisy single-window measurements.
- **IMU fusion via `Sensor.TYPE_ROTATION_VECTOR`.** Android's fused gyro + accel + magnetometer orientation feeds the belief update at full pipeline rate and drives the radar's world-frame anchoring at ~60 fps via a Choreographer callback — so a fixed sound source stays put on the radar while the user rotates the phone.
- **Smart "rotate to localize" hint.** When confident audio is detected but the phone hasn't moved (cumulative rotation < 15° over 2 seconds), a non-blocking banner nudges the user to turn the phone — because rotational-aperture localization needs rotation to converge.

### Personalization

- **Scene profiles.** Users create context profiles (Default, Home, Office, Café, Outdoor, …) each with its own priority-label set and per-label urgency overrides. The "Default" preset listens for everything; lighter profiles subscribe to only the labels relevant to a given setting.
- **Per-profile urgency overrides.** Any of the 42 sound groups can be promoted or demoted across the four-tier urgency scale (CRITICAL / HIGH / MEDIUM / LOW) per profile. Urgency drives both color coding and haptic intensity.
- **Drag-to-reorder profile chips.** A horizontal chip strip on the main screen lets the user swap profiles instantly. Long-press to reorder; persists across launches.
- **Persistent settings.** Profiles, urgency overrides, and ordering survive app restarts via `SharedPreferences`.

### Alert delivery

- **Tiered haptic feedback.** `VibrationEffect` waveforms differentiated by urgency: CRITICAL is a stronger repeated pulse; HIGH is a single warning buzz; LOW/MEDIUM stay silent. Per-event cooldown prevents sustained buzzing on continuous detections.
- **Persistent pinned alerts.** HIGH/CRITICAL events accumulate into an unacknowledged set, keyed on `(label, urgency)`. They survive app dismiss, screen lock, and process restart — so a critical sound that fired while the user was looking away isn't silently lost. Cleared only when the user explicitly dismisses.
- **24-hour rolling sound history.** All HIGH/CRITICAL detections are logged with timestamp and the active profile at the time of detection. Same-label events within a dedup window collapse to keep the log readable; viewable in a dedicated history screen.
- **2D spatial radar UI.** Custom-drawn `Canvas` view: concentric range rings, FRONT/REAR/L/R labels, rotating sweep while listening, per-label belief halos (arc segments alpha-modulated by belief intensity), peak-direction arrows tinted by urgency color, and per-event labeled chips that snap to the halo peak once the belief is sharp enough to trust.
- **Background passive monitoring.** When the user backgrounds the app, capture transparently hands off to a foreground service (`foregroundServiceType="microphone"`, persistent notification). HIGH/CRITICAL detections still surface via system notifications and haptics — but localization is skipped in passive mode (it's the bulk of non-classification CPU and only the in-app radar consumes its output), roughly halving background battery cost.

### Engineering

- **Fully offline.** No network calls in the inference path. RECORD_AUDIO + VIBRATE + FOREGROUND_SERVICE_MICROPHONE + POST_NOTIFICATIONS — no INTERNET permission required.
- **NPU-first model loading.** `LiteRTModelManager` (via `YamnetClassifier`) attempts NNAPI delegate first with `acceleratorName = "qti-default"` and `EXECUTION_PREFERENCE_SUSTAINED_SPEED`; gracefully falls back to multi-threaded CPU on devices without the Hexagon NPU.
- **CSV diagnostics for offline analysis.** `DiagnosticsLogger` writes per-window pipeline state (per-channel RMS, front-back bias, all six lag/confidence pairs at both temporal scales, displayed azimuth, IMU yaw, belief peak/intensity) to app-private external storage while live capture is running. Pull via `adb` for offline tuning.
- **Built-in HAL probes.** `StereoMicTest`, `MicCapabilityProbe`, `MultiStreamProbe` characterize the device's audio capture topology — the architecture lockdown was driven from these probes; they double as a regression suite for new firmware versions.
- **Coroutine-based pipeline.** Concurrent classification and localization with `async`/`coroutineScope`; `repeatOnLifecycle(STARTED)` for clean foreground/background transitions; `collectLatest` keeps the pipeline current without backpressure stalls.

---

## Architecture (brief)

```text
mic capture (dual CAMCORDER, 16 kHz stereo × 2)
    │
    ├──► classification stage (YAMNet on NPU → 42-group consolidation, top-k noisy-OR)
    │
    ├──► localization stage (multi-scale GCC-PHAT, within-pair + cross-pair lags, RMS bias)
    │
    └──► IMU yaw (fused TYPE_ROTATION_VECTOR)
              │
              └──► per-label BeliefDistribution (cosine-bias × Bayesian decay)
                          │
                          └──► fusion stage (EventTracker, profile-aware urgency)
                                    │
                                    ├──► RadarView (per-label halo + peak arrow, 60 fps)
                                    ├──► HapticManager
                                    ├──► PinnedAlertTracker
                                    └──► SoundHistoryManager
```

For a deeper dive — including the development arc since the v2 baseline, the dual-stream capture lockdown, the cosine-bias likelihood derivation, and the empirical findings that drive each design choice — see `CLAUDE.md`.

---

## Notes: known constraints & empirical findings

These were diagnosed from real CSV diagnostic sessions on the S25 Ultra. They drive the design choices above.

- **Multi-mic access topology — locked.** The S25 Ultra exposes two routable `BUILTIN_MIC` device addresses (`bottom`, `back`). Two concurrent `AudioRecord` instances, each pinned to one of them via `setPreferredDevice`, give 4 effectively-distinct channels. Direct 4-channel capture (`channelMask = 0x60000C` or index mask `0xF`) is not reachable: the SDK rejects the positional mask and the HAL silently fills only L/R for the index mask.
- **3+ stream capture is dead.** `MultiStreamProbe` confirmed the HAL silently multiplexes a single buffer to multiple clients beyond 2 (bit-identical sample buffers across "different" streams). Hard 2-stream cap.
- **`AudioSource.CAMCORDER` is the chosen source.** `MIC` produces ~0.99 within-pair correlation (useless for ITD); `UNPROCESSED` and `VOICE_RECOGNITION` collapse to bit-identical mono. CAMCORDER gives ~0.60 within-pair correlation — usable for ITD-based azimuth — at the cost of HAL post-processing (AGC/NS) on the audio that feeds YAMNet.
- **CAMCORDER per-recorder AGC suppresses RMS-based front/back bias.** `frontBackBias` rarely exceeds ±0.17 in real sessions because each recorder's HAL-level AGC normalizes magnitudes independently. Mitigation: spectral-shadow front/back (operates in the frequency domain so AGC can't flatten it) — listed in Future Work.
- **Cross-recorder sync jitter dominates geometric front/back ITD.** The two `AudioRecord` instances are buffer-coherent but have ~few-ms relative jitter (~50+ samples at 16 kHz). The geometric ITD between bottom and back arrays is at most ~7 samples (~437 µs over ~15 cm). Cross-pair lag is therefore unreliable for direction — front/back relies on the RMS ratio (and, eventually, the spectral-shadow signal), not cross-pair lag.
- **Within-pair lag exceeds the pure-geometric ITD bound.** Confident detections show within-pair lags up to ±8 samples even though 10 cm mic-spacing implies max ±4. The HAL is adding inter-channel processing delay on top of (or in place of) geometric ITD. The lag is treated as an empirical proxy for direction, calibrated against observed lag distribution rather than physics.
- **GCC-PHAT confidence has a clean separation point.** Quiet windows produce correlation around 0.4 (random-noise correlations); strong sources produce 0.9–1.0. The "trust this angle" threshold cuts cleanly at ~0.5.
- **Localization needs rotation to converge.** A single-window ILD measurement is direction-ambiguous (mirror-symmetric across the long axis). The per-label belief distribution accumulates evidence across multiple device-frame views of the same world-frame source — which is why the UI prompts the user to rotate the phone when audio is active but the phone hasn't moved.

For the full design rationale, the unsuccessful experiments that led to the lockdown, and the calibration knobs available for tuning, see `CLAUDE.md` § "Audio Pipeline Details" and § "Known Constraints & Open Questions".

---

## Tests

The project ships 127 JVM unit tests covering all pure-Kotlin pipeline components. No device or emulator is needed.

```sh
# Run the full unit test suite
./gradlew testDebugUnitTest

# Expected output: BUILD SUCCESSFUL
# HTML report: app/build/reports/tests/testDebugUnitTest/index.html
```

### What's covered

| Test class | Component tested |
|---|---|
| `BeliefDistributionTest` | Bayesian belief math — uniform init, Gaussian-likelihood updates, decay, convergence to known world angle, rotational-aperture mirror resolution |
| `GccPhatLocalizerTest` | Cross-correlation engine — zero lag on identical signals, correct lag sign on shifted signals, confidence bounds, silent/empty edge cases |
| `LocalizationStageTest` | Full localization pipeline — ILD formula, front/back bias sign and magnitude, per-channel RMS, metadata passthrough, multi-scale energy picker |
| `ClassificationStageTest` | YAMNet wrapper — 4-channel downmix, float normalization, padding/trimming to `inputSampleCount`, frame number passthrough |
| `FusionStageTest` | End-to-end event lifecycle — event creation, temporal refresh, urgency sort, profile application (checkAll, priority filtering, CRITICAL auto-promotion) |
| `YamnetConsolidationMapTest` | Noisy-OR consolidation math — single/empty groups, top-k limiting, compounding vs max-pool, output clamping, size validation |
| `StubSoundClassifierTest` | Stub classifier branches — silence/ambient/loud thresholds, confidence ordering |
| `UrgencyTest` | Urgency enum — ordinal ordering, tint alpha, color differentiation |
| `DevicePositionTest` | Spatial conversion — ILD noise floor, degree mapping, lag confidence threshold, pair selection |
| `EventTrackerTest` | Rolling event tracker — confidence threshold, stale eviction, refresh, substring matching, urgency overrides, multi-label independent tracking |

---

## References

- [Google LiteRT documentation](https://ai.google.dev/edge/litert)
- [LiteRT Hugging Face Model Zoo](https://huggingface.co/litert-community)
- [Qualcomm AI Hub](https://aihub.qualcomm.com) — pre-optimized NPU-ready models
- [YAMNet on TF Hub](https://tfhub.dev/google/yamnet/1) — sound event classification model
- [YAMNet class map](https://github.com/tensorflow/models/blob/master/research/audioset/yamnet/yamnet_class_map.csv)
- [GCC-PHAT (Knapp & Carter, 1976)](https://ieeexplore.ieee.org/document/1165664) — generalized cross-correlation with phase transform
- [Android `AudioRecord` reference](https://developer.android.com/reference/android/media/AudioRecord)
- [Android `Sensor.TYPE_ROTATION_VECTOR` reference](https://developer.android.com/reference/android/hardware/Sensor#TYPE_ROTATION_VECTOR)

---

## Team

| Name | Email |
|---|---|
| Jason Lai | laijason150@gmail.com |
| John Doan | johndoaneo@gmail.com |
| Steven Li | stevenli45678@gmail.com |
| Ronald Zhang | ronaldarezhang@gmail.com |

---

## License

MIT — see [`LICENSE`](LICENSE).

# echoAI

Real-time on-device sound detection, localization, and urgency alerts for deaf and hard-of-hearing users. Submitted to the **Google × Qualcomm Hackathon — Track 2: LiteRT Classical Models (Audio)**.

> Status: bootstrap. The Android Studio project skeleton is in place. The LiteRT pipeline (audio capture → classification → localization → urgency → UI) is not yet wired — see `CLAUDE.md` §"Development Priorities & Build Order" for the planned sequence.

## Requirements

- Android Studio Ladybug (2024.2) or newer
- Android SDK Platform **35** installed
- JDK 17+
- Target hardware: Samsung Galaxy S25 Ultra (Snapdragon 8 Elite) — any device with NNAPI works for testing CPU/GPU paths

## Setup

1. Clone the repo.
2. Open the project root (`EchoAI/`) in Android Studio. Let it sync Gradle and download the SDK components it asks for.
3. Edit `local.properties` if your Android SDK lives somewhere other than `~/Library/Android/sdk`.
4. Plug in a device with USB debugging on, then **Run**.

## Build from CLI

```sh
./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/app-debug.apk`.

## Project layout

The package skeleton mirrors the architecture in `CLAUDE.md` — only `com.echoai.ui.MainActivity` exists today. Audio, ML, domain, and util packages will be added as their corresponding pipeline stages land.

## License

Apache License 2.0 — see `LICENSE`.

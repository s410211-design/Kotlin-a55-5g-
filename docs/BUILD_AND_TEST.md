# Build and test

## Baseline toolchain

- Android Gradle Plugin: 9.5.0
- Gradle Wrapper: 9.6.0
- JDK: 17
- compileSdk / targetSdk: 37
- minSdk: 34
- Kotlin: Android Gradle Plugin built-in Kotlin support
- Application ID: `com.s410211.a55wuwa`

AGP 9.5.0 supports API 37 and requires Gradle 9.6.0 and JDK 17. AGP 9 enables built-in Kotlin support, so this project does not apply the legacy `org.jetbrains.kotlin.android` plugin.

## Build locally

1. Install Android Studio with the Android 17 (Cinnamon Bun Preview) SDK Platform and Build Tools 37.0.0. Use the preview channel in SDK Manager.
2. Use JDK 17 for Gradle.
3. Open this repository in Android Studio, or run:

```bash
./gradlew --no-daemon clean testDebugUnitTest assembleDebug
```

On Windows, use `gradlew.bat`.

Debug APK output:

```
app/build/outputs/apk/debug/app-debug.apk
```

## Device profile

Target test device: Samsung Galaxy A55 5G, Taiwan model SM-A5560, Exynos 1480 / Xclipse 530. The supplied hardware configuration is 8 GB RAM and 256 GB storage. The app reads runtime memory and data-partition values from Android; those readings, not the nominal configuration, are the runtime evidence.

The owner reports Android 17 / One UI 9 as the target OS. The app currently reads Android release and API level from public Android APIs. Runtime One UI identification remains pending.

## Current implementation boundary

Included:
- Launchable Android Activity.
- Runtime device, RAM and internal data-partition profile.
- Explicit typed telemetry states.
- Separate instant-percentage and cumulative-counter GPU utilization parsers.
- Candidate GPU sysfs node catalog that does not claim retail-firmware availability.
- JVM unit tests and GitHub Actions build/test workflow.

Not yet included:
- Direct sysfs probing and permission classification.
- Shizuku Binder / UserService integration.
- Foreground monitoring service or HUD overlay.
- Game package/layer identification and validated game FPS.
- GPU/CPU setting writes, Boost journal or Restore.

These items are planned work and must not be represented as already working.

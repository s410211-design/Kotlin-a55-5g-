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

The owner reports Android 17 / One UI 9 as the target OS. The app reads Android release/API level, Build ID, Build display/fingerprint, and kernel release. It only displays a One UI version when an explicit version token is present in those public Build strings; otherwise it reports that the version could not be identified. The Shizuku-approved `ro.build.version.oneui` property is in the read-only command allowlist but is not queried until the Shizuku transport is integrated.

## Current implementation boundary

Included:
- Launchable Android Activity.
- Runtime device, RAM and internal data-partition profile.
- Build ID, build display, full build fingerprint, kernel release, and conservative One UI build-string hint.
- Explicit typed telemetry states.
- Separate instant-percentage and cumulative-counter GPU utilization parsers.
- GPU frequency parser that requires an explicit, source-verified input unit before converting to Hz.
- Bounded, read-only probe of the catalogued GPU sysfs candidates with errno-based result classification.
- A single-worker, bounded-rate GPU sampler that runs only while the dashboard Activity is visible, isolates per-node failures, and is shared by the manual refresh path.
- Typed read-only Shizuku command policy for fixed GPU-node and approved system-property reads.
- Bounded process executor with concurrent stdout/stderr draining, strict output caps, command deadlines, process termination, and explicit result states.
- Shell outcome mapper that converts command results into the same typed telemetry states as direct sysfs reads, without inferring permission denial or missing-node status from localized stderr text. The executor is transport-agnostic and is not yet wired to Shizuku.
- Candidate GPU sysfs node catalog that does not claim retail-firmware availability.
- JVM unit tests and GitHub Actions build/test workflow.

Not yet included:
- Shizuku Binder / UserService integration and runtime permission lifecycle.
- Foreground monitoring service or HUD overlay.
- Game package/layer identification and validated game FPS.
- GPU/CPU setting writes, Boost journal or Restore.

These items are planned work and must not be represented as already working.

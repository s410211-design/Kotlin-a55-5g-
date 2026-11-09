# Galaxy A55 5G — Wuthering Waves Monitor & Booster

## Project goal
Build a Kotlin Android application for Samsung Galaxy A55 5G that monitors device/GPU performance during Wuthering Waves and provides safe, verifiable performance actions.

Priority order:
1. Device and system safety.
2. Stability: no ANR, UI freeze, deadlock, unbounded threads, memory leaks, or excessive battery drain.
3. Accurate telemetry with explicit unavailable/error states.
4. Simple one-tap user experience.
5. Performance changes must be verified; never report success merely because a command was issued.

## Target device
- Primary target: Samsung Galaxy A55 5G, Taiwan model SM-A5560.
- SoC: Samsung Exynos 1480.
- GPU: Xclipse 530.
- Record actual Android version, One UI version, kernel version, build fingerprint, and device model at runtime.
- Do not assume all regional firmware builds expose identical sysfs nodes or permissions.

## Core features
- Automatic device and capability detection.
- Live GPU load and current frequency when readable and validated.
- GPU supported-frequency table, clearly distinguished from current frequency.
- GPU temperature only when a trustworthy sensor is exposed.
- CPU load/frequency, RAM, battery temperature, and network status where reliable.
- FPS only when measured from a valid source. Do not substitute display refresh rate or app Choreographer callbacks for game FPS.
- Shizuku/ADB-shell permission integration with clear setup, status, disconnect detection, and recovery.
- One-tap game launch, HUD/monitor toggle, safe Boost action, Stop, and Restore.
- Persistent foreground notification while monitoring/boost work is active, subject to Android notification and foreground-service rules.

## Telemetry correctness
Every reading must carry a typed state, source, unit, and timestamp. Suggested states:
- Value(value)
- Unsupported
- PermissionDenied
- NotFound
- InvalidData
- TemporarilyUnavailable
- Error

Never display unavailable data as zero. Distinguish permission denial from missing nodes and malformed output. Bound sampling rates and use one shared sampler rather than spawning readers per UI component.

### Candidate GPU interfaces (must be probed on the real device)
- `/sys/kernel/gpu/gpu_busy`
- `/sys/kernel/gpu/gpu_clock`
- `/sys/kernel/gpu/gpu_freq_table`
- `/sys/kernel/gpu/gpu_tmu`
- `/sys/kernel/gpu/gpu_model`
- `/sys/kernel/gpu/mem_info`
- `/sys/class/devfreq/*gpu*/`
- `/sys/class/devfreq/*sgpu*/`

These are candidates, not guarantees on retail firmware. Discover DRM devices by driver/name/symlink; never assume the GPU is always card0.

For cumulative busy/total counters, calculate utilization from deltas:
`100 * deltaBusy / deltaTotal`, and guard against zero, negative, reset, and overflow cases. A frequency table is a list of supported operating points, not the current clock. A reported clock may be cached or stale; expose source and freshness. Inspect `utilization_source` and `available_utilization_sources` before describing a utilization counter as hardware-derived. Do not infer the GPU model from a single sysfs string.

## Permissions and Shizuku
- ADB shell UID 2000 and Shizuku access are not root.
- SELinux and file permissions may still deny access.
- Detect Shizuku binder death, permission revocation, and reconnect safely.
- Use bounded exponential backoff for reconnect attempts; cancel retries when the service stops.
- Explain missing capability in plain language; do not ask users to type sysfs paths or shell commands in the app.

## Safe Boost contract
A Boost operation must expose these stages:
1. User request accepted.
2. Background operation started.
3. Command completed with exit status and bounded output.
4. Result read back and verified.
5. Before/after telemetry recorded where possible.

Separate “setting applied” from “performance improvement measured.” If the operation is unsupported or denied, say so. Never fake a success animation or claim an FPS gain without measurement.

Safety restrictions:
- No voltage modification.
- Never disable thermal protection or safety throttling.
- Do not force maximum GPU clocks indefinitely.
- Do not mass-kill background applications.
- Save original values before changes.
- Restore only values still matching the app's last applied value, so external changes are not overwritten.
- Maintain a restore journal and provide a reliable Stop/Restore path.

## Android service and notification
- Use a foreground service only for user-visible ongoing work.
- Notification must reflect actual monitor/boost state and provide a Stop/Restore action.
- Use a stable notification ID and avoid duplicate notifications.
- Respect Android 13+ notification permission behavior.
- Declare only the correct foreground-service type for the actual work; do not misuse `dataSync`, `systemExempted`, or `specialUse`.
- Do not promise that Samsung One UI will never stop the process.
- Distinguish one-shot Boost work from continuous monitoring.

## Architecture and stability
- Kotlin; use structured concurrency and explicit dispatchers.
- Keep shell/sysfs I/O off the main thread.
- Use SupervisorJob or equivalent isolation for independent samplers.
- Enforce single-flight/MUTEX for Boost and restore operations.
- Set command deadlines, cancellation, and output-size limits.
- Use a bounded ring buffer for telemetry/history.
- Ensure service shutdown cancels jobs, closes resources, releases temporary locks, and stops notifications.
- Avoid unbounded wakelocks, retry loops, threads, and polling frequency.

## User experience
- No terminal commands or sysfs paths required in normal use.
- Automatically detect device, available telemetry, and permission state.
- Explain permission setup and failures in plain Traditional Chinese.
- Main actions: Launch Wuthering Waves, Show/Hide HUD, Boost, Stop, Restore.
- Show unavailable capabilities honestly rather than hiding them or displaying fabricated values.

## Acceptance checklist
- [ ] AT-01: Correctly identifies SM-A5560 and records Android/One UI/kernel details.
- [ ] AT-02: GPU load is measured from a validated source; cumulative counters use deltas.
- [ ] AT-03: Current GPU frequency is distinguished from the supported frequency table.
- [ ] AT-04: Permission denied, missing node, invalid data, and unsupported are distinct states.
- [ ] AT-05: Ten rapid Boost taps result in at most one active operation.
- [ ] AT-06: Boost reports each stage and never claims success without verification.
- [ ] AT-07: Before/after measurements are labeled as measurements, not guarantees.
- [ ] AT-08: Restore returns app-managed settings to their original values without overwriting external changes.
- [ ] AT-09: Notification accurately reflects service state and Stop/Restore works.
- [ ] AT-10: UI remains responsive during slow or blocked shell operations.
- [ ] AT-11: Command timeout cancels cleanly and does not leak jobs.
- [ ] AT-12: Shizuku disconnect/revocation is detected and recovered without crash loops.
- [ ] AT-13: A failed sampler does not terminate unrelated telemetry.
- [ ] AT-14: Long-duration monitoring has bounded memory, thread count, and battery impact.
- [ ] AT-15: Stop cancels sampling, retries, foreground work, and temporary resources.
- [ ] AT-16: Final validation is performed on a real Galaxy A55 5G, not an emulator alone.

## Development sequence
1. Inspect repository and establish Kotlin/Gradle Android project structure.
2. Implement device identity and read-only diagnostics.
3. Implement typed telemetry and GPU capability probing.
4. Add Shizuku permission and binder lifecycle handling.
5. Build dashboard/HUD and foreground monitoring service.
6. Implement safe Boost/restore only for verified supported controls.
7. Run acceptance tests, then validate on SM-A5560 retail firmware.

## Device validation still required
The actual SM-A5560 firmware's readable GPU nodes, permission behavior, utilization source, frequency reporting, and thermal sensors must be confirmed on-device. Do not treat candidate paths as confirmed capabilities.

## Technical references
- Shizuku API: https://github.com/RikkaApps/Shizuku-API
- Exynos 1480 community kernel reference: https://github.com/Lyinceer/android_kernel_samsung_exynos_1480
- Android foreground services: https://developer.android.com/develop/background-work/services/foreground-services
- Android notification permission: https://developer.android.com/develop/ui/views/notifications/notification-permission

## Reference-project review notes
Potential implementation patterns worth evaluating:
- GameCore: typed sensor states, Shizuku integration, and conditional restore.
- FusionHUD: separation of sampling and HUD rendering, dynamic GPU path discovery.
- Shizuku API: binder lifecycle and permission handling.

Do not copy code without checking its license and preserving required notices. In particular, review GPL-3.0 obligations before deriving code from GPL-licensed projects. Avoid known pitfalls: calculating cumulative counters as raw ratios, stripping decimal/negative characters with simplistic regex, guessing clock units, treating unavailable as zero, or assuming a fixed DRM card index.

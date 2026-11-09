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


### GPU source semantics and parser consistency

Every candidate GPU node must be assigned an explicit parser based on verified kernel semantics, not inferred solely from a familiar filename. The source adapter must record source path, access channel, unit, timestamp, sample interval, data shape, and confidence/precision.

Supported data shapes must remain distinct:

- **Instant percentage:** a single sample is already a percentage. Validate its syntax and 0–100 range; do not calculate a second delta.
- **Cumulative busy/total counters:** the first sample establishes a baseline and must return an awaiting-sample state. Subsequent samples use `100 × Δbusy / Δtotal`. Reject or reset the baseline for counter rollback/reset, overflow, `Δtotal <= 0`, `Δbusy < 0`, or `Δbusy > Δtotal`. Do not clamp malformed values into a plausible percentage.
- **Cumulative time/vendor statistics:** establish the unit and counter semantics before calculating deltas. Do not mix ns, ms, jiffies, or other clock domains.
- **Current frequency:** parse separately from the supported frequency table. Preserve the raw unit and normalize only through an explicit, tested conversion. Track sample age and whether the value is current or last-known.

The direct sysfs reader and Shizuku shell reader must share the same parser and sampling state for a given source. The access channel may change; the meaning and calculation must not. In particular, a Shizuku fallback must not interpret a cumulative busy/total pair using the absolute ratio when the direct reader uses deltas.

A parent directory existing is not proof that a specific value node exists. Probe the exact file and distinguish confirmed missing, confirmed access denial, malformed value, transient failure, and indeterminate visibility. If permission restrictions prevent confirming existence, report restricted/unknown rather than asserting NotFound. Do not cache a permanent failure forever: capability probes should have an explicit invalidation/recheck policy for permission changes, firmware changes, and service reconnection.

### Exynos SGPU community-kernel observations

The following are observations from the community tree [Lyinceer/android_kernel_samsung_exynos_1480](https://github.com/Lyinceer/android_kernel_samsung_exynos_1480), not confirmation of the retail SM-A5560 kernel:

- `exynos_gpu_interface.c` implements `gpu_busy_show()` by formatting `last_status.busy_time / total_time × 100`; this output is a computed percentage, not a raw cumulative pair. The application must inspect the actual device node format before selecting a parser.
- `gpu_clock_show()` tries the device's `get_cur_freq` callback and falls back to `previous_freq` when that callback is unavailable or fails. A returned value may therefore be last-known rather than a fresh hardware clock. Do not label it unconditionally as a real-time frequency.
- `gpu_freq_table` represents supported operating points and must not be shown as the currently active frequency.
- In the reviewed `sgpu_utilization.c` source, the default utilization source is `jiffies`; the `hw_counter` entry has `hw_source_valid = false`. The existence of a source name or its appearance in `available_utilization_sources` does not prove that it is active or valid hardware telemetry.
- The inspected `gpu_busy_show()` division does not visibly guard a zero `total_time` in that function. The app must reject invalid, non-finite, out-of-range, or unavailable readings rather than trusting the node blindly.

For every real-device validation, record model, build fingerprint, Android/One UI/kernel versions, exact node path, raw sample format, access method, permission outcome, timestamps, and the current/available utilization sources when exposed. Do not write `utilization_source`, GPU min/max frequency, or other kernel controls as part of telemetry discovery. Any future write operation requires a separate approved proposal and its own safety/restore tests.

## FPS measurement and provenance

FPS must describe the target game's actual frame presentation, not merely a convenient timer or the app's own render loop.

- Keep display refresh rate as a separate display capability/measurement. It is never a fallback FPS value for Wuthering Waves.
- Choreographer callbacks in this app measure this app's own UI frame callbacks only. They cannot be labeled as another process's game FPS.
- `dumpsys gfxinfo` framestats may be useful for supported UI-rendered apps, but it is not a universal source for Vulkan, NativeActivity, or SurfaceView-heavy games. Do not use it as a silent fallback for Wuthering Waves.
- SurfaceFlinger `--latency` data must be parsed according to the exact Android-version output contract. A single frame's start-to-completion duration is not automatically the interval between consecutive presented frames. Prefer validated present-to-present timestamps or an appropriate FrameTimeline source when available.
- FrameTimeline and SurfaceFlinger access may be permission-, API-, layer-, and device-dependent. A successful shell command alone does not prove that the selected layer belongs to the game or that the timestamps represent actual presented frames.
- Resolve the foreground package and the correct game surface/layer; validate package ownership, layer identity, timestamp ordering, sentinels, refresh-period metadata, and stale-cache invalidation after package changes, rotation, surface recreation, or command failures.
- Every FPS snapshot must include measurement method, package/layer identity when known, access tier, sample window, timestamp, stale/fresh status, and limitations. If the source is not valid for the game, return unavailable rather than a guessed number.
- Do not average or smooth together values from different methods (for example, display refresh rate and SurfaceFlinger present timestamps) as though they were equivalent.

## Permissions and Shizuku
- ADB shell UID 2000 and Shizuku access are not root.
- SELinux and file permissions may still deny access.
- Detect Shizuku binder death, permission revocation, and reconnect safely.
- Use bounded exponential backoff for reconnect attempts; cancel retries when the service stops.
- Explain missing capability in plain language; do not ask users to type sysfs paths or shell commands in the app.

### Shell command allowlist and lifecycle

- Shell access is centralized behind a typed allowlist (for example, sealed command types); callers and UI components cannot submit arbitrary shell text.
- Validate every argument. Never concatenate untrusted package names, paths, or UI input into commands. Canonicalize filesystem paths and restrict them to explicitly allowed locations.
- Prefer read-only commands for diagnostics. Each allowed command declares its purpose, timeout, cancellation behavior, output cap, and exit-status/error mapping.
- Bound stdout and stderr independently. Do not persist raw dumpsys output, window titles, installed package inventories, or other potentially sensitive data in logcat; retain only the minimum sanitized diagnostic summary.
- Track Binder liveness, Shizuku permission, UserService binding, remote-process liveness, and command success as separate states. A live Binder does not mean the UserService is ready, and permission grant does not prove that a particular command is allowed by SELinux.
- Binding/rebinding must be idempotent, bounded, cancellable, and performed off the main thread. Binder death or permission revocation must invalidate stale remote references and stop affected jobs safely.
- Respect the selected privilege policy. A forced Shizuku mode must fail closed when Shizuku is unavailable; do not silently execute the same operation under a different privilege tier.

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

- [ ] AT-17: GPU data-source adapters distinguish instantaneous percentages, cumulative counters, and cumulative time/vendor statistics; each uses its matching parser.
- [ ] AT-18: Direct sysfs and Shizuku reads of the same cumulative source share the same delta baseline and produce equivalent results; the first sample awaits a second sample.
- [ ] AT-19: Counter reset/rollback, zero or negative delta total, negative busy delta, busy delta greater than total delta, overflow, malformed text, and non-finite values never become a normal 0–100% reading.
- [ ] AT-20: GPU node discovery checks the exact target file; an existing parent directory alone does not prove that the value node exists or that access is denied.
- [ ] AT-21: Current GPU frequency is tagged with source and freshness; a last-known/fallback clock is never presented as a confirmed real-time frequency.
- [ ] AT-22: Exynos utilization-source metadata is treated as kernel/build-specific; source names and available-source lists alone do not prove valid hardware-counter telemetry.
- [ ] AT-23: Game FPS is calculated from validated game presentation intervals; single-frame render duration, app Choreographer callbacks, and display refresh rate are not mislabeled as game FPS.
- [ ] AT-24: FPS layer/package matching, sentinel filtering, timestamp ordering, sample windows, stale-state handling, and cache invalidation are covered by parser/unit tests.
- [ ] AT-25: Shell commands are allowlisted and parameter-validated; timeout, cancellation, bounded output, Binder death, permission revocation, and forced privilege policy are tested.

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
- [GameCore](https://github.com/Dreamucxe/GameCore) (MIT): Kotlin/Shizuku layering, typed GPU read states, typed shell commands, write ledger, conditional restore, and JVM tests. Code review found a consistency hazard: its direct cumulative `gpubusy` reader uses deltas, while its elevated-shell fallback parses an absolute busy/total ratio. Do not reproduce that inconsistency.
- [DeviceInsight](https://github.com/zenithblue-oss/DeviceInsight) (GPL-3.0-or-later): FPS source separation, SurfaceFlinger layer discovery, Shizuku shell gateway, privilege policy, and parser tests. Its legacy SurfaceFlinger parser's frame-start-to-completion duration is not automatically present-to-present FPS. Its gfxinfo path is not a universal Vulkan/Native game source. Its shell gateway accepts command strings, so this project must retain a stricter typed allowlist.
- [MonitoredCheck](https://github.com/GenzPx/MonitoredCheck) (MIT): typed `Reading<T>` statuses, hardware repositories, monitoring services, and test organization. Avoid its absolute-ratio treatment of cumulative `gpubusy` and lossy digit-filter parsing.
- [FusionHUD](https://github.com/The412Banner/FusionHUD) (GPL-3.0 plus an additional attribution requirement stated in its `ATTRIBUTION.md`): HUD rendering, shared snapshots, and separation of sampling from drawing. Its FPS counter is fed from a Winlator rendering/present callback; that does not make it a generic third-party Android game FPS API. Do not port its legacy absolute-ratio or lossy parser behavior.
- [Shizuku-API](https://github.com/RikkaApps/Shizuku-API) (MIT): primary API reference for Binder, permission, and UserService lifecycle.
- [Exynos 1480 community kernel](https://github.com/Lyinceer/android_kernel_samsung_exynos_1480): research reference for SGPU sysfs semantics only; it does not prove the behavior of SM-A5560 retail firmware.

Do not copy code without checking the current license, copyright notice, attribution conditions, and distribution obligations. Keep each upstream project as an architecture/behavior reference unless an explicit code-use and license review is completed. Known pitfalls to test against include: cumulative counters parsed as absolute ratios; direct and Shizuku paths using different algorithms; directories mistaken for existing value nodes; frequency fallbacks presented as fresh; simplistic regex stripping decimal/negative signs; guessed clock units; app Choreographer or display refresh mislabeled as game FPS; and assuming a fixed DRM card index.

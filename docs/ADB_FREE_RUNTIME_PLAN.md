# ADB-Free Runtime Plan and Final SM-A5560 Validation

**Target:** Samsung Galaxy A55 5G, SM-A5560, Exynos 1480, Xclipse 530  
**Branch:** \`foundation/stage-0\`  
**Purpose:** finish source discovery and validation before removing the user's dependence on a PC and USB ADB.

## 1. Meaning of “ADB-free”

This project uses two different ideas which must not be conflated:

1. **No PC / no USB ADB during normal use.** This is the project target. The installed APK must run its ordinary monitoring functions on the phone without a computer, USB cable, terminal, or user-entered shell commands.
2. **No privileged shell transport at all.** This is stricter. Ordinary Android applications cannot bypass Linux DAC or SELinux policy to read protected sysfs nodes. If an item is denied to the app UID and is not exposed by a public Android API, the app must report that capability as unavailable. It must not fake a value.

Shizuku is an optional elevated-shell transport, not a way to make a protected node accessible to an ordinary app. Its official documentation says wireless-debugging startup is available on Android 11+ without connecting a computer, but the service must be started again after reboot. This is a no-PC setup path, not proof that every command is permitted by Samsung's SELinux policy:
- https://shizuku.rikka.app/zh-hant/guide/setup/

**Default product policy:** direct app/public-API mode first. No PC ADB dependency at runtime. Shizuku is optional and must be off unless separately integrated, authorized, and tested. If the product requirement is “no ADB or Shizuku mechanism of any kind,” omit Shizuku entirely and expose only data available to the ordinary app.

## 2. Source tiers

| Tier | Source | Expected availability | Product behavior |
|---|---|---|---|
| A | Android public APIs: device profile, memory, battery broadcasts, display, PowerManager thermal status/headroom | Ordinary app, subject to API limits | Preferred source; report actual API and freshness |
| B | Exact read-only sysfs nodes opened by the app UID | Firmware / SELinux dependent | Probe on-device; show PermissionDenied/Unsupported/InvalidData rather than 0 |
| C | Shizuku UserService / shell UID | Optional user-authorized service | Separate permission and service states; never silently fall back from forced mode |
| D | PC ADB | Development and one-time hardware diagnosis only | Must not be a runtime dependency or required for normal users |

The public Android PowerManager API supplies thermal status and thermal-headroom estimates; it does not return the AP/GPU/BAT/PA/SKIN sensor temperatures printed by \`dumpsys thermalservice\`. See:
- https://developer.android.com/reference/android/os/PowerManager

## 3. Hardware evidence already available

The user-provided ADB runs establish:

- Device model \`SM-A5560\`, Android 17 / API 37 and build display ID \`CP2A.260605.016.A5560ZHUIEZJ1\`.
- GPU identity node reported \`Samsung Xclipse 530\`.
- \`gpu_busy\` is readable as Android shell UID 2000 and returned varying integer values (11–73 across the visible 32 samples). The raw shape is consistent with a single 0–100 percentage, but retail-kernel provenance/precision is not established.
- \`gpu_clock\` reported 450000 in the visible 32 samples. devfreq \`cur_freq\` mostly reported 450000 and once 544000; \`min_freq\` and \`max_freq\` both reported 450000. These sources conflict and their unit/freshness are not established.
- \`gpu_tmu\` returned 48–55 raw values in the visible sample window. Its sensor mapping and unit are unknown; do not display as °C.
- The nine advertised GPU operating points were read from two frequency-table nodes. They are supported levels, not proof of current frequency.
- CPUFreq \`policy0\` and \`policy4\` \`scaling_cur_freq\` values were readable through ADB shell. These are kernel-reported policy frequencies, not guaranteed hardware clock measurements.
- Thermal HAL returned per-sensor temperatures through \`dumpsys thermalservice\`; this is not available through the public PowerManager API as per-sensor Celsius values.
- ADB shell UID 2000 with SELinux Enforcing is not root and does not prove that the ordinary app UID can open the same files.

Only samples 1–32 of the user's nominal 60-sample run were present in the conversation. Do not claim the 60-sample trace is complete until the full report is inspected.

## 4. Final one-time inventory

Use \`tools/A55_Final_ReadOnly_Node_Inventory.bat\` for one consolidated, read-only inventory. It collects:

- Exact build, fingerprint, kernel and security identity.
- Full GPU directory metadata, including names and permissions of candidate/control nodes.
- Content of explicitly selected telemetry/metadata nodes only. It does not write to or read control-only nodes such as IFPO, swap, LLC or boost switches.
- Every devfreq directory and selected read-only attributes.
- Every CPUFreq policy and selected read-only attributes, including policy/core mapping and scaling-driver information.
- Thermal zones, cooling devices and Thermal HAL.
- Power-supply attribute visibility and Android battery service.
- RAM and GPU memory information.
- One persistent shell session with 60 timestamped samples of GPU busy, both GPU clock sources, target-frequency candidate, GPU TMU and observed CPUFreq policies.
- Thermal service before and after the sample window.

Run it with the phone connected and authorized, preferably while Wuthering Waves is in the foreground. It is a one-time final diagnosis, not the app's runtime workflow. Inspect the entire TXT report; inaccessible files are valid evidence and must remain classified as restricted/unknown, not silently omitted.

## 5. Required validation gates before calling the runtime PC-ADB-free

### Gate A — source inventory
- [ ] Final inventory report has all sections and 60 samples.
- [ ] Exact GPU and devfreq paths, permissions and raw formats are documented.
- [ ] CPUFreq policies include \`affected_cpus\` / \`related_cpus\`, driver and available limits.
- [ ] Thermal-zone and cooling-device access outcomes are recorded.
- [ ] Every candidate is classified as confirmed readable, confirmed denied, confirmed missing, malformed, transient, or indeterminate.

### Gate B — ordinary app UID
Install the debug APK directly on the phone (file transfer / browser download / package installer; **not** \`adb install\`). In the app:
- [ ] Confirm model/build identity.
- [ ] Probe every exact catalogued GPU path using the app UID.
- [ ] Probe CPUFreq policy nodes using the app UID.
- [ ] Read public Thermal status/headroom.
- [ ] Capture source path, raw value, unit, timestamp, access tier and typed failure state.
- [ ] Compare a foreground game sample with the ADB diagnosis; do not assume matching access or freshness.
- [ ] Confirm the app continues to work when the USB cable is disconnected and the computer is off.

### Gate C — measurement semantics
- [ ] \`gpu_busy\` stays in range and varies with GPU workload; retail kernel source or independent cross-check establishes what its number measures before it is labelled GPU utilization percent.
- [ ] Compare \`gpu_clock\`, devfreq \`cur_freq\`, \`target_freq\`, \`min_freq\` and \`max_freq\` with timestamps. Keep them as separate readings until one current-frequency source is validated.
- [ ] Confirm GPU frequency units from the actual device ABI/driver or a trustworthy same-build source. Do not infer units by magnitude.
- [ ] Establish GPU TMU mapping and unit before displaying a temperature.
- [ ] CPU policy values are labelled kernel-reported kHz-derived frequency and mapped to related CPUs. Do not label as exact hardware clock.
- [ ] Thermal API status/headroom are not shown as Celsius temperatures.

### Gate D — app reliability
- [ ] Unit tests pass for all parsers, reset/overflow/range cases and permission mapping.
- [ ] Debug APK assembles from the documented toolchain.
- [ ] Manual installation succeeds without ADB.
- [ ] At least 10 minutes of foreground monitoring produce no crash, ANR, thread growth or unbounded history.
- [ ] Stop/leave-screen lifecycle cancels samplers; re-entry does not create duplicate workers.
- [ ] USB disconnection and PC shutdown do not interrupt supported Tier A/B monitoring.
- [ ] If Shizuku is later added, Binder, permission, UserService and command success are individually tested, with forced mode failing closed.

## 6. Exit criteria

The project can remove PC ADB from the normal user workflow when Gates B and D pass on the physical SM-A5560. It may remove Shizuku too only if all required product functions are available through Tier A and Tier B.

If a GPU sysfs node is denied to an ordinary app, there are only honest choices:
1. Mark that metric unavailable in ADB-free mode.
2. Offer optional Shizuku mode (which still uses Android's privileged shell service and wireless-debugging activation).
3. Require root (not recommended for this project).

There is no safe Kotlin trick that grants an ordinary app permissions denied by SELinux. The product must not claim “ADB-free full GPU telemetry” until the installed APK itself has demonstrated access.

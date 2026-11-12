package com.s410211.a55wuwa.telemetry

enum class GpuNodeRole {
    UTILIZATION_CANDIDATE,
    CURRENT_CLOCK_CANDIDATE,
    SUPPORTED_CLOCK_TABLE,
    TEMPERATURE_CANDIDATE,
    GPU_IDENTIFICATION,
    MEMORY_INFORMATION,
    UTILIZATION_SOURCE,
    AVAILABLE_UTILIZATION_SOURCES,
    MINIMUM_CLOCK_LIMIT,
    MAXIMUM_CLOCK_LIMIT,
    TARGET_CLOCK_CANDIDATE
}

data class GpuNodeCandidate(
    val path: String,
    val role: GpuNodeRole,
    val note: String
)

object GpuNodeCatalog {
    /**
     * Probe candidates only. Presence, format, permissions, active semantics
     * and freshness must be checked on retail SM-A5560 firmware.
     */
    val candidates: List<GpuNodeCandidate> = listOf(
        GpuNodeCandidate(
            "/sys/kernel/gpu/gpu_busy",
            GpuNodeRole.UTILIZATION_CANDIDATE,
            "SM-A5560 ADB samples vary within 0..100; treated as candidate instantaneous percentage, with source precision still to verify."
        ),
        GpuNodeCandidate(
            "/sys/kernel/gpu/gpu_clock",
            GpuNodeRole.CURRENT_CLOCK_CANDIDATE,
            "May return a cached/last-known frequency; freshness must be validated."
        ),
        GpuNodeCandidate(
            "/sys/class/devfreq/10e00000.sgpu/cur_freq",
            GpuNodeRole.CURRENT_CLOCK_CANDIDATE,
            "SM-A5560 observed devfreq current-frequency node; unit and freshness remain unverified."
        ),
        GpuNodeCandidate(
            "/sys/class/devfreq/10e00000.sgpu/min_freq",
            GpuNodeRole.MINIMUM_CLOCK_LIMIT,
            "Read-only policy limit for frequency diagnostics; not the current frequency."
        ),
        GpuNodeCandidate(
            "/sys/class/devfreq/10e00000.sgpu/max_freq",
            GpuNodeRole.MAXIMUM_CLOCK_LIMIT,
            "Read-only policy limit for frequency diagnostics; not the current frequency."
        ),
        GpuNodeCandidate(
            "/sys/class/devfreq/10e00000.sgpu/target_freq",
            GpuNodeRole.TARGET_CLOCK_CANDIDATE,
            "Requested/target frequency candidate; not proof of hardware current frequency."
        ),
        GpuNodeCandidate(
            "/sys/kernel/gpu/gpu_freq_table",
            GpuNodeRole.SUPPORTED_CLOCK_TABLE,
            "Supported operating points only; not the current clock."
        ),
        GpuNodeCandidate(
            "/sys/class/devfreq/10e00000.sgpu/available_frequencies",
            GpuNodeRole.SUPPORTED_CLOCK_TABLE,
            "Devfreq supported operating points only; unit still needs firmware-specific confirmation."
        ),
        GpuNodeCandidate(
            "/sys/kernel/gpu/gpu_tmu",
            GpuNodeRole.TEMPERATURE_CANDIDATE,
            "Temperature semantics and unit are not yet verified."
        ),
        GpuNodeCandidate(
            "/sys/kernel/gpu/gpu_model",
            GpuNodeRole.GPU_IDENTIFICATION,
            "Identification hint only; do not infer GPU model from one string."
        ),
        GpuNodeCandidate(
            "/sys/kernel/gpu/mem_info",
            GpuNodeRole.MEMORY_INFORMATION,
            "Format and units require device-side validation."
        ),
        GpuNodeCandidate(
            "/sys/kernel/gpu/utilization_source",
            GpuNodeRole.UTILIZATION_SOURCE,
            "Current utilization source identifier; must be read together with the available-source list."
        ),
        GpuNodeCandidate(
            "/sys/kernel/gpu/available_utilization_sources",
            GpuNodeRole.AVAILABLE_UTILIZATION_SOURCES,
            "Advertised choices do not prove that a hardware counter is active or valid."
        )
    )
}

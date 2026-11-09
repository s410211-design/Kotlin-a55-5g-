package com.s410211.a55wuwa.telemetry

enum class GpuNodeRole {
    UTILIZATION_CANDIDATE,
    CURRENT_CLOCK_CANDIDATE,
    SUPPORTED_CLOCK_TABLE,
    TEMPERATURE_CANDIDATE,
    GPU_IDENTIFICATION,
    MEMORY_INFORMATION,
    UTILIZATION_SOURCE,
    AVAILABLE_UTILIZATION_SOURCES
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
            "May expose an instantaneous percentage on some Exynos SGPU kernels; retail firmware not verified."
        ),
        GpuNodeCandidate(
            "/sys/kernel/gpu/gpu_clock",
            GpuNodeRole.CURRENT_CLOCK_CANDIDATE,
            "May return a cached/last-known frequency; freshness must be validated."
        ),
        GpuNodeCandidate(
            "/sys/kernel/gpu/gpu_freq_table",
            GpuNodeRole.SUPPORTED_CLOCK_TABLE,
            "Supported operating points only; not the current clock."
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

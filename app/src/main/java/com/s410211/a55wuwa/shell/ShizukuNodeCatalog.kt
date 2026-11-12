package com.s410211.a55wuwa.shell

/**
 * Stable IDs shared with the AIDL UserService. Only paths discovered or
 * explicitly approved for read-only diagnostics are included here.
 *
 * This catalog is deliberately read-only. Frequency-control writes will use
 * a separate policy after real-device UID and write semantics are verified.
 */
enum class ShizukuNode(val id: Int, val path: String) {
    GPU_BUSY(1, "/sys/kernel/gpu/gpu_busy"),
    GPU_CLOCK(2, "/sys/kernel/gpu/gpu_clock"),
    GPU_FREQ_TABLE(3, "/sys/kernel/gpu/gpu_freq_table"),
    GPU_TEMPERATURE(4, "/sys/kernel/gpu/gpu_tmu"),
    GPU_MODEL(5, "/sys/kernel/gpu/gpu_model"),
    GPU_MEMORY_INFO(6, "/sys/kernel/gpu/mem_info"),
    GPU_MIN_CLOCK(7, "/sys/kernel/gpu/gpu_min_clock"),
    GPU_MAX_CLOCK(8, "/sys/kernel/gpu/gpu_max_clock"),
    GPU_DEVFREQ_CURRENT(9, "/sys/class/devfreq/10e00000.sgpu/cur_freq"),
    GPU_DEVFREQ_TARGET(10, "/sys/class/devfreq/10e00000.sgpu/target_freq"),
    GPU_DEVFREQ_MIN(11, "/sys/class/devfreq/10e00000.sgpu/min_freq"),
    GPU_DEVFREQ_MAX(12, "/sys/class/devfreq/10e00000.sgpu/max_freq"),
    GPU_DEVFREQ_AVAILABLE(13, "/sys/class/devfreq/10e00000.sgpu/available_frequencies"),

    CPU_POLICY0_CURRENT(20, "/sys/devices/system/cpu/cpufreq/policy0/scaling_cur_freq"),
    CPU_POLICY0_MIN(21, "/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq"),
    CPU_POLICY0_MAX(22, "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq"),
    CPU_POLICY4_CURRENT(23, "/sys/devices/system/cpu/cpufreq/policy4/scaling_cur_freq"),
    CPU_POLICY4_MIN(24, "/sys/devices/system/cpu/cpufreq/policy4/scaling_min_freq"),
    CPU_POLICY4_MAX(25, "/sys/devices/system/cpu/cpufreq/policy4/scaling_max_freq"),
    CPU_POLICY0_AVAILABLE(26, "/sys/devices/system/cpu/cpufreq/policy0/scaling_available_frequencies"),
    CPU_POLICY4_AVAILABLE(27, "/sys/devices/system/cpu/cpufreq/policy4/scaling_available_frequencies");

    companion object {
        private val byId = values().associateBy(ShizukuNode::id)
        private val byPath = values().associateBy(ShizukuNode::path)

        fun fromId(id: Int): ShizukuNode? = byId[id]
        fun fromPath(path: String): ShizukuNode? = byPath[path]
    }
}
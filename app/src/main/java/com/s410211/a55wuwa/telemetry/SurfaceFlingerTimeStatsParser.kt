package com.s410211.a55wuwa.telemetry

data class GameFpsSnapshot(
    val packageName: String,
    val layerName: String,
    val totalFrames: Long,
    val cumulativeAverageFps: Double
)

/**
 * Parses SurfaceFlinger --timestats output. Only a layer whose name contains
 * the selected game package is accepted; display refresh and app UI callbacks
 * are never used as fallback game FPS.
 */
object SurfaceFlingerTimeStatsParser {
    private val framesPattern = Regex("""(?m)^\s*totalFrames\s*=\s*(\d+)\s*$""")
    private val fpsPattern = Regex("""(?m)^\s*averageFPS\s*=\s*([0-9]+(?:\.[0-9]+)?)\s*$""")

    fun parse(
        output: String,
        packageName: String,
        sampledAtMillis: Long
    ): Reading<GameFpsSnapshot> {
        if (!PACKAGE_PATTERN.matches(packageName)) {
            return Reading.InvalidData(
                "SurfaceFlinger --timestats",
                sampledAtMillis,
                "遊戲 package name 格式無效"
            )
        }
        if (output.isBlank() || output.length > MAX_OUTPUT_CHARS) {
            return Reading.InvalidData(
                "SurfaceFlinger --timestats",
                sampledAtMillis,
                "timestats 輸出為空或超過解析上限"
            )
        }

        val sections = output.split(SECTION_BOUNDARY)
        val candidates = sections.mapNotNull { section ->
            val layerLine = section.lineSequence()
                .firstOrNull { it.trimStart().startsWith("layerName") }
                ?: return@mapNotNull null
            val layerName = layerLine.substringAfter("=").trim()
            if (!layerName.contains(packageName, ignoreCase = false)) return@mapNotNull null

            val frames = framesPattern.find(section)?.groupValues?.getOrNull(1)?.toLongOrNull()
                ?: return@mapNotNull null
            val fps = fpsPattern.find(section)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
                ?: return@mapNotNull null
            if (frames <= 0L || !fps.isFinite() || fps !in MIN_VALID_FPS..MAX_VALID_FPS) {
                return@mapNotNull null
            }
            GameFpsSnapshot(packageName, layerName, frames, fps)
        }

        val selected = candidates.maxByOrNull(GameFpsSnapshot::totalFrames)
            ?: return Reading.TemporarilyUnavailable(
                "SurfaceFlinger --timestats:$packageName",
                sampledAtMillis,
                "尚未找到含有遊戲 package 的有效 SurfaceFlinger layer／frame 統計"
            )

        return Reading.Value(
            selected,
            "SurfaceFlinger --timestats:$packageName",
            "fps",
            sampledAtMillis
        )
    }

    private const val MAX_OUTPUT_CHARS = 262_144
    private const val MIN_VALID_FPS = 1.0
    private const val MAX_VALID_FPS = 240.0
    private val PACKAGE_PATTERN = Regex("^[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+$")
    private val SECTION_BOUNDARY = Regex("(?m)(?=^\\s*layerName\\s*=)")
}

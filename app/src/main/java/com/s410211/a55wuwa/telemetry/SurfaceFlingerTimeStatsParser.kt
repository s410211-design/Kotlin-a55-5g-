package com.s410211.a55wuwa.telemetry

data class GameFpsSnapshot(
    val packageName: String,
    val layerName: String,
    val totalFrames: Long,
    val cumulativeAverageFps: Double,
    val slow20FpsPercent: Double?,
    val slow30FpsPercent: Double?
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
            if (!layerNameMatchesPackage(layerName, packageName)) return@mapNotNull null

            val frames = framesPattern.find(section)?.groupValues?.getOrNull(1)?.toLongOrNull()
                ?: return@mapNotNull null
            val fps = fpsPattern.find(section)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
                ?: return@mapNotNull null
            if (frames <= 0L || !fps.isFinite() || fps !in MIN_VALID_FPS..MAX_VALID_FPS) {
                return@mapNotNull null
            }
            val histogram = parsePresentToPresentHistogram(section)
            val slow20 = histogram?.let { slowFramePercent(it, frames, 54) }
            val slow30 = histogram?.let { slowFramePercent(it, frames, 34) }
            GameFpsSnapshot(packageName, layerName, frames, fps, slow20, slow30)
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

    private fun parsePresentToPresentHistogram(section: String): Map<Int, Long>? {
        val marker = "presentToPresent histogram is as below:"
        val markerIndex = section.indexOf(marker)
        if (markerIndex < 0) return null
        val afterMarker = section.substring(markerIndex + marker.length)
        val nextHistogram = afterMarker.indexOf(" histogram is as below:")
        val block = if (nextHistogram >= 0) afterMarker.substring(0, nextHistogram) else afterMarker
    private val HISTOGRAM_BIN_PATTERN = Regex("""(\d+)ms=(\d+)""")
            val millis = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val count = match.groupValues[2].toLongOrNull() ?: return@mapNotNull null
            if (millis < 0 || count < 0L) null else millis to count
        }.toMap()
        return bins.takeIf { it.isNotEmpty() }
    }

    private fun slowFramePercent(
        histogram: Map<Int, Long>,
        totalFrames: Long,
        thresholdMillis: Int
    ): Double? {
        if (totalFrames <= 0L) return null
        val slowFrames = histogram.filterKeys { it >= thresholdMillis }.values.sum()
        if (slowFrames < 0L || slowFrames > totalFrames) return null
        return (slowFrames.toDouble() / totalFrames.toDouble()) * 100.0
    }

    private fun layerNameMatchesPackage(layerName: String, packageName: String): Boolean =
        layerName.contains("$packageName/") ||
            layerName.contains("$packageName]") ||
            layerName.endsWith(packageName)

    private val HISTOGRAM_BIN_PATTERN = Regex("""(\d+)ms=(\d+)""")
    private const val MAX_OUTPUT_CHARS = 262_144
    private const val MIN_VALID_FPS = 1.0
    private const val MAX_VALID_FPS = 240.0
    private val PACKAGE_PATTERN = Regex("^[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+$")
    private val SECTION_BOUNDARY = Regex("(?m)(?=^\\s*layerName\\s*=)")
}

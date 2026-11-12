package com.s410211.a55wuwa.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SurfaceFlingerTimeStatsParserTest {
    @Test
    fun selectsLayerForExactGamePackageAndParsesAverageFps() {
        val output = """
            layerName = SurfaceView[com.kurogame.wutheringwaves.global/MainActivity]@0(BLAST)#7
            totalFrames = 120
            averageFPS = 59.87
            presentToPresent histogram is as below:
            16ms=100 17ms=20
            layerName = StatusBar
            totalFrames = 500
            averageFPS = 60.0
        """.trimIndent()

        val result = SurfaceFlingerTimeStatsParser.parse(
            output,
            "com.kurogame.wutheringwaves.global",
            1234L
        )
        assertTrue(result is Reading.Value)
        val value = (result as Reading.Value<GameFpsSnapshot>).value
        assertEquals(120L, value.totalFrames)
        assertEquals(59.87, value.cumulativeAverageFps, 0.001)
        assertEquals(0.0, value.slow20FpsPercent!!, 0.001)
        assertEquals(0.0, value.slow30FpsPercent!!, 0.001)
        assertTrue(value.layerName.contains("com.kurogame.wutheringwaves.global"))
    }

    @Test
    fun calculatesSlowFramePercentagesFromPresentToPresentHistogram() {
        val output = """
            layerName = SurfaceView[com.kurogame.wutheringwaves.global/MainActivity]@0(BLAST)#7
            totalFrames = 100
            averageFPS = 45.0
            presentToPresent histogram is as below:
            16ms=70 34ms=10 54ms=20
        """.trimIndent()
        val result = SurfaceFlingerTimeStatsParser.parse(
            output,
            "com.kurogame.wutheringwaves.global",
            1L
        ) as Reading.Value<GameFpsSnapshot>
        assertEquals(20.0, result.value.slow20FpsPercent!!, 0.001)
        assertEquals(30.0, result.value.slow30FpsPercent!!, 0.001)
    }

    @Test
    fun doesNotMatchPackageNamePrefixes() {
        val output = """
            layerName = SurfaceView[com.kurogame.wutheringwaves.global2/MainActivity]@0(BLAST)#7
            totalFrames = 120
            averageFPS = 60.0
        """.trimIndent()
        assertTrue(
            SurfaceFlingerTimeStatsParser.parse(
                output,
                "com.kurogame.wutheringwaves.global",
                1L
            ) is Reading.TemporarilyUnavailable
        )
    }

    @Test
    fun refusesOtherPackagesAndInvalidFps() {
        val output = """
            layerName = SurfaceView[com.other.game/MainActivity]@0(BLAST)#7
            totalFrames = 100
            averageFPS = 60.0
        """.trimIndent()
        assertTrue(
            SurfaceFlingerTimeStatsParser.parse(output, "com.kurogame.wutheringwaves.global", 1L)
                is Reading.TemporarilyUnavailable
        )
        assertTrue(
            SurfaceFlingerTimeStatsParser.parse(output, "not a package", 1L)
                is Reading.InvalidData
        )
    }
}

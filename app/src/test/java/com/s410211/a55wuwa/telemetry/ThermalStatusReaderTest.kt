package com.s410211.a55wuwa.telemetry

import org.junit.Assert.assertEquals
import org.junit.Test

class ThermalStatusReaderTest {
    @Test
    fun mapsAndroidThermalStatusLevels() {
        assertEquals("NONE（正常）", ThermalStatusReader.statusLabel(0))
        assertEquals("LIGHT（輕度）", ThermalStatusReader.statusLabel(1))
        assertEquals("MODERATE（中度）", ThermalStatusReader.statusLabel(2))
        assertEquals("SEVERE（嚴重）", ThermalStatusReader.statusLabel(3))
        assertEquals("CRITICAL（危急）", ThermalStatusReader.statusLabel(4))
        assertEquals("EMERGENCY（緊急）", ThermalStatusReader.statusLabel(5))
        assertEquals("SHUTDOWN（系統關機等級）", ThermalStatusReader.statusLabel(6))
    }

    @Test
    fun retainsUnknownStatusInsteadOfGuessing() {
        assertEquals("UNKNOWN（99）", ThermalStatusReader.statusLabel(99))
    }
}

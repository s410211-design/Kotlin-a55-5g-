package com.s410211.a55wuwa

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceProfileFormatterTest {
    @Test
    fun formatsOneGiB() {
        assertEquals("1.0 GiB", DeviceProfileFormatter.formatGiB(1_073_741_824L))
    }

    @Test
    fun reportsNegativeValuesAsUnavailable() {
        assertEquals("無法取得", DeviceProfileFormatter.formatGiB(-1L))
    }
}

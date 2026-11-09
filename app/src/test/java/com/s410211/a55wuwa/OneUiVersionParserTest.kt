package com.s410211.a55wuwa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OneUiVersionParserTest {
    @Test
    fun extractsExplicitOneUiVersion() {
        assertEquals("9.0", OneUiVersionParser.parse("One UI 9.0 release"))
        assertEquals("9.1.1", OneUiVersionParser.parse("Samsung OneUI 9.1.1"))
    }

    @Test
    fun returnsNullWhenBuildStringsDoNotExposeVersion() {
        assertNull(OneUiVersionParser.parse("A5560ZCU1 build release-keys"))
        assertNull(OneUiVersionParser.parse(null, ""))
    }
}

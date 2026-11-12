package com.s410211.a55wuwa.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuNodeCatalogTest {
    @Test
    fun nodeIdsAndPathsAreUnique() {
        val nodes = ShizukuNode.values()
        assertEquals(nodes.size, nodes.map { it.id }.toSet().size)
        assertEquals(nodes.size, nodes.map { it.path }.toSet().size)
    }

    @Test
    fun everyNodeHasAnExactIdAndPathLookup() {
        ShizukuNode.values().forEach { node ->
            assertEquals(node, ShizukuNode.fromId(node.id))
            assertEquals(node, ShizukuNode.fromPath(node.path))
        }
    }

    @Test
    fun arbitraryPathsAndIdsAreNotAccepted() {
        assertNull(ShizukuNode.fromPath("/system/bin/sh"))
        assertNull(ShizukuNode.fromPath("/sys/kernel/gpu/gpu_governor"))
        assertNull(ShizukuNode.fromId(-1))
        assertNotNull(ShizukuNode.fromId(ShizukuNode.GPU_BUSY.id))
    }

    @Test
    fun catalogContainsOnlyObservedSysfsAndProcPaths() {
        ShizukuNode.values().forEach { node ->
            assertTrue(node.path.startsWith("/sys/") || node == ShizukuNode.PROC_STAT)
            assertTrue(node.path.none { it == ';' || it == '|' || it == '\n' || it == '\r' })
        }
        assertNull(ShizukuNode.fromPath("/sys/kernel/gpu/utilization_source"))
        assertNull(ShizukuNode.fromPath("/sys/kernel/gpu/available_utilization_sources"))
        assertEquals(ShizukuNode.PROC_STAT, ShizukuNode.fromPath("/proc/stat"))
    }
}

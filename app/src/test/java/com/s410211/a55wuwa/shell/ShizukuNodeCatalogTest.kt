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
            assertEquals(node, ShizukuNodeCatalog.fromId(node.id))
            assertEquals(node, ShizukuNodeCatalog.fromPath(node.path))
        }
    }

    @Test
    fun arbitraryPathsAndIdsAreNotAccepted() {
        assertNull(ShizukuNodeCatalog.fromPath("/system/bin/sh"))
        assertNull(ShizukuNodeCatalog.fromPath("/sys/kernel/gpu/gpu_governor"))
        assertNull(ShizukuNodeCatalog.fromId(-1))
        assertNotNull(ShizukuNodeCatalog.fromId(ShizukuNode.GPU_BUSY.id))
    }

    @Test
    fun catalogContainsObservedCpuAndGpuTelemetryOnly() {
        ShizukuNode.values().forEach { node ->
            assertTrue(node.path.startsWith("/sys/"))
            assertTrue(node.path.none { it == ';' || it == '|' || it == '\n' || it == '\r' })
        }
        assertNull(ShizukuNodeCatalog.fromPath("/sys/kernel/gpu/utilization_source"))
        assertNull(ShizukuNodeCatalog.fromPath("/sys/kernel/gpu/available_utilization_sources"))
    }
}

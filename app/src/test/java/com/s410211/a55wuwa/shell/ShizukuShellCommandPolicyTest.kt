package com.s410211.a55wuwa.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuShellCommandPolicyTest {
    @Test
    fun everyGpuNodeMapsToOneFixedCatArgument() {
        GpuShellNode.values().forEach { node ->
            val spec = ShizukuShellCommandPolicy.toSpec(
                ReadOnlyShellCommand.ReadGpuNode(node)
            )
            assertEquals(listOf("/system/bin/cat", node.path), spec.argv)
            assertEquals(ShizukuShellCommandPolicy.DEFAULT_TIMEOUT_MILLIS, spec.timeoutMillis)
            assertEquals(ShizukuShellCommandPolicy.MAX_GPU_NODE_STDOUT_BYTES, spec.stdoutLimitBytes)
            assertEquals(ShizukuShellCommandPolicy.MAX_STDERR_BYTES, spec.stderrLimitBytes)
        }
    }

    @Test
    fun everySystemPropertyMapsToFixedGetpropArgument() {
        AllowedSystemProperty.values().forEach { property ->
            val spec = ShizukuShellCommandPolicy.toSpec(
                ReadOnlyShellCommand.ReadSystemProperty(property)
            )
            assertEquals(listOf("/system/bin/getprop", property.key), spec.argv)
            assertEquals(ShizukuShellCommandPolicy.MAX_PROPERTY_STDOUT_BYTES, spec.stdoutLimitBytes)
        }
    }

    @Test
    fun argumentArraysNeverInvokeAShellOrContainUserSuppliedText() {
        val specs = GpuShellNode.values().map {
            ShizukuShellCommandPolicy.toSpec(ReadOnlyShellCommand.ReadGpuNode(it))
        } + AllowedSystemProperty.values().map {
            ShizukuShellCommandPolicy.toSpec(ReadOnlyShellCommand.ReadSystemProperty(it))
        }

        specs.forEach { spec ->
            assertFalse(spec.argv.contains("sh"))
            assertFalse(spec.argv.contains("-c"))
            assertTrue(spec.argv.size == 2)
            assertTrue(spec.argv.all { value ->
                value.none { it == ';' || it == '|' || it == '&' || it == '\n' || it == '\r' }
            })
        }
    }
}

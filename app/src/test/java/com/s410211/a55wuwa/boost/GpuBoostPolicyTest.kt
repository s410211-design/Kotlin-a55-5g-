package com.s410211.a55wuwa.boost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GpuBoostPolicyTest {
    @Test
    fun choosesSupportedGpuOppWithoutExceedingCurrentMaximum() {
        val result = GpuBoostPolicy.planAtPercent(
            GpuPolicySnapshot(
                currentMinRaw = 450_000L,
                currentMaxRaw = 1_152_000L,
                availableOperatingPointsRaw = setOf(
                    450_000L, 544_000L, 648_000L, 750_000L,
                    864_000L, 960_000L, 1_025_000L, 1_152_000L, 1_300_000L
                )
            ),
            80
        )
        assertTrue(result is GpuBoostPlanResult.Ready)
        assertEquals(960_000L, (result as GpuBoostPlanResult.Ready).plan.targetMinRaw)
    }

    @Test
    fun percentageCalculationDoesNotOverflowForLargePositiveLimits() {
        val result = GpuBoostPolicy.planAtPercent(
            GpuPolicySnapshot(
                currentMinRaw = 1L,
                currentMaxRaw = Long.MAX_VALUE,
                availableOperatingPointsRaw = setOf(1L, Long.MAX_VALUE)
            ),
            90
        )
        assertTrue(result is GpuBoostPlanResult.Ready)
        assertEquals(Long.MAX_VALUE, (result as GpuBoostPlanResult.Ready).plan.targetMinRaw)
    }

    @Test
    fun excludesOperatingPointsAboveOemMaximum() {
        val result = GpuBoostPolicy.planAtPercent(
            GpuPolicySnapshot(
                currentMinRaw = 450_000L,
                currentMaxRaw = 1_152_000L,
                availableOperatingPointsRaw = setOf(450_000L, 864_000L, 1_152_000L, 1_300_000L)
            ),
            90
        )
        assertTrue(result is GpuBoostPlanResult.Ready)
        assertEquals(1_152_000L, (result as GpuBoostPlanResult.Ready).plan.targetMinRaw)
    }
}

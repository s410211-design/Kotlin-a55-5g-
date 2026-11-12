package com.s410211.a55wuwa.boost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CpuBoostPolicyTest {
    private val policy = CpuPolicySnapshot(
        policyId = "policy4",
        currentMinKHz = 1_248_000L,
        currentMaxKHz = 2_750_000L,
        availableFrequenciesKHz = setOf(
            533_000L, 800_000L, 1_248_000L, 1_500_000L,
            1_800_000L, 2_000_000L, 2_200_000L, 2_500_000L, 2_750_000L
        )
    )

    @Test
    fun choosesSupportedOppWithoutRaisingOemMaximum() {
        val result = CpuBoostPolicy.planAtPercent(policy, 80)
        assertTrue(result is CpuBoostPlanResult.Ready)
        val plan = (result as CpuBoostPlanResult.Ready).plan
        assertEquals(2_200_000L, plan.targetMinKHz)
        assertEquals(policy.currentMaxKHz, plan.maxLimitKHz)
    }

    @Test
    fun rejectsMissingOppTableAndInvalidPercent() {
        assertTrue(
            CpuBoostPolicy.planAtPercent(policy.copy(availableFrequenciesKHz = emptySet()), 80)
                is CpuBoostPlanResult.Rejected
        )
        assertTrue(CpuBoostPolicy.planAtPercent(policy, 100) is CpuBoostPlanResult.Rejected)
    }

    @Test
    fun neverPlansOutsideCurrentPolicyLimits() {
        val result = CpuBoostPolicy.planAtPercent(
            policy.copy(currentMaxKHz = 1_800_000L),
            90
        )
        assertTrue(result is CpuBoostPlanResult.Ready)
        assertEquals(1_800_000L, (result as CpuBoostPlanResult.Ready).plan.targetMinKHz)
    }

    @Test
    fun restoreOnlyWhenCurrentValueStillMatchesOurLastWrite() {
        assertEquals(
            RestoreDecision.RESTORE_ORIGINAL,
            CpuBoostPolicy.decideRestore(1_000_000L, 2_000_000L, 2_000_000L)
        )
        assertEquals(
            RestoreDecision.ALREADY_RESTORED,
            CpuBoostPolicy.decideRestore(1_000_000L, 2_000_000L, 1_000_000L)
        )
        assertEquals(
            RestoreDecision.EXTERNAL_CHANGE,
            CpuBoostPolicy.decideRestore(1_000_000L, 2_000_000L, 1_500_000L)
        )
    }

    @Test
    fun thermalGateDeratesModerateAndFailsClosedAtSevereOrUnknown() {
        assertTrue(CpuBoostPolicy.thermalAllowsBoost(0))
        assertTrue(CpuBoostPolicy.thermalAllowsBoost(1))
        assertTrue(CpuBoostPolicy.thermalAllowsBoost(2))
        assertTrue(!CpuBoostPolicy.thermalAllowsBoost(3))
        assertTrue(!CpuBoostPolicy.thermalAllowsBoost(-1))
        assertEquals(70, CpuBoostPolicy.effectivePercent(80, 2))
        assertEquals(80, CpuBoostPolicy.effectivePercent(80, 1))
        assertEquals(null, CpuBoostPolicy.effectivePercent(80, 3))
    }
}

// Copyright (C) 2026 Toit contributors.
package org.toitlang.wcsrotate.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RotationPlannerTest {
    private val settings = RotationSettings()

    @Test
    fun `three minute five second song uses legal final block`() {
        val plan = RotationPlanner.create(0, 185_000, settings)

        assertEquals(4, plan.danceBlocksMs.size)
        assertTrue(plan.danceBlocksMs.dropLast(1).all { it in 45_000..65_000 })
        assertTrue(plan.danceBlocksMs.last() in 35_000..65_000)
        assertFalse(plan.usesLongBlockFallback)
        assertEquals(185_000L, plan.danceBlocksMs.sum() + plan.cuePositionsMs.size * 3_000L)
    }

    @Test
    fun `impossible seventy second song prefers one long block`() {
        val plan = RotationPlanner.create(0, 70_000, settings)

        assertEquals(listOf(70_000L), plan.danceBlocksMs)
        assertTrue(plan.cuePositionsMs.isEmpty())
        assertTrue(plan.usesLongBlockFallback)
    }

    @Test
    fun `three minute twenty three second song is evenly divided`() {
        val plan = RotationPlanner.create(0, 203_000, settings)

        assertEquals(listOf(48_500L, 48_500L, 48_500L, 48_500L), plan.danceBlocksMs)
        assertEquals(listOf(48_500L, 100_000L, 151_500L), plan.cuePositionsMs)
    }

    @Test
    fun `planning from current position uses only remaining song`() {
        val plan = RotationPlanner.create(60_000, 203_000, settings)

        assertEquals(143_000L, plan.danceBlocksMs.sum() + plan.cuePositionsMs.size * 3_000L)
        assertTrue(plan.cuePositionsMs.all { it >= 60_000 && it < 203_000 })
    }

    @Test
    fun `custom constraints are honored`() {
        val custom = RotationSettings(
            targetSeconds = 60,
            minimumSeconds = 55,
            maximumSeconds = 75,
            finalMinimumSeconds = 40,
            cueSeconds = 4,
        )
        val plan = RotationPlanner.create(0, 240_000, custom)

        assertTrue(plan.danceBlocksMs.dropLast(1).all { it in 55_000..75_000 })
        assertTrue(plan.danceBlocksMs.last() in 40_000..75_000)
        assertEquals(240_000L, plan.danceBlocksMs.sum() + plan.cuePositionsMs.size * 4_000L)
    }

    @Test
    fun `all ordinary song lengths obey constraints or use long fallback`() {
        for (durationSeconds in 35..600) {
            val plan = RotationPlanner.create(0, durationSeconds * 1_000L, settings)
            assertEquals(
                "duration=$durationSeconds",
                durationSeconds * 1_000L,
                plan.danceBlocksMs.sum() + plan.cuePositionsMs.size * 3_000L,
            )
            if (plan.isAdaptive) {
                assertTrue(
                    "normal blocks at duration=$durationSeconds: ${plan.danceBlocksMs}",
                    plan.danceBlocksMs.dropLast(1).all { it in 45_000..65_000 },
                )
                assertTrue(
                    "final block at duration=$durationSeconds: ${plan.danceBlocksMs}",
                    plan.danceBlocksMs.last() in 35_000..65_000,
                )
            } else {
                assertTrue(
                    "fallback should prefer long blocks at duration=$durationSeconds",
                    plan.danceBlocksMs.all { it > 65_000 },
                )
            }
        }
    }
}

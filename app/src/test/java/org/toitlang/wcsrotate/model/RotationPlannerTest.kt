// Copyright (C) 2026 Toit contributors.
package org.toitlang.wcsrotate.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RotationPlannerTest {
    private val settings = RotationSettings()

    @Test
    fun `three minute five second song uses three long dances`() {
        val plan = RotationPlanner.create(0, 185_000, settings)
        assertEquals(listOf(59_667L, 59_667L, 59_666L), plan.danceBlocksMs)
        assertEquals(185_000L, plan.stopPositionMs)
        assertEquals(0L, plan.trimmedEndMs)
    }

    @Test
    fun `three minute twenty three second song needs four dances`() {
        val plan = RotationPlanner.create(0, 203_000, settings)
        assertEquals(List(4) { 48_500L }, plan.danceBlocksMs)
        assertEquals(listOf(48_500L, 100_000L, 151_500L), plan.cuePositionsMs)
        assertEquals(0L, plan.trimmedEndMs)
    }

    @Test
    fun `ambiguous song prefers the fewest longest blocks`() {
        val plan = RotationPlanner.create(0, 600_000, settings)
        // Nine, ten, eleven, and twelve blocks all fit. Nine gives the longest dances.
        assertEquals(List(9) { 64_000L }, plan.danceBlocksMs)
        assertEquals(600_000L, plan.stopPositionMs)
        assertEquals(0L, plan.trimmedEndMs)
    }

    @Test
    fun `seventy second song stops at the maximum block length`() {
        val plan = RotationPlanner.create(0, 70_000, settings)
        assertEquals(listOf(65_000L), plan.danceBlocksMs)
        assertTrue(plan.cuePositionsMs.isEmpty())
        assertEquals(65_000L, plan.stopPositionMs)
        assertEquals(5_000L, plan.trimmedEndMs)
    }

    @Test
    fun `final dance does not get a shorter minimum`() {
        val plan = RotationPlanner.create(0, 83_000, settings)
        assertEquals(listOf(65_000L), plan.danceBlocksMs)
        assertEquals(65_000L, plan.stopPositionMs)
        assertEquals(18_000L, plan.trimmedEndMs)
    }

    @Test
    fun `exactly two minimum blocks include the switch time`() {
        val plan = RotationPlanner.create(0, 93_000, settings)
        assertEquals(listOf(45_000L, 45_000L), plan.danceBlocksMs)
        assertEquals(listOf(45_000L), plan.cuePositionsMs)
        assertEquals(93_000L, plan.stopPositionMs)
        assertEquals(0L, plan.trimmedEndMs)
    }

    @Test
    fun `fixed length blocks trim the smallest possible tail`() {
        val fixed = RotationSettings(minimumSeconds = 60, maximumSeconds = 60, cueSeconds = 4)
        val plan = RotationPlanner.create(0, 135_000, fixed)
        assertEquals(listOf(60_000L, 60_000L), plan.danceBlocksMs)
        assertEquals(124_000L, plan.stopPositionMs)
        assertEquals(11_000L, plan.trimmedEndMs)
    }

    @Test
    fun `replanning uses the remaining music and preserves absolute positions`() {
        val plan = RotationPlanner.create(60_000, 130_000, settings)
        assertEquals(60_000L, plan.startPositionMs)
        assertEquals(listOf(65_000L), plan.danceBlocksMs)
        assertEquals(125_000L, plan.stopPositionMs)
        assertEquals(5_000L, plan.trimmedEndMs)
    }

    @Test
    fun `too little remaining music produces an immediate stop with no cue`() {
        val plan = RotationPlanner.create(60_000, 80_000, settings)
        assertEquals(60_000L, plan.stopPositionMs)
        assertEquals(20_000L, plan.trimmedEndMs)
        assertTrue(plan.danceBlocksMs.isEmpty())
        assertTrue(plan.cuePositionsMs.isEmpty())
    }

    @Test
    fun `empty or reversed range does not produce negative time`() {
        val plan = RotationPlanner.create(60_000, 30_000, settings)
        assertEquals(60_000L, plan.startPositionMs)
        assertEquals(60_000L, plan.stopPositionMs)
        assertEquals(0L, plan.trimmedEndMs)
        assertTrue(plan.danceBlocksMs.isEmpty())
    }

    @Test
    fun `long recordings are not limited to two hundred blocks`() {
        val plan = RotationPlanner.create(0, 6 * 60 * 60 * 1_000L, settings)
        assertEquals(318, plan.danceBlocksMs.size)
        assertEquals(0L, plan.trimmedEndMs)
        assertTrue(plan.danceBlocksMs.all { it in 45_000L..65_000L })
    }

    @Test
    fun `starter time extends only the first dance and keeps switch intervals`() {
        val starter = RotationSettings(
            minimumSeconds = 60, maximumSeconds = 60, cueSeconds = 6,
            addSwitchTimeToFirstBlock = true,
        )
        val plan = RotationPlanner.create(0, 198_000, starter)
        assertEquals(listOf(66_000L, 60_000L, 60_000L), plan.danceBlocksMs)
        assertEquals(listOf(66_000L, 132_000L), plan.cuePositionsMs)
        assertEquals(198_000L, plan.stopPositionMs)
    }

    @Test
    fun `starter time is accounted for when trimming the end`() {
        val plan = RotationPlanner.create(0, 70_000, settings.copy(addSwitchTimeToFirstBlock = true))
        assertEquals(listOf(68_000L), plan.danceBlocksMs)
        assertEquals(68_000L, plan.stopPositionMs)
        assertEquals(2_000L, plan.trimmedEndMs)
    }

    @Test
    fun `starter time requires room for both the minimum dance and intro`() {
        val starter = settings.copy(addSwitchTimeToFirstBlock = true)
        assertTrue(RotationPlanner.create(0, 47_999, starter).danceBlocksMs.isEmpty())
        assertEquals(listOf(48_000L), RotationPlanner.create(0, 48_000, starter).danceBlocksMs)
    }

    @Test
    fun `replanning after a switch does not add starter time again`() {
        val plan = RotationPlanner.create(
            72_000, 198_000,
            RotationSettings(
                minimumSeconds = 60, maximumSeconds = 60, cueSeconds = 6,
                addSwitchTimeToFirstBlock = true,
            ),
            isFirstBlock = false,
        )
        assertEquals(listOf(60_000L, 60_000L), plan.danceBlocksMs)
        assertEquals(listOf(132_000L), plan.cuePositionsMs)
        assertEquals(198_000L, plan.stopPositionMs)
    }

    @Test
    fun `plans obey both limits retain the most music and prefer fewer blocks`() {
        val configurations = listOf(
            settings,
            RotationSettings(minimumSeconds = 60, maximumSeconds = 60),
            RotationSettings(minimumSeconds = 40, maximumSeconds = 50, cueSeconds = 15),
            RotationSettings(minimumSeconds = 10, maximumSeconds = 180, cueSeconds = 1),
            RotationSettings(minimumSeconds = 179, maximumSeconds = 180, cueSeconds = 15),
        )
        for (configuration in configurations + configurations.map { it.copy(addSwitchTimeToFirstBlock = true) }) {
            val minimum = configuration.minimumSeconds * 1_000L
            val maximum = configuration.maximumSeconds * 1_000L
            val cue = configuration.cueSeconds * 1_000L
            val extra = if (configuration.addSwitchTimeToFirstBlock) cue else 0L
            for (seconds in 1..600) {
                // Odd milliseconds exercise rounding close to block boundaries.
                val duration = seconds * 1_000L + 37
                val plan = RotationPlanner.create(0, duration, configuration)
                val counts = 1..(duration / minimum).toInt()
                val fullSongCount = counts.firstOrNull { count ->
                    duration in (extra + count * minimum + (count - 1) * cue)..
                        (extra + count * maximum + (count - 1) * cue)
                }
                val latestLegalStop = counts.mapNotNull { count ->
                    if (extra + count * minimum + (count - 1) * cue <= duration) {
                        minOf(duration, extra + count * maximum + (count - 1) * cue)
                    } else null
                }.maxOrNull() ?: 0L

                assertEquals("duration=$duration, $configuration", latestLegalStop, plan.stopPositionMs)
                if (fullSongCount != null) assertEquals(fullSongCount, plan.danceBlocksMs.size)
                plan.danceBlocksMs.forEachIndexed { index, block ->
                    val dance = block - if (index == 0) extra else 0L
                    assertTrue(dance in minimum..maximum)
                }
                assertEquals(duration - plan.stopPositionMs, plan.trimmedEndMs)
                assertEquals(plan.stopPositionMs, plan.danceBlocksMs.sum() + plan.cuePositionsMs.size * cue)
                var cursor = 0L
                plan.danceBlocksMs.dropLast(1).forEachIndexed { index, block ->
                    cursor += block
                    assertEquals(cursor, plan.cuePositionsMs[index])
                    cursor += cue
                }
            }
        }
    }
}

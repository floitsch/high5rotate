// Copyright (C) 2026 Toit contributors.
package org.toitlang.wcsrotate.model

data class RotationPlan(
    val startPositionMs: Long,
    val stopPositionMs: Long,
    val danceBlocksMs: List<Long>,
    val cuePositionsMs: List<Long>,
    val trimmedEndMs: Long,
)

object RotationPlanner {
    fun create(
        startPositionMs: Long,
        stopPositionMs: Long,
        settings: RotationSettings,
        isFirstBlock: Boolean = true,
    ): RotationPlan {
        val value = settings.sanitized()
        val start = startPositionMs.coerceAtLeast(0)
        val stop = stopPositionMs.coerceAtLeast(start)
        val totalAvailable = stop - start
        val cue = value.cueSeconds * 1_000L
        val firstBlockExtra = if (isFirstBlock && value.addSwitchTimeToFirstBlock) cue else 0L
        val available = totalAvailable - firstBlockExtra
        val minimum = value.minimumSeconds * 1_000L
        val maximum = value.maximumSeconds * 1_000L

        if (available < minimum) {
            return RotationPlan(start, start, emptyList(), emptyList(), totalAvailable)
        }

        // The fewest blocks that could cover the song without exceeding the maximum
        // also give the longest dances when several schedules fit.
        var blockCount = ((available + cue - 1) / (maximum + cue) + 1).toInt()
        var danceTime = available - (blockCount - 1) * cue
        var effectiveStop = stop
        if (danceTime < blockCount * minimum) {
            // No full-song partition fits. Keep as much music as possible by using
            // one fewer block at the maximum length and trimming the leftover tail.
            blockCount--
            danceTime = blockCount * maximum
            effectiveStop = start + firstBlockExtra + danceTime + (blockCount - 1) * cue
        }

        // Distribute rounding milliseconds so every block stays inside the limits.
        val base = danceTime / blockCount
        val remainder = danceTime % blockCount
        val blocks = List(blockCount) { index ->
            base + (if (index < remainder) 1L else 0L) +
                (if (index == 0) firstBlockExtra else 0L)
        }
        val cuePositions = mutableListOf<Long>()
        var cursor = start
        blocks.dropLast(1).forEach { block ->
            cursor += block
            cuePositions += cursor
            cursor += cue
        }

        return RotationPlan(
            startPositionMs = start,
            stopPositionMs = effectiveStop,
            danceBlocksMs = blocks,
            cuePositionsMs = cuePositions,
            trimmedEndMs = stop - effectiveStop,
        )
    }
}

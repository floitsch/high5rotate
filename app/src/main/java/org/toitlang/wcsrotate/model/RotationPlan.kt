// Copyright (C) 2026 Toit contributors.
package org.toitlang.wcsrotate.model

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToLong

data class RotationPlan(
    val startPositionMs: Long,
    val stopPositionMs: Long,
    val danceBlocksMs: List<Long>,
    val cuePositionsMs: List<Long>,
    val usesLongBlockFallback: Boolean,
) {
    val isAdaptive: Boolean get() = !usesLongBlockFallback
}

object RotationPlanner {
    fun create(
        startPositionMs: Long,
        stopPositionMs: Long,
        settings: RotationSettings,
    ): RotationPlan {
        val value = settings.sanitized()
        val start = startPositionMs.coerceAtLeast(0)
        val stop = stopPositionMs.coerceAtLeast(start)
        val available = (stop - start).toDouble()
        val cue = value.cueSeconds * 1_000.0
        val target = value.targetSeconds * 1_000.0
        val minimum = value.minimumSeconds * 1_000.0
        val maximum = value.maximumSeconds * 1_000.0
        val finalMinimum = value.finalMinimumSeconds * 1_000.0

        if (available <= 0) {
            return RotationPlan(start, stop, emptyList(), emptyList(), false)
        }

        val maximumCandidates = ceil((available + cue) / (finalMinimum + cue))
            .toInt()
            .coerceIn(1, 200)

        val candidates = (1..maximumCandidates).mapNotNull { blockCount ->
            val danceTime = available - (blockCount - 1) * cue
            val minimumDanceTime = (blockCount - 1) * minimum + finalMinimum
            val maximumDanceTime = blockCount * maximum
            if (danceTime < minimumDanceTime || danceTime > maximumDanceTime) return@mapNotNull null

            val average = danceTime / blockCount
            val blocks = if (average >= minimum) {
                List(blockCount) { average }
            } else {
                List(blockCount - 1) { minimum } +
                    listOf(danceTime - (blockCount - 1) * minimum)
            }
            val score = blocks.sumOf { (it - target).pow(2) } / blockCount
            Candidate(blocks, score)
        }

        val selected = candidates.minWithOrNull(
            compareBy<Candidate> { it.score }
                .thenBy { it.blocks.size },
        )

        if (selected != null) {
            return buildPlan(start, stop, selected.blocks, cue, false)
        }

        // No legal partition exists. Prefer fewer, longer blocks rather than short-changing
        // students with a block below the configured final minimum.
        val fallbackCount = floor((available + cue) / (maximum + cue))
            .toInt()
            .coerceAtLeast(1)
        val danceTime = available - (fallbackCount - 1) * cue
        val blocks = List(fallbackCount) { danceTime / fallbackCount }
        return buildPlan(start, stop, blocks, cue, true)
    }

    private fun buildPlan(
        start: Long,
        stop: Long,
        rawBlocks: List<Double>,
        cueMs: Double,
        fallback: Boolean,
    ): RotationPlan {
        val cuePositions = mutableListOf<Long>()
        var cursor = start.toDouble()
        rawBlocks.dropLast(1).forEach { block ->
            cursor += block
            cuePositions += cursor.roundToLong()
            cursor += cueMs
        }

        val roundedBlocks = rawBlocks.map { it.roundToLong() }.toMutableList()
        if (roundedBlocks.isNotEmpty()) {
            val roundedTotal = roundedBlocks.sum() +
                (roundedBlocks.size - 1) * cueMs.roundToLong()
            roundedBlocks[roundedBlocks.lastIndex] += (stop - start) - roundedTotal
        }

        return RotationPlan(
            startPositionMs = start,
            stopPositionMs = stop,
            danceBlocksMs = roundedBlocks,
            cuePositionsMs = cuePositions,
            usesLongBlockFallback = fallback,
        )
    }

    private data class Candidate(
        val blocks: List<Double>,
        val score: Double,
    )
}

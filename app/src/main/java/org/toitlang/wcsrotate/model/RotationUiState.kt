// Copyright (C) 2026 Toit contributors.
package org.toitlang.wcsrotate.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class RotationPhase {
    DISARMED,
    WAITING_FOR_MEDIA_ACCESS,
    WAITING_FOR_MUSIC,
    DANCING,
    SWITCHING,
    MUSIC_PAUSED,
}

data class RotationUiState(
    val armed: Boolean = false,
    val phase: RotationPhase = RotationPhase.DISARMED,
    val trackTitle: String? = null,
    val trackArtist: String? = null,
    val nextCueInMs: Long? = null,
    val trackRemainingMs: Long? = null,
    val blockDurationsMs: List<Long> = emptyList(),
    val usesLongBlockFallback: Boolean = false,
    val message: String? = null,
)

object RotationStateStore {
    private val mutableState = MutableStateFlow(RotationUiState())
    val state = mutableState.asStateFlow()

    fun update(value: RotationUiState) {
        mutableState.value = value
    }

    fun update(transform: (RotationUiState) -> RotationUiState) {
        mutableState.value = transform(mutableState.value)
    }
}

// Copyright (C) 2026 Toit contributors.
package org.toitlang.wcsrotate.model

import android.content.Context

enum class SwitchTone(val label: String) {
    CLASSIC("Double beep"),
    BEEP("Short beep"),
    CHIME("Chime"),
    AUDIO_FILE("Audio file"),
}

data class RotationSettings(
    val targetSeconds: Int = 50,
    val minimumSeconds: Int = 45,
    val maximumSeconds: Int = 65,
    val finalMinimumSeconds: Int = 35,
    val cueSeconds: Int = 3,
    val playSwitchSound: Boolean = true,
    val playSoundAtSongEnd: Boolean = false,
    val endGuardMillis: Int = 500,
    val switchTone: SwitchTone = SwitchTone.CLASSIC,
    val soundUri: String? = null,
    val soundName: String? = null,
) {
    fun hasSameTimingAs(other: RotationSettings): Boolean =
        targetSeconds == other.targetSeconds &&
            minimumSeconds == other.minimumSeconds &&
            maximumSeconds == other.maximumSeconds &&
            finalMinimumSeconds == other.finalMinimumSeconds &&
            cueSeconds == other.cueSeconds &&
            endGuardMillis == other.endGuardMillis

    fun sanitized(): RotationSettings {
        val maximum = maximumSeconds.coerceIn(10, 180)
        val minimum = minimumSeconds.coerceIn(10, maximum)
        val target = targetSeconds.coerceIn(minimum, maximum)
        return copy(
            targetSeconds = target,
            minimumSeconds = minimum,
            maximumSeconds = maximum,
            finalMinimumSeconds = finalMinimumSeconds.coerceIn(5, minimum),
            cueSeconds = cueSeconds.coerceIn(1, 15),
            endGuardMillis = endGuardMillis.coerceIn(100, 3_000),
        )
    }
}

class RotationSettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun load(): RotationSettings = RotationSettings(
        targetSeconds = preferences.getInt(TARGET, 50),
        minimumSeconds = preferences.getInt(MINIMUM, 45),
        maximumSeconds = preferences.getInt(MAXIMUM, 65),
        finalMinimumSeconds = preferences.getInt(FINAL_MINIMUM, 35),
        cueSeconds = preferences.getInt(CUE, 3),
        playSwitchSound = preferences.getBoolean(PLAY_SWITCH_SOUND, true),
        playSoundAtSongEnd = preferences.getBoolean(PLAY_SOUND_AT_SONG_END, false),
        endGuardMillis = preferences.getInt(END_GUARD, 500),
        switchTone = SwitchTone.entries.firstOrNull {
            it.name == preferences.getString(SWITCH_TONE, null)
        } ?: SwitchTone.CLASSIC,
        soundUri = preferences.getString(SOUND_URI, null),
        soundName = preferences.getString(SOUND_NAME, null),
    ).sanitized()

    fun save(settings: RotationSettings) {
        val value = settings.sanitized()
        preferences.edit()
            .putInt(TARGET, value.targetSeconds)
            .putInt(MINIMUM, value.minimumSeconds)
            .putInt(MAXIMUM, value.maximumSeconds)
            .putInt(FINAL_MINIMUM, value.finalMinimumSeconds)
            .putInt(CUE, value.cueSeconds)
            .putBoolean(PLAY_SWITCH_SOUND, value.playSwitchSound)
            .putBoolean(PLAY_SOUND_AT_SONG_END, value.playSoundAtSongEnd)
            .putInt(END_GUARD, value.endGuardMillis)
            .putString(SWITCH_TONE, value.switchTone.name)
            .putString(SOUND_URI, value.soundUri)
            .putString(SOUND_NAME, value.soundName)
            .apply()
    }

    fun isArmed(): Boolean = preferences.getBoolean(ARMED, false)

    fun setArmed(armed: Boolean) {
        preferences.edit().putBoolean(ARMED, armed).apply()
    }

    private companion object {
        const val PREFERENCES = "rotation"
        const val TARGET = "target_seconds"
        const val MINIMUM = "minimum_seconds"
        const val MAXIMUM = "maximum_seconds"
        const val FINAL_MINIMUM = "final_minimum_seconds"
        const val CUE = "cue_seconds"
        const val PLAY_SWITCH_SOUND = "play_switch_sound"
        const val PLAY_SOUND_AT_SONG_END = "play_sound_at_song_end"
        const val END_GUARD = "end_guard_millis"
        const val SWITCH_TONE = "switch_tone"
        const val SOUND_URI = "sound_uri"
        const val SOUND_NAME = "sound_name"
        const val ARMED = "armed"
    }
}

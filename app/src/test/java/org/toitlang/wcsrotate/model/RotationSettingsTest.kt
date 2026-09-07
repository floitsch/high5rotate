// Copyright (C) 2026 Toit contributors.
package org.toitlang.wcsrotate.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RotationSettingsTest {
    @Test
    fun `sound changes preserve timing during a song`() {
        val original = RotationSettings()
        val changed = original.copy(
            switchTone = SwitchTone.AUDIO_FILE,
            soundUri = "content://documents/switch.wav",
            soundName = "switch.wav",
            playSwitchSound = false,
            playSoundAtSongEnd = true,
        ).sanitized()

        assertTrue(changed.hasSameTimingAs(original))
        assertEquals(SwitchTone.AUDIO_FILE, changed.switchTone)
        assertEquals("content://documents/switch.wav", changed.soundUri)
        assertEquals("switch.wav", changed.soundName)
    }

    @Test
    fun `each timing control requires replanning`() {
        val original = RotationSettings()
        val changes = listOf(
            original.copy(minimumSeconds = 40),
            original.copy(maximumSeconds = 70),
            original.copy(cueSeconds = 4),
            original.copy(addSwitchTimeToFirstBlock = true),
            original.copy(endGuardMillis = 600),
        )

        changes.forEach { assertFalse(it.sanitized().hasSameTimingAs(original)) }
    }

    @Test
    fun `default sound retains existing cue behavior`() {
        val settings = RotationSettings()
        assertEquals(SwitchTone.CLASSIC, settings.switchTone)
        assertTrue(settings.playSwitchSound)
        assertFalse(settings.playSoundAtSongEnd)
    }
}

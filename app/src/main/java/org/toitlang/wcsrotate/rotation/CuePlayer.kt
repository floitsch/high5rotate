// Copyright (C) 2026 Toit contributors.
package org.toitlang.wcsrotate.rotation

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.net.Uri
import android.os.Handler
import org.toitlang.wcsrotate.model.SwitchTone

class CuePlayer(
    context: Context,
    private val handler: Handler,
) {
    private val appContext = context.applicationContext
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .setOnAudioFocusChangeListener { }
        .build()

    private var toneGenerator: ToneGenerator? = null
    private var mediaPlayer: MediaPlayer? = null
    private var finishRunnable: Runnable? = null
    private var secondToneRunnable: Runnable? = null

    var isPlaying: Boolean = false
        private set

    fun play(
        durationMs: Long,
        playSound: Boolean,
        tone: SwitchTone = SwitchTone.CLASSIC,
        soundUri: String? = null,
        onFinished: () -> Unit,
    ) {
        stop()
        isPlaying = true
        audioManager.requestAudioFocus(focusRequest)

        if (playSound) {
            if (tone == SwitchTone.AUDIO_FILE && soundUri != null) {
                playAudioFile(soundUri)
            } else {
                playBuiltIn(tone)
            }
        }

        if (playSound && tone != SwitchTone.AUDIO_FILE && durationMs >= 1_200) {
            secondToneRunnable = Runnable {
                toneGenerator?.startTone(ToneGenerator.TONE_PROP_ACK, 450)
            }.also { handler.postDelayed(it, durationMs - 600) }
        }

        finishRunnable = Runnable {
            finishRunnable = null
            secondToneRunnable = null
            finish()
            onFinished()
        }.also { handler.postDelayed(it, durationMs.coerceAtLeast(250)) }
    }

    private fun playBuiltIn(tone: SwitchTone) {
        val toneType = when (tone) {
            SwitchTone.CLASSIC, SwitchTone.AUDIO_FILE -> ToneGenerator.TONE_PROP_BEEP2
            SwitchTone.BEEP -> ToneGenerator.TONE_PROP_BEEP
            SwitchTone.CHIME -> ToneGenerator.TONE_PROP_ACK
        }
        toneGenerator = runCatching {
            ToneGenerator(AudioManager.STREAM_MUSIC, 100).also {
                it.startTone(toneType, 450)
            }
        }.getOrNull()
    }

    private fun playAudioFile(soundUri: String) {
        runCatching {
            val player = MediaPlayer()
            mediaPlayer = player
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            player.setOnPreparedListener { preparedPlayer ->
                if (isPlaying && mediaPlayer === preparedPlayer) {
                    runCatching { preparedPlayer.start() }
                        .onFailure { fallbackToBuiltIn(preparedPlayer) }
                }
            }
            player.setOnErrorListener { failedPlayer, _, _ ->
                fallbackToBuiltIn(failedPlayer)
                true
            }
            player.setDataSource(appContext, Uri.parse(soundUri))
            player.prepareAsync()
        }.onFailure {
            mediaPlayer?.let(::fallbackToBuiltIn) ?: playBuiltIn(SwitchTone.CLASSIC)
        }
    }

    private fun fallbackToBuiltIn(player: MediaPlayer) {
        if (mediaPlayer !== player) return
        mediaPlayer = null
        player.release()
        if (isPlaying) playBuiltIn(SwitchTone.CLASSIC)
    }

    fun stop() {
        finishRunnable?.let(handler::removeCallbacks)
        secondToneRunnable?.let(handler::removeCallbacks)
        finishRunnable = null
        secondToneRunnable = null
        finish()
    }

    private fun finish() {
        val player = mediaPlayer
        mediaPlayer = null
        player?.release()
        toneGenerator?.stopTone()
        toneGenerator?.release()
        toneGenerator = null
        if (isPlaying) {
            audioManager.abandonAudioFocusRequest(focusRequest)
        }
        isPlaying = false
    }
}

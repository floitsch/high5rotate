// Copyright (C) 2026 Toit contributors.
package org.toitlang.wcsrotate.rotation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.toitlang.wcsrotate.MainActivity
import org.toitlang.wcsrotate.R
import org.toitlang.wcsrotate.media.MediaAccess
import org.toitlang.wcsrotate.model.RotationPhase
import org.toitlang.wcsrotate.model.RotationPlan
import org.toitlang.wcsrotate.model.RotationPlanner
import org.toitlang.wcsrotate.model.RotationSettings
import org.toitlang.wcsrotate.model.RotationSettingsStore
import org.toitlang.wcsrotate.model.RotationStateStore
import org.toitlang.wcsrotate.model.RotationUiState
import java.util.Locale
import kotlin.math.abs

class RotationService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var settingsStore: RotationSettingsStore
    private lateinit var mediaSessionManager: MediaSessionManager
    private lateinit var cuePlayer: CuePlayer
    private lateinit var wakeLock: PowerManager.WakeLock
    private var lastWakeLockRenewalMs = 0L

    private var settings = RotationSettings()
    private var armed = false
    private var explicitDisarm = false
    private val observedControllers = mutableListOf<ObservedController>()
    private var activeController: MediaController? = null
    private var activeTrackKey: String? = null
    private var trackTitle: String? = null
    private var trackArtist: String? = null
    private var durationMs: Long = 0
    private var plan: RotationPlan? = null
    private var nextCueIndex = 0
    private var blindNextCuePositionMs: Long? = null
    private var lastEstimatedPositionMs = 0L
    private var pauseRequestedAtEnd = false
    private var pauseObservedAtEnd = false
    private var finalCuePlaying = false
    private var lastNotificationSecond = Long.MIN_VALUE
    private var sessionsListenerRegistered = false

    private val tickRunnable = object : Runnable {
        override fun run() {
            tick()
            if (armed) handler.postDelayed(this, TICK_MS)
        }
    }

    private val sessionsChangedListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        replaceControllers(controllers.orEmpty())
        evaluateSessions()
    }

    override fun onCreate() {
        super.onCreate()
        settingsStore = RotationSettingsStore(this)
        mediaSessionManager = getSystemService(MediaSessionManager::class.java)
        cuePlayer = CuePlayer(this, handler)
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "wcsrotate:rotation",
        ).apply { setReferenceCounted(false) }
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISARM -> disarm()
            ACTION_SWITCH_NOW -> switchNow()
            ACTION_SETTINGS_CHANGED -> {
                val previousSettings = settings
                settings = settingsStore.load()
                if (!settings.hasSameTimingAs(previousSettings)) {
                    replanFromCurrentPosition("Timing updated")
                }
            }
            ACTION_REFRESH -> if (armed || settingsStore.isArmed()) arm()
            ACTION_ARM, null -> {
                if (intent?.action == ACTION_ARM || settingsStore.isArmed()) arm()
                else stopSelf()
            }
        }
        return if (armed) START_STICKY else START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        cuePlayer.stop()
        updateWakeLock(false)
        unregisterMediaSessions()
        if (explicitDisarm) {
            RotationStateStore.update(RotationUiState())
        }
        super.onDestroy()
    }

    private fun arm() {
        if (armed) {
            registerMediaSessions()
            return
        }
        armed = true
        explicitDisarm = false
        settings = settingsStore.load()
        settingsStore.setArmed(true)
        startForegroundServiceNotification()
        publishState(
            phase = if (MediaAccess.isGranted(this)) {
                RotationPhase.WAITING_FOR_MUSIC
            } else {
                RotationPhase.WAITING_FOR_MEDIA_ACCESS
            },
        )
        registerMediaSessions()
        handler.removeCallbacks(tickRunnable)
        handler.post(tickRunnable)
    }

    private fun disarm() {
        explicitDisarm = true
        armed = false
        settingsStore.setArmed(false)
        cuePlayer.stop()
        updateWakeLock(false)
        handler.removeCallbacksAndMessages(null)
        unregisterMediaSessions()
        RotationStateStore.update(RotationUiState())
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun registerMediaSessions() {
        if (!MediaAccess.isGranted(this)) {
            cuePlayer.stop()
            unregisterMediaSessions()
            publishState(RotationPhase.WAITING_FOR_MEDIA_ACCESS, "Grant media access to monitor music")
            return
        }
        val component = MediaAccess.component(this)
        if (!sessionsListenerRegistered) {
            runCatching {
                mediaSessionManager.addOnActiveSessionsChangedListener(
                    sessionsChangedListener,
                    component,
                    handler,
                )
            }.onSuccess {
                sessionsListenerRegistered = true
            }.onFailure {
                publishState(RotationPhase.WAITING_FOR_MEDIA_ACCESS, "Media access is not connected yet")
            }
        }
        refreshMediaSessions()
    }

    private fun refreshMediaSessions() {
        if (!MediaAccess.isGranted(this)) return
        runCatching {
            mediaSessionManager.getActiveSessions(MediaAccess.component(this))
        }.onSuccess {
            replaceControllers(it)
            evaluateSessions()
        }.onFailure {
            publishState(RotationPhase.WAITING_FOR_MEDIA_ACCESS, "Unable to read active media sessions")
        }
    }

    private fun unregisterMediaSessions() {
        if (sessionsListenerRegistered) {
            runCatching { mediaSessionManager.removeOnActiveSessionsChangedListener(sessionsChangedListener) }
            sessionsListenerRegistered = false
        }
        observedControllers.forEach { it.controller.unregisterCallback(it.callback) }
        observedControllers.clear()
        activeController = null
    }

    private fun replaceControllers(controllers: List<MediaController>) {
        observedControllers.forEach { it.controller.unregisterCallback(it.callback) }
        observedControllers.clear()
        controllers
            .filterNot { it.packageName == packageName }
            .forEach { controller ->
                val callback = object : MediaController.Callback() {
                    override fun onPlaybackStateChanged(state: PlaybackState?) {
                        if (controller.sessionToken == activeController?.sessionToken && state != null) {
                            detectSeekAndMaybeReplan(state)
                        }
                        evaluateSessions()
                    }

                    override fun onMetadataChanged(metadata: MediaMetadata?) {
                        evaluateSessions(metadataChangedController = controller)
                    }

                    override fun onSessionDestroyed() {
                        refreshMediaSessions()
                    }
                }
                controller.registerCallback(callback, handler)
                observedControllers += ObservedController(controller, callback)
            }
    }

    private fun evaluateSessions(metadataChangedController: MediaController? = null) {
        if (!armed) return
        val best = chooseController()
        if (best == null) {
            activeController = null
            cuePlayer.stop()
            finalCuePlaying = false
            publishState(RotationPhase.WAITING_FOR_MUSIC)
            return
        }

        val previousController = activeController
        val previousKey = activeTrackKey
        val previousRemaining = if (durationMs > 0) durationMs - lastEstimatedPositionMs else Long.MAX_VALUE
        activeController = best

        val metadata = best.metadata
        val state = best.playbackState
        val newDuration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.coerceAtLeast(0) ?: 0
        val newTitle = metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
        val newArtist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
        val newKey = trackKey(best, metadata, newDuration, newTitle, newArtist)
        // Refreshing sessions creates new controller objects for the same player.
        val trackChanged = previousController?.sessionToken != best.sessionToken ||
            (newKey != null && newKey != previousKey)

        if (trackChanged && previousKey != null && previousRemaining <= AUTOPLAY_GUARD_MS &&
            metadataChangedController?.sessionToken == best.sessionToken &&
            state?.state == PlaybackState.STATE_PLAYING
        ) {
            activeTrackKey = newKey
            trackTitle = newTitle
            trackArtist = newArtist
            durationMs = newDuration
            pauseAtEnd(best, "Stopped before the next song")
            return
        }

        if (trackChanged) {
            activeTrackKey = newKey
            trackTitle = newTitle
            trackArtist = newArtist
            durationMs = newDuration
            plan = null
            nextCueIndex = 0
            blindNextCuePositionMs = null
            pauseRequestedAtEnd = false
            pauseObservedAtEnd = false
            finalCuePlaying = false
            if (state?.state == PlaybackState.STATE_PLAYING) {
                createPlan(estimatedPosition(state))
            }
        }

        when (state?.state) {
            PlaybackState.STATE_PLAYING -> {
                if (pauseRequestedAtEnd && !pauseObservedAtEnd) return
                if (pauseRequestedAtEnd) {
                    pauseRequestedAtEnd = false
                    pauseObservedAtEnd = false
                    createPlan(estimatedPosition(state))
                }
                if (plan == null && blindNextCuePositionMs == null) createPlan(estimatedPosition(state))
                publishPlaybackState(estimatedPosition(state))
            }
            PlaybackState.STATE_PAUSED, PlaybackState.STATE_STOPPED -> {
                if (pauseRequestedAtEnd) pauseObservedAtEnd = true
                publishState(
                    when {
                        finalCuePlaying -> RotationPhase.SWITCHING
                        pauseRequestedAtEnd -> RotationPhase.WAITING_FOR_MUSIC
                        else -> RotationPhase.MUSIC_PAUSED
                    },
                )
            }
            else -> publishState(RotationPhase.WAITING_FOR_MUSIC)
        }
    }

    private fun chooseController(): MediaController? {
        val controllers = observedControllers.map { it.controller }
        val playing = controllers.filter { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        if (playing.isNotEmpty()) {
            return playing.maxByOrNull { packagePriority(it.packageName) }
        }
        return controllers.firstOrNull { it.sessionToken == activeController?.sessionToken }
            ?: controllers.maxByOrNull { packagePriority(it.packageName) }
    }

    private fun packagePriority(packageName: String): Int = when (packageName) {
        "com.google.android.apps.youtube.music" -> 3
        "com.spotify.music" -> 2
        else -> 1
    }

    private fun createPlan(positionMs: Long) {
        lastEstimatedPositionMs = positionMs
        if (durationMs > 0) {
            val stop = (durationMs - settings.endGuardMillis).coerceAtLeast(positionMs)
            plan = RotationPlanner.create(positionMs, stop, settings)
            nextCueIndex = 0
            blindNextCuePositionMs = null
        } else {
            plan = null
            blindNextCuePositionMs = positionMs + settings.maximumSeconds * 1_000L
        }
    }

    private fun replanFromCurrentPosition(message: String? = null) {
        val controller = activeController ?: return
        val state = controller.playbackState ?: return
        val position = estimatedPosition(state)
        createPlan(position)
        if (state.state == PlaybackState.STATE_PLAYING) {
            publishPlaybackState(position, message)
        } else {
            evaluateSessions()
        }
    }

    private fun detectSeekAndMaybeReplan(newState: PlaybackState) {
        if (newState.state != PlaybackState.STATE_PLAYING || plan == null) return
        val newPosition = estimatedPosition(newState)
        if (abs(newPosition - lastEstimatedPositionMs) > SEEK_THRESHOLD_MS) {
            createPlan(newPosition)
        }
    }

    private fun tick() {
        if (!armed) return
        val controller = activeController
        val state = controller?.playbackState
        if (controller == null || state == null) {
            updateWakeLock(cuePlayer.isPlaying)
            if (MediaAccess.isGranted(this)) refreshMediaSessions()
            return
        }
        updateWakeLock(cuePlayer.isPlaying ||
            (state.state == PlaybackState.STATE_PLAYING && !pauseRequestedAtEnd))
        if (state.state != PlaybackState.STATE_PLAYING || pauseRequestedAtEnd) return

        val position = estimatedPosition(state)
        lastEstimatedPositionMs = position
        val currentPlan = plan

        if (currentPlan != null) {
            if (position >= currentPlan.stopPositionMs) {
                pauseAtEnd(controller)
                return
            }
            val cuePosition = currentPlan.cuePositionsMs.getOrNull(nextCueIndex)
            if (cuePosition != null && position >= cuePosition) {
                val remainingAfterCue = currentPlan.stopPositionMs - position - settings.cueSeconds * 1_000L
                // Allow one polling interval of timing jitter at an exact boundary.
                if (remainingAfterCue + TICK_MS >= settings.minimumSeconds * 1_000L) {
                    playCue()
                } else {
                    pauseAtEnd(controller, "Stopped before a too-short final block")
                    return
                }
            }
        } else {
            val cuePosition = blindNextCuePositionMs
            if (cuePosition != null && position >= cuePosition) {
                playCue()
                blindNextCuePositionMs = position +
                    (settings.cueSeconds + settings.maximumSeconds) * 1_000L
            }
        }
        publishPlaybackState(position)
    }

    private fun playCue(replanAfterCue: Boolean = false) {
        if (cuePlayer.isPlaying) return
        if (!replanAfterCue) nextCueIndex++
        publishState(RotationPhase.SWITCHING)
        cuePlayer.play(
            durationMs = settings.cueSeconds * 1_000L,
            playSound = settings.playSwitchSound,
            tone = settings.switchTone,
            soundUri = settings.soundUri,
        ) {
            activeController?.playbackState?.let { state ->
                if (state.state == PlaybackState.STATE_PLAYING) {
                    if (replanAfterCue) {
                        createPlan(estimatedPosition(state))
                    }
                    publishPlaybackState(estimatedPosition(state))
                }
            }
            evaluateSessions()
        }
    }

    private fun switchNow() {
        val controller = activeController ?: return
        val state = controller.playbackState ?: return
        if (state.state != PlaybackState.STATE_PLAYING || cuePlayer.isPlaying) return
        val position = estimatedPosition(state)
        val remaining = plan?.stopPositionMs?.minus(position)
        if (remaining != null &&
            remaining - settings.cueSeconds * 1_000L < settings.minimumSeconds * 1_000L
        ) {
            publishPlaybackState(position, "Too close to the end for another switch")
            return
        }
        playCue(replanAfterCue = true)
    }

    private fun pauseAtEnd(
        controller: MediaController,
        waitingMessage: String = when {
            plan?.danceBlocksMs?.isEmpty() == true -> "Not enough music left for a dance block"
            (plan?.trimmedEndMs ?: 0) > 0 -> "Song stopped early — choose the next song"
            else -> "Song finished — choose the next song"
        },
    ) {
        if (pauseRequestedAtEnd) return
        val hadDanceBlocks = plan?.danceBlocksMs?.isNotEmpty() != false
        pauseRequestedAtEnd = true
        pauseObservedAtEnd = false
        finalCuePlaying = false
        cuePlayer.stop()
        controller.transportControls.pause()
        plan = null
        blindNextCuePositionMs = null
        if (hadDanceBlocks && settings.playSwitchSound && settings.playSoundAtSongEnd) {
            finalCuePlaying = true
            publishState(RotationPhase.SWITCHING, "Final rotation for the next song")
            cuePlayer.play(
                durationMs = settings.cueSeconds * 1_000L,
                playSound = true,
                tone = settings.switchTone,
                soundUri = settings.soundUri,
            ) {
                finalCuePlaying = false
                publishState(RotationPhase.WAITING_FOR_MUSIC, waitingMessage)
            }
        } else {
            publishState(RotationPhase.WAITING_FOR_MUSIC, waitingMessage)
        }
    }

    private fun publishPlaybackState(position: Long, message: String? = null) {
        val currentPlan = plan
        val nextCue = currentPlan?.cuePositionsMs?.getOrNull(nextCueIndex)
            ?: blindNextCuePositionMs
        val remaining = if (durationMs > 0) {
            ((currentPlan?.stopPositionMs ?: durationMs) - position).coerceAtLeast(0)
        } else null
        publishState(
            phase = if (cuePlayer.isPlaying) RotationPhase.SWITCHING else RotationPhase.DANCING,
            message = message,
            nextCueInMs = nextCue?.minus(position)?.coerceAtLeast(0),
            remainingMs = remaining,
        )
    }

    private fun publishState(
        phase: RotationPhase,
        message: String? = null,
        nextCueInMs: Long? = null,
        remainingMs: Long? = null,
    ) {
        updateWakeLock(cuePlayer.isPlaying ||
            phase == RotationPhase.DANCING || phase == RotationPhase.SWITCHING)
        RotationStateStore.update(
            RotationUiState(
                armed = armed,
                phase = phase,
                trackTitle = trackTitle,
                trackArtist = trackArtist,
                nextCueInMs = nextCueInMs,
                stopInMs = remainingMs,
                blockDurationsMs = plan?.danceBlocksMs.orEmpty(),
                trimmedEndMs = plan?.trimmedEndMs ?: 0,
                message = message,
            ),
        )
        updateNotification(nextCueInMs, remainingMs)
    }

    private fun updateWakeLock(needed: Boolean) {
        if (!armed || !needed) {
            if (wakeLock.isHeld) wakeLock.release()
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (!wakeLock.isHeld || now - lastWakeLockRenewalMs >= WAKE_LOCK_RENEW_MS) {
            // Use timed acquisition and renew only while timing music or a cue.
            wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)
            lastWakeLockRenewalMs = now
        }
    }

    private fun estimatedPosition(state: PlaybackState): Long {
        if (state.position == PlaybackState.PLAYBACK_POSITION_UNKNOWN) return lastEstimatedPositionMs
        if (state.state != PlaybackState.STATE_PLAYING || state.lastPositionUpdateTime <= 0) {
            return state.position.coerceAtLeast(0)
        }
        val elapsed = (SystemClock.elapsedRealtime() - state.lastPositionUpdateTime).coerceAtLeast(0)
        return (state.position + elapsed * state.playbackSpeed).toLong().coerceAtLeast(0)
    }

    private fun trackKey(
        controller: MediaController,
        metadata: MediaMetadata?,
        duration: Long,
        title: String?,
        artist: String?,
    ): String? {
        val mediaId = metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
        if (!mediaId.isNullOrBlank()) return "${controller.packageName}:$mediaId"
        if (title == null && artist == null && duration <= 0) return null
        return "${controller.packageName}:${title.orEmpty()}:${artist.orEmpty()}:$duration"
    }

    private fun startForegroundServiceNotification() {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(null, null),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            },
        )
    }

    private fun updateNotification(nextCueInMs: Long?, remainingMs: Long?) {
        if (!armed) return
        val second = SystemClock.elapsedRealtime() / 1_000
        if (second == lastNotificationSecond && nextCueInMs != null) return
        lastNotificationSecond = second
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(nextCueInMs, remainingMs))
    }

    private fun buildNotification(nextCueInMs: Long?, remainingMs: Long?): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val switchIntent = servicePendingIntent(ACTION_SWITCH_NOW, 1)
        val disarmIntent = servicePendingIntent(ACTION_DISARM, 2)
        val status = when {
            !MediaAccess.isGranted(this) -> "Media access required"
            cuePlayer.isPlaying -> "Switch partners"
            nextCueInMs != null -> "Next switch ${formatTime(nextCueInMs)} · stops in ${formatTime(remainingMs)}"
            pauseRequestedAtEnd -> "Waiting for the next song"
            remainingMs != null -> "Music stops in ${formatTime(remainingMs)}"
            else -> "Waiting for music"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(trackTitle ?: "High 5 Rotate is armed")
            .setContentText(status)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "Switch now", switchIntent)
            .addAction(0, "Disarm", disarmIntent)
            .build()
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent {
        return PendingIntent.getService(
            this,
            requestCode,
            Intent(this, RotationService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Active rotation timer",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Keeps the user-armed dance rotation timer running"
            setSound(null, null)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun formatTime(milliseconds: Long?): String {
        if (milliseconds == null) return "--:--"
        val seconds = (milliseconds / 1_000).coerceAtLeast(0)
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
    }

    private data class ObservedController(
        val controller: MediaController,
        val callback: MediaController.Callback,
    )

    companion object {
        private const val ACTION_ARM = "org.toitlang.wcsrotate.ARM"
        private const val ACTION_DISARM = "org.toitlang.wcsrotate.DISARM"
        private const val ACTION_SWITCH_NOW = "org.toitlang.wcsrotate.SWITCH_NOW"
        private const val ACTION_SETTINGS_CHANGED = "org.toitlang.wcsrotate.SETTINGS_CHANGED"
        private const val ACTION_REFRESH = "org.toitlang.wcsrotate.REFRESH"
        private const val CHANNEL_ID = "rotation_active"
        private const val NOTIFICATION_ID = 50
        private const val TICK_MS = 250L
        private const val WAKE_LOCK_RENEW_MS = 30_000L
        private const val WAKE_LOCK_TIMEOUT_MS = 60_000L
        private const val SEEK_THRESHOLD_MS = 2_500L
        private const val AUTOPLAY_GUARD_MS = 3_000L

        fun arm(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RotationService::class.java).setAction(ACTION_ARM),
            )
        }

        fun disarm(context: Context) {
            context.startService(Intent(context, RotationService::class.java).setAction(ACTION_DISARM))
        }

        fun settingsChanged(context: Context) {
            if (RotationSettingsStore(context).isArmed()) {
                context.startService(
                    Intent(context, RotationService::class.java).setAction(ACTION_SETTINGS_CHANGED),
                )
            }
        }

        fun refreshIfArmed(context: Context) {
            if (RotationSettingsStore(context).isArmed()) {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, RotationService::class.java).setAction(ACTION_REFRESH),
                )
            }
        }
    }
}

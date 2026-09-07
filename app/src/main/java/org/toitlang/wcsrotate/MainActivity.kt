// Copyright (C) 2026 Toit contributors.
package org.toitlang.wcsrotate

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.toitlang.wcsrotate.media.MediaAccess
import org.toitlang.wcsrotate.model.RotationPhase
import org.toitlang.wcsrotate.model.RotationSettings
import org.toitlang.wcsrotate.model.RotationSettingsStore
import org.toitlang.wcsrotate.model.RotationStateStore
import org.toitlang.wcsrotate.model.RotationUiState
import org.toitlang.wcsrotate.model.SwitchTone
import org.toitlang.wcsrotate.power.BatteryAccess
import org.toitlang.wcsrotate.power.BatteryStatus
import org.toitlang.wcsrotate.rotation.CuePlayer
import org.toitlang.wcsrotate.rotation.RotationService
import org.toitlang.wcsrotate.ui.WcsRotateTheme
import java.util.Locale

class MainActivity : ComponentActivity() {
    private lateinit var settingsStore: RotationSettingsStore
    private val mediaAccessGranted = mutableStateOf(false)
    private val batteryStatus = mutableStateOf(BatteryStatus.OPTIMIZED)
    private lateinit var previewPlayer: CuePlayer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsStore = RotationSettingsStore(this)
        previewPlayer = CuePlayer(this, Handler(Looper.getMainLooper()))
        mediaAccessGranted.value = MediaAccess.isGranted(this)
        RotationService.refreshIfArmed(this)
        enableEdgeToEdge()
        setContent {
            WcsRotateTheme {
                val state by RotationStateStore.state.collectAsStateWithLifecycle()
                var settings by remember { mutableStateOf(settingsStore.load()) }
                val saveSettings: (RotationSettings) -> Unit = { newSettings ->
                    previewPlayer.stop()
                    settings = newSettings.sanitized()
                    settingsStore.save(settings)
                    RotationService.settingsChanged(this)
                }
                val audioFilePicker = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument(),
                ) { uri ->
                    if (uri != null) {
                        runCatching {
                            contentResolver.takePersistableUriPermission(
                                uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION,
                            )
                        }.onSuccess {
                            val previousUri = settings.soundUri
                            saveSettings(settings.copy(
                                switchTone = SwitchTone.AUDIO_FILE,
                                soundUri = uri.toString(),
                                soundName = audioFileName(uri),
                            ))
                            if (previousUri != null && previousUri != uri.toString()) {
                                runCatching {
                                    contentResolver.releasePersistableUriPermission(
                                        Uri.parse(previousUri),
                                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                                    )
                                }
                            }
                        }.onFailure {
                            Toast.makeText(
                                this,
                                "Could not keep access to this file. Choose a file stored on the phone.",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                }
                val notificationPermission = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) {
                    previewPlayer.stop()
                    RotationService.arm(this)
                }

                MainScreen(
                    state = state,
                    settings = settings,
                    mediaAccessGranted = mediaAccessGranted.value,
                    batteryStatus = batteryStatus.value,
                    onGrantMediaAccess = { startActivity(MediaAccess.settingsIntent(this)) },
                    onBatterySettings = {
                        if (!BatteryAccess.openSettings(this)) {
                            Toast.makeText(this, "Unable to open app settings on this phone.", Toast.LENGTH_LONG).show()
                        }
                    },
                    onSettingsChanged = saveSettings,
                    onChooseAudioFile = { audioFilePicker.launch(arrayOf("audio/*")) },
                    onPreviewTone = {
                        if (!RotationStateStore.state.value.armed) {
                            previewPlayer.play(
                                durationMs = settings.cueSeconds * 1_000L,
                                playSound = true,
                                tone = settings.switchTone,
                                soundUri = settings.soundUri,
                            ) { }
                        }
                    },
                    onArm = {
                        previewPlayer.stop()
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                            PackageManager.PERMISSION_GRANTED
                        ) {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            RotationService.arm(this)
                        }
                    },
                    onDisarm = { RotationService.disarm(this) },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        mediaAccessGranted.value = MediaAccess.isGranted(this)
        batteryStatus.value = BatteryAccess.status(this)
        RotationService.refreshIfArmed(this)
    }

    override fun onStop() {
        previewPlayer.stop()
        super.onStop()
    }

    private fun audioFileName(uri: Uri): String = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull() ?: "Selected audio file"
}

@Composable
private fun MainScreen(
    state: RotationUiState,
    settings: RotationSettings,
    mediaAccessGranted: Boolean,
    batteryStatus: BatteryStatus,
    onGrantMediaAccess: () -> Unit,
    onBatterySettings: () -> Unit,
    onSettingsChanged: (RotationSettings) -> Unit,
    onChooseAudioFile: () -> Unit,
    onPreviewTone: () -> Unit,
    onArm: () -> Unit,
    onDisarm: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "High 5 Rotate",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                HelpButton()
            }
            Text(
                text = "Arm once for class. Start songs in your usual music app.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (!mediaAccessGranted) {
                PermissionCard(onGrantMediaAccess)
            }

            StatusCard(state)

            Button(
                onClick = if (state.armed) onDisarm else onArm,
                enabled = mediaAccessGranted || state.armed,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp),
                colors = if (state.armed) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    )
                } else {
                    ButtonDefaults.buttonColors()
                },
            ) {
                Text(
                    text = if (state.armed) "Disarm for the day" else "Arm rotation timer",
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            BatterySettingsCard(batteryStatus, onBatterySettings)
            TimingSettingsCard(settings, onSettingsChanged)
            SoundSettingsCard(
                settings = settings,
                armed = state.armed,
                onChanged = onSettingsChanged,
                onChooseAudioFile = onChooseAudioFile,
                onPreviewTone = onPreviewTone,
            )
            PrivacyButton()

            Text(
                text = "The music keeps playing quietly during each switch. The app pauses near the end of the current song and then waits for you to choose another.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
    }
}

@Composable
private fun HelpButton() {
    val context = LocalContext.current
    var showHelp by remember { mutableStateOf(false) }
    val help = remember {
        context.resources.openRawResource(R.raw.help).bufferedReader().use { it.readText() }
    }
    TextButton(onClick = { showHelp = true }) { Text("Help") }
    if (showHelp) {
        AlertDialog(
            onDismissRequest = { showHelp = false },
            title = { Text("Using High 5 Rotate") },
            text = { Text(help, modifier = Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = {
                TextButton(onClick = { showHelp = false }) { Text("Got it") }
            },
        )
    }
}

@Composable
private fun PrivacyButton() {
    val context = LocalContext.current
    var showPrivacy by remember { mutableStateOf(false) }
    val policy = remember {
        context.resources.openRawResource(R.raw.privacy_policy).bufferedReader().use { it.readText() }
    }
    TextButton(onClick = { showPrivacy = true }, modifier = Modifier.fillMaxWidth()) {
        Text("Privacy · High 5 Rotate ${BuildConfig.VERSION_NAME}")
    }
    if (showPrivacy) {
        AlertDialog(
            onDismissRequest = { showPrivacy = false },
            title = { Text("Privacy policy") },
            text = { Text(policy, modifier = Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = {
                TextButton(onClick = { showPrivacy = false }) { Text("Close") }
            },
        )
    }
}

@Composable
private fun PermissionCard(onGrant: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("One-time setup", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "Android calls this notification access. It lets High 5 Rotate see the active song, its position, and its pause control. No notification data is saved or sent anywhere.",
            )
            FilledTonalButton(onClick = onGrant, modifier = Modifier.fillMaxWidth()) {
                Text("Grant media access")
            }
        }
    }
}

@Composable
private fun BatterySettingsCard(status: BatteryStatus, onOpenSettings: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                when (status) {
                    BatteryStatus.UNRESTRICTED -> "Background timing unrestricted"
                    BatteryStatus.OPTIMIZED -> "Background activity allowed"
                    BatteryStatus.RESTRICTED -> "Background activity restricted"
                },
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                when (status) {
                    BatteryStatus.UNRESTRICTED -> "Battery optimization is off for High 5 Rotate. No setup needed."
                    BatteryStatus.OPTIMIZED -> "Battery optimization is on. You can use the timer as it is. " +
                        "If cues are delayed with the screen locked, open this app's battery settings " +
                        "and choose Unrestricted."
                    BatteryStatus.RESTRICTED -> "Android is restricting this app in the background. " +
                        "Open this app's battery settings and allow background usage for screen-off timing."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            if (status != BatteryStatus.UNRESTRICTED) {
                TextButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                    Text("App battery settings")
                }
            }
        }
    }
}

@Composable
private fun StatusCard(state: RotationUiState) {
    val phaseTitle = when (state.phase) {
        RotationPhase.DISARMED -> "Not armed"
        RotationPhase.WAITING_FOR_MEDIA_ACCESS -> "Waiting for media access"
        RotationPhase.WAITING_FOR_MUSIC -> "Armed — waiting for music"
        RotationPhase.DANCING -> "Dancing"
        RotationPhase.SWITCHING -> "Switch partners"
        RotationPhase.MUSIC_PAUSED -> "Music paused"
    }
    val highlight = state.phase == RotationPhase.SWITCHING
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (highlight) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.primaryContainer
            },
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(phaseTitle, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            state.trackTitle?.let {
                Text(it, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            }
            state.trackArtist?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            if (state.nextCueInMs != null) {
                Text(
                    text = formatClock(state.nextCueInMs),
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text("until next switch", style = MaterialTheme.typography.labelLarge)
            }
            if (state.trackRemainingMs != null) {
                Text("${formatClock(state.trackRemainingMs)} left in song")
            }
            if (state.blockDurationsMs.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text(
                    text = state.blockDurationsMs.joinToString("  ·  ") {
                        "${it / 1_000}s"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
                Text(
                    if (state.usesLongBlockFallback) "Long-block fallback" else "Adaptive song plan",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            state.message?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun TimingSettingsCard(
    settings: RotationSettings,
    onChanged: (RotationSettings) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Timing", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "The planner aims for the middle of the normal range while respecting the final minimum.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(4.dp))
            SettingStepper(
                "Shortest normal block", settings.minimumSeconds, "s", 10, 180,
                description = "Minimum dance time between switches, excluding the switch itself.",
            ) {
                onChanged(settings.copy(minimumSeconds = it))
            }
            SettingStepper(
                "Longest normal block", settings.maximumSeconds, "s", 10, 180,
                description = "Maximum dance time. The planner prefers the middle of the normal range.",
            ) {
                onChanged(settings.copy(maximumSeconds = it))
            }
            SettingStepper(
                "Shortest final block", settings.finalMinimumSeconds, "s", 5, 180,
                description = "The last dance block may be shorter, so the schedule can fit the song.",
            ) {
                onChanged(settings.copy(finalMinimumSeconds = it))
            }
            SettingStepper(
                "Switch time", settings.cueSeconds, "s", 1, 15,
                description = "Time to high-five and rotate while the music plays quietly.",
            ) {
                onChanged(settings.copy(cueSeconds = it))
            }
            SettingStepper(
                "End guard", settings.endGuardMillis / 100, "s", 1, 30,
                description = "Pause this far before the song ends. Increase if the next song starts briefly.",
                valueText = String.format(Locale.ROOT, "%.1f s", settings.endGuardMillis / 1_000.0),
            ) {
                onChanged(settings.copy(endGuardMillis = it * 100))
            }
        }
    }
}

@Composable
private fun SoundSettingsCard(
    settings: RotationSettings,
    armed: Boolean,
    onChanged: (RotationSettings) -> Unit,
    onChooseAudioFile: () -> Unit,
    onPreviewTone: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Switch sound", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            BooleanSetting(
                label = "Play a switch tone",
                description = "Turn off to keep the quiet switch interval without a sound.",
                checked = settings.playSwitchSound,
                onChanged = { onChanged(settings.copy(playSwitchSound = it)) },
            )
            Box {
                OutlinedButton(
                    onClick = { menuExpanded = true },
                    enabled = settings.playSwitchSound,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (settings.switchTone == SwitchTone.AUDIO_FILE) {
                        settings.soundName ?: "Audio file"
                    } else {
                        settings.switchTone.label
                    })
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    SwitchTone.entries.filter { it != SwitchTone.AUDIO_FILE }.forEach { tone ->
                        DropdownMenuItem(
                            text = { Text(tone.label) },
                            onClick = {
                                menuExpanded = false
                                onChanged(settings.copy(switchTone = tone))
                            },
                        )
                    }
                    if (settings.soundUri != null) {
                        DropdownMenuItem(
                            text = { Text(settings.soundName ?: "Selected audio file") },
                            onClick = {
                                menuExpanded = false
                                onChanged(settings.copy(switchTone = SwitchTone.AUDIO_FILE))
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Choose audio file…") },
                        onClick = {
                            menuExpanded = false
                            onChooseAudioFile()
                        },
                    )
                }
            }
            if (settings.switchTone == SwitchTone.AUDIO_FILE) {
                Text(
                    "The file plays once, for at most the switch time. " +
                        "If it is unavailable, a double beep plays instead.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            OutlinedButton(
                onClick = onPreviewTone,
                enabled = settings.playSwitchSound && !armed,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Preview sound")
            }
            if (armed) {
                Text("Disarm to preview sounds.", style = MaterialTheme.typography.bodySmall)
            }
            BooleanSetting(
                label = "Final tone after the song",
                description = "Signals one last rotation before the next song.",
                checked = settings.playSoundAtSongEnd,
                enabled = settings.playSwitchSound,
                onChanged = { onChanged(settings.copy(playSoundAtSongEnd = it)) },
            )
        }
    }
}

@Composable
private fun BooleanSetting(
    label: String,
    checked: Boolean,
    onChanged: (Boolean) -> Unit,
    description: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                },
            )
            description?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onChanged,
            enabled = enabled,
        )
    }
}

@Composable
private fun SettingStepper(
    label: String,
    value: Int,
    unit: String,
    minimum: Int,
    maximum: Int,
    description: String? = null,
    valueText: String? = null,
    onChanged: (Int) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            OutlinedButton(
                onClick = { onChanged((value - 1).coerceAtLeast(minimum)) },
                enabled = value > minimum,
                contentPadding = ButtonDefaults.ContentPadding,
            ) {
                Text("−")
            }
            Text(
                text = valueText ?: "$value $unit",
                modifier = Modifier.padding(horizontal = 10.dp),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            OutlinedButton(
                onClick = { onChanged((value + 1).coerceAtMost(maximum)) },
                enabled = value < maximum,
                contentPadding = ButtonDefaults.ContentPadding,
            ) {
                Text("+")
            }
        }
        description?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun formatClock(milliseconds: Long): String {
    val seconds = (milliseconds / 1_000).coerceAtLeast(0)
    return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
}

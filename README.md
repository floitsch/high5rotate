# High 5 Rotate

![High 5 Rotate icon](store/assets/icon.png)

Give your partner a high five, then rotate. That's the idea behind the name and the raised-hand icon with its counter-clockwise rotation arrow.

High 5 Rotate is an Android-only teaching aid for West Coast Swing classes. Arm it once for the session, then play individual songs in YouTube Music, Spotify, or another Android media-session player.

While a song plays, the app:

- reads its duration and current position through Android media-session access;
- divides it into partner blocks within your shortest and longest limits, preferring longer blocks;
- temporarily ducks music and plays a configurable switch cue;
- ends the song earlier when a full final block cannot fit; and
- pauses just before the media player advances to the next song.

The app stays armed after pausing and waits for the teacher to select the next song.

## Defaults

- Shortest block: 45 seconds
- Longest block: 65 seconds
- Switch time: 3 seconds
- Add switch time to the first block: disabled
- Switch tone: double beep (enabled)
- Final post-song rotation tone: disabled
- End guard: 0.5 seconds

All of these values are configurable in the app. Every dance block, including the last, uses the same shortest and longest limits before the optional first-block extra. When several schedules fit, the planner chooses fewer, longer blocks. It uses the full available song time whenever possible; otherwise it trims the smallest possible tail. For example, with 45–65 second blocks and 3-second switches, 70 seconds of available music becomes one 65-second dance. If less than one shortest block remains, it pauses immediately. There is no separate target or final-block setting.

Check **Add switch time to the first block** to give dancers time for a slow intro and starter step. It adds one switch interval on top of the first block’s normal dance time, with music playing normally. For example, fixed 60-second dances with 6-second switches become 66 seconds, switch, 60 seconds, switch, 60 seconds: 198 seconds total. The extra is reserved when planning and is not repeated after a partner switch.

## First run

1. Open **High 5 Rotate**.
2. Tap **Grant media access** and enable **High 5 Rotate media access** in Android settings.
3. Check the detected background status. If restricted, use **App battery settings** to allow background usage. If merely optimized, you can use the timer as-is; choose Unrestricted if screen-off cues are delayed. The button opens this app directly, with App info as a fallback.
4. Optionally choose **Double beep**, **Short beep**, **Chime**, or **Choose audio file…** under **Switch sound**. Preview while disarmed. Audio files play once, for at most the switch time; unavailable files fall back to a double beep.
5. Return to the app and tap **Arm rotation timer**.
6. Connect the phone to the Bluetooth speaker and start a song in the usual music app.
7. After the app pauses at the end, choose the next song. There is no need to arm it again.
8. Tap **Disarm for the day** when class is over.

Tap **Help** for the in-app guide. Each timing control has a short explanation; **End guard** is shown directly in seconds (0.5 s by default), meaning how early to request the final pause.

Android describes the required media-session permission as “notification access.” High 5 Rotate uses it only to discover active media sessions and their playback controls. It does not save or transmit notification contents and does not request internet access.

## Build

The project requires JDK 17 or newer and an Android SDK containing API 36.

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
```

The debug APK is produced at `app/build/outputs/apk/debug/app-debug.apk`.

To install it on a connected Android phone:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Implementation notes

- `RotationService` is a user-started foreground service. A partial CPU wake lock is held while timing music or a cue and released when paused, waiting, disarmed, or destroyed. Android Doze and manufacturer battery restrictions can still affect timing; the app distinguishes explicit background restrictions from normal optimization and offers a direct link to its battery settings.
- Media sessions are matched by session token, so reopening the app preserves the current song plan. Sound-only settings changes also preserve it.
- `MediaAccessService` grants access to other apps' active Android media sessions.
- `RotationPlanner` computes the song schedule and has unit tests covering both block limits, longer-block preference, and minimal end trimming.
- `CuePlayer` requests transient ducking audio focus, plays built-in tones or a selected audio file, and releases audio resources and focus after the configured switch time. File access uses Android's document picker and a persisted grant; no broad storage permission is needed.
- The switch tone can be disabled while retaining the quiet interval. When tones are enabled, an optional final tone after pausing the song signals one last partner rotation for the next song.
- If a player does not publish track duration, the app uses the longest block setting for each dance block, plus the switch interval. The optional first-block extra also applies. It cannot reliably stop before the next song in that degraded mode.

The end guard exists because media-control and Bluetooth pipelines can introduce small timing differences. Adjust it on the real classroom phone and speaker if the end is cut too early or the next song begins briefly.

## Publication

The public project is [floitsch/high5rotate](https://github.com/floitsch/high5rotate). The app keeps package ID `org.toitlang.wcsrotate` for compatibility with existing installations.

Start with the [step-by-step Play Console guide](store/publishing.md). It includes upload-key setup, unsigned versus signed bundles, listing copy, policy declaration drafts, and device testing. GitHub Actions runs unit tests, lint, and debug/release builds. Its release bundle artifact is unsigned.

The [privacy policy](docs/privacy.html) is also included in the app. `docs/` is ready for GitHub Pages. Generate the public privacy page and store PNGs from the app's policy and vector icon with `python3 scripts/generate-store-assets.py` (requires `rsvg-convert` and Pillow).

Play publication still needs the publisher's account setup, support email, upload key, real device screenshots/video, and applicable testing/review. See the [device checklist](store/testing.md).

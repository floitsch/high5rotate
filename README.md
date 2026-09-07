# high5rotate

![high5rotate icon](store/assets/icon.png)

Give your partner a high five, then rotate. That's the idea behind the name and the raised-hand icon with its counter-clockwise rotation arrow.

high5rotate is an Android-only teaching aid for West Coast Swing classes. Arm it once for the session, then play individual songs in YouTube Music, Spotify, or another Android media-session player.

While a song plays, the app:

- reads its duration and current position through Android media-session access;
- divides it into partner blocks close to the configured target;
- temporarily ducks music and plays a configurable switch cue;
- avoids a switch that would leave an unreasonably short final block; and
- pauses just before the media player advances to the next song.

The app stays armed after pausing and waits for the teacher to select the next song.

## Defaults

- Target dance block: 50 seconds
- Normal block range: 45–65 seconds
- Minimum final block: 35 seconds
- Switch time: 3 seconds
- Switch tone: double beep (enabled)
- Final post-song rotation tone: disabled
- End guard: 0.5 seconds

All of these values are configurable in the app. If no schedule can satisfy the limits, the planner deliberately chooses fewer, longer blocks instead of short blocks.

## First run

1. Open **high5rotate**.
2. Tap **Grant media access** and enable **high5rotate media access** in Android settings.
3. Use **Battery settings** to exempt high5rotate from battery optimization for reliable timing with the screen off.
4. Optionally choose **Double beep**, **Short beep**, **Chime**, or **Choose audio file…** under **Switch sound**. Preview while disarmed. Audio files play once, for at most the switch time; unavailable files fall back to a double beep.
5. Return to the app and tap **Arm rotation timer**.
6. Connect the phone to the Bluetooth speaker and start a song in the usual music app.
7. After the app pauses at the end, choose the next song. There is no need to arm it again.
8. Tap **Disarm for the day** when class is over.

Android describes the required media-session permission as “notification access.” high5rotate uses it only to discover active media sessions and their playback controls. It does not save or transmit notification contents and does not request internet access.

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

- `RotationService` is a user-started foreground service. A partial CPU wake lock is held while timing music or a cue and released when paused, waiting, disarmed, or destroyed. Android Doze and manufacturer battery restrictions still require the battery-settings setup above.
- Media sessions are matched by session token, so reopening the app preserves the current song plan. Sound-only settings changes also preserve it.
- `MediaAccessService` grants access to other apps' active Android media sessions.
- `RotationPlanner` computes the whole-song schedule and has unit tests covering the configurable constraints and impossible-duration fallback.
- `CuePlayer` requests transient ducking audio focus, plays built-in tones or a selected audio file, and releases audio resources and focus after the configured switch time. File access uses Android's document picker and a persisted grant; no broad storage permission is needed.
- The switch tone can be disabled while retaining the quiet interval. When tones are enabled, an optional final tone after pausing the song signals one last partner rotation for the next song.
- If a player does not publish track duration, the app falls back to target-length rotations. It cannot reliably stop before the next song in that degraded mode.

The end guard exists because media-control and Bluetooth pipelines can introduce small timing differences. Adjust it on the real classroom phone and speaker if the end is cut too early or the next song begins briefly.

## Publication

The public project is [floitsch/high5rotate](https://github.com/floitsch/high5rotate). The app keeps package ID `org.toitlang.wcsrotate` for compatibility with existing installations.

Start with the [step-by-step Play Console guide](store/publishing.md). It includes upload-key setup, unsigned versus signed bundles, listing copy, policy declaration drafts, and device testing. GitHub Actions runs unit tests, lint, and debug/release builds. Its release bundle artifact is unsigned.

The [privacy policy](docs/privacy.html) is also included in the app. `docs/` is ready for GitHub Pages. Generate the public privacy page and store PNGs from the app's policy and vector icon with `python3 scripts/generate-store-assets.py` (requires `rsvg-convert` and Pillow).

Play publication still needs the publisher's account setup, support email, upload key, real device screenshots/video, and applicable testing/review. See the [device checklist](store/testing.md).

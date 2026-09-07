# Play Console declaration drafts

Review these with the publisher before submission. They describe the source in this repository, not unrelated music players or future versions.

## Data safety and privacy

- **Data collected or shared off-device:** No. This build has no Internet permission, advertising, analytics, backend, or account system. Song metadata is used locally in memory; preferences and selected-file references are stored locally. Android backup is disabled, subject to manufacturer device-transfer behavior. External document providers and music apps operate independently.
- **Account creation:** None; no account-deletion feature is applicable.
- **Privacy policy:** `https://floitsch.github.io/high5rotate/privacy.html`, once publicly reachable. The same text is accessible in the app.
- **Ads:** No.
- **App access:** No high5rotate sign-in or paywall. Reviewers must grant notification access, use an Android media-session player, arm the timer, and play a track. A reviewer can use a local music player and an audio file they own; a paid streaming account is not required by high5rotate.

Play defines collection around transmission off the device; processing solely on the device is outside that definition. Reassess the form if adding network access, telemetry, third-party SDKs, or other data flows. [Data safety guidance](https://support.google.com/googleplay/android-developer/answer/10787469?hl=en)

## Audience and rating

The intended users are dance teachers. The publisher must select the actual target age groups and complete the content-rating questionnaire truthfully; no rating has been assigned by this project. The app has no chat, social feeds, ads, purchases, or bundled music. It displays metadata from the user's chosen music player and can play a user-selected sound. Do not declare child-directed distribution unless you intend it and have completed the corresponding requirements.

## Foreground service: specialUse

**Function:** A user arms a dance-class partner-rotation timer. The service monitors another music app's playback position, schedules brief rotation cues, ducks music during switches, and requests pause near the song's end. It remains armed until the user disarms it, with an ongoing notification and a Disarm action.

**Impact of deferral/interruption:** Delayed cues cause uneven partner time and missed switches. Delaying the final pause can allow the next song to start. The timer must follow playback in real time while the teacher uses the music app or locks the phone; deferred jobs cannot provide this behavior.

**User control:** Start with “Arm rotation timer”; stop with “Disarm for the day” or the notification's “Disarm” action. CPU wake locks are limited to active music timing and cue playback, with a timeout and explicit release.

**Video to record:** Show notification-access setup, arming, starting a local song in another player, the ongoing notification, an audible rotation cue while backgrounded, the end pause, and disarming. Include a screen-lock demonstration using a second camera if needed. Use an accessible review-video URL and audio you may distribute.

Play reviews the declared special-use justification; approval is not automatic. [Foreground-service declaration requirements](https://support.google.com/googleplay/android-developer/answer/13392821?hl=en)

## Permission inventory

| Permission / capability | Purpose |
| --- | --- |
| Notification-listener access | Discover active media sessions; no notification message contents are read. |
| POST_NOTIFICATIONS | Show the armed timer and controls. |
| FOREGROUND_SERVICE / FOREGROUND_SERVICE_SPECIAL_USE | Continue the user-started class timer in the background. |
| WAKE_LOCK | Keep CPU timing active while music or a cue plays; screen stays off. |
| System document picker grant | Read only the selected sound file; no broad media/storage permission. |

There is no accessibility service, microphone permission, exact-alarm permission, full-screen intent, direct battery-exemption request permission, or app Internet permission. The battery button opens Android's settings so the user can choose an exemption.

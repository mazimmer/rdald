# RdAld (ReadAloud v2)

Android app that turns links and text into listenable, read-aloud articles using the
device's Text‑to‑Speech engine. Share a link, it downloads the article in "reader mode",
detects the language, and reads it sentence by sentence with a fitting voice — controllable
via the app, the media notification, a smartwatch (MediaController) **and Bluetooth AVRCP
headsets**.

## Requirements

- **Target OS:** Android (minSdk 31 / Android 12, targetSdk 34, compileSdk 34).
- **Distribution:** F‑Droid. **No Google Play build, no Google Play Services, no Firebase.**
  Uses only platform/system APIs and open‑source libraries.
- Kotlin, single module (`:app`).
- Package / applicationId: `com.rdald.app` (app label: **RdAld**).

## Features (current)

- Receive text/links via **share** (ACTION_SEND text/plain) and via **paste/+** dialog.
- **URL → reader‑mode extraction**: downloads the page, extracts the main article with
  **Readability4J**, reconstructs paragraphs with **Jsoup** (no menus/headers/footers).
- **Persistent list** of saved texts (SQLite), oldest→newest (new items queue at the bottom).
- Playback: sentence-by-sentence TTS with **pause/stop/resume**, **forward/back** (±1 sentence).
- **Media controls**: notification + **Bluetooth AVRCP headsets** + watches (via media3).
- **Language detection** + **random fitting voice** per item (offline voices preferred).
- **Speech rate / pitch** settings applied per item.
- Background playback via a foreground `MediaLibraryService`.

## Architecture

```
MainActivity  ── share / + / list ──► SavedTextDb (SQLite)
     │ tap item
     ▼
ReaderActivity ── MediaController ──► PlaybackService (MediaLibraryService)
     │ startService(ACTION_LOAD_TEXT)
     ▼
PlaybackService ── owns ──► TtsPlayer (media3 SimpleBasePlayer, 1 MediaItem per sentence)
     │                                   │
     │                                   ├─ TextToSpeech.synthesizeToFile()  → WAV
     │                                   └─ MediaPlayer (plays WAV in OUR process)
DownloadService ── fetch + Readability4J + Jsoup ──► SavedTextDb  ──broadcast──► MainActivity refresh
```

Key classes (under `app/src/main/java/com/rdald/app/`):

- `MainActivity` — list, share/paste intake, URL detection, permission request.
- `ReaderActivity` — displays text; controls playback via media3 `MediaController`.
- `SettingsActivity` — speech rate/pitch & forward/rewind intervals (`SeekBarPreference`).
- `service/PlaybackService` — `MediaLibraryService`; builds a `MediaLibrarySession`; handles
  `ACTION_LOAD_TEXT` and forwarded media keys.
- `service/TtsPlayer` — custom media3 `Player`: detects language, picks a voice, synthesizes
  sentences to WAV files and plays them with a `MediaPlayer`. Position model: 1 sentence = 1 s.
- `service/DownloadService` — background fetch + readability extraction → DB.
- `receiver/AppMediaButtonReceiver` — extends media3 `MediaButtonReceiver` (AVRCP forwarding).
- `data/SavedTextDb` — `SQLiteOpenHelper` persistence (no Room/KSP — see notes).
- `SavedText` — `@Parcelize` data model (title, content, createdAt, id).

## Critical implementation details (hard-won)

1. **Bluetooth AVRCP only targets the app whose UID is actively playing audio.**
   Android's `MediaSessionStack.findMediaButtonSession()` chooses the "media button session"
   from `getSortedAudioPlaybackClientUids()`. `TextToSpeech.speak()` plays audio **inside the
   TTS engine's process**, so the active UID was never RdAld's and our session was never
   selected → headset did nothing. **Fix:** render each sentence with
   `tts.synthesizeToFile()` and play the WAV with a `MediaPlayer` **in RdAld's own process**.
   This is what makes Bose/AVRCP/notification/watch all work. Do not regress to `tts.speak()`.

2. **Per-sentence playlist.** Each sentence is one `MediaItem`, so the system Next/Previous
   buttons (notification, watch, AVRCP) map to next/previous sentence. With a single item,
   "next" was a no-op.

3. **The service must own the playlist.** Do **not** call `controller.setMediaItem()` from the
   Activity: it makes the session's playlist (1 item) disagree with the player's (N items) and
   media3 throws `IllegalStateException` in `PlayerInfo.mergePlayerInfo`. Instead the Activity
   sends `ACTION_LOAD_TEXT`; `TtsPlayer.playText()` owns the sentence playlist.

4. **`contentPositionMs` is per-item** and must be ≤ that item's duration. Reporting a
   cumulative position caused a media3 assertion; each sentence starts at 0.

5. **POST_NOTIFICATIONS runtime permission is required** (Android 13+). Without a displayed
   media notification the session is not treated as a live "media controls" session. It is
   requested at runtime in `MainActivity`.

6. **TTS readiness race.** If an item is opened before `TextToSpeech` finishes initializing,
   `tts.voices` is null and no language voice applies. `playText()` defers until TTS is ready.

7. **Language detection** uses a stopword heuristic (de/en/fr/es/it/nl/pt/sv/da/no) with the
   platform `TextClassifier` as fallback. The classifier alone mis-detected German as English.

8. **Audio cache key** = sentence hash **+ speech rate + pitch + voice**, so changing settings
   or picking a different voice re-synthesizes instead of replaying a stale file.

9. **Audio focus** is requested on play and abandoned on pause/stop; audio uses the media
   stream/usage so it behaves like media.

10. **Queue order** is `createdAt ASC` (new items at the bottom).

## Persistence

SQLite via framework `SQLiteOpenHelper` (`readaloud.db`, table `saved_texts`:
id/title/content/createdAt). Room was avoided because the only cached Room (2.8.5) requires
Kotlin 2.x + KSP while the project uses Kotlin 1.9.20; the toolchain upgrade was riskier than
a small hand-written DB layer.

## Notable libraries

- media3 `media3-session` / `media3-common` / `media3-ui` 1.3.1
- `androidx.media:media` 1.6.0, `androidx.core:core-ktx` 1.12.0, `appcompat` 1.6.1,
  `material` 1.11.0, `recyclerview` 1.3.2, `preference-ktx` 1.2.1
- `org.jsoup:jsoup` 1.21.2, `net.dankito.readability4j:readability4j` 1.0.8

## Build & run

```
cd v2
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`local.properties` sets `sdk.dir`. Version is defined in `app/build.gradle.kts`
(`versionCode` / `versionName`).

## Known limitations / next steps

- **Inbox / archive tabs** (mark finished → archive; archive view) — not yet implemented.
- **Reading-time estimates** (total/remaining) + spoken announcements
  ("remaining item time is …", "continuing with the next item", "no more items in the inbox").
- Sentence splitting is regex-based (`(?<=[.!?])\s+`); may split on abbreviations.
- Downloaded items don't retain the source URL in the DB (title/content only).
- No per-item resume position persistence yet.

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

## Features (current, v0.5.7)

**Intake**
- Receive text/links via **share** (ACTION_SEND text/plain). Sharing is **background-only**:
  an invisible `ShareActivity` (`Theme.NoDisplay`) stores the text or starts a download and
  shows only a toast — the app UI does **not** come to the foreground.
- **Paste/+** dialog inside the app.
- **URL → reader‑mode extraction**: downloads the page, extracts the main article with
  **Readability4J**, reconstructs paragraphs with **Jsoup** (no menus/headers/footers).
- **Favicon**: the site icon is fetched on download (`<link rel="…icon…">`, then
  `/favicon.ico` / `/apple-touch-icon.png`), validated, cached to internal storage, and shown
  in the list row.

**Library / list**
- **Persistent list** (SQLite), oldest→newest (new items queue at the bottom).
- **Inbox / Archive tabs**. Long‑press multi‑select → **Archive** (inbox) or **Delete**.
- Tapping an archived item returns it to the **bottom of the inbox** (reset to 0% only if fully
  read, otherwise keeps the archived percentage) and opens it for playback.
- **Top-of-tab summary**: `N items · ~X total · ~Y remaining` (estimated listening time for the
  current tab; remaining accounts for each item's saved progress).
- Per-row date, progress %, total and remaining time estimate.

**Playback**
- Sentence-by-sentence TTS with **pause/stop/resume**, **forward/back** (±1 sentence).
- **Media controls**: notification + **Bluetooth AVRCP headsets** + watches (via media3).
- **Language detection** + **random fitting voice** per item (offline voices preferred).
- **Speech rate / pitch** settings applied per item.
- Background playback via a foreground `MediaLibraryService`.
- **Reads the item title first** (in the item's own voice), then the optional time announcement,
  then the text — reading only starts after the title audio has fully finished.
- **Spoken announcements**: `"The item time is N minutes"` / `"The remaining item time is N
  minutes"` (only when remaining > 5 min), `"Continuing with the next item"`,
  `"No more items in the inbox"` — all in English via an available English voice.
- **Reader view**: tap a paragraph to jump, per-paragraph FF/RW, **auto-scroll**, and a
  **high-contrast current-sentence highlight** (black background / white text, chosen for e-ink).
- Remembers **resume progress** per item; auto-advance to the next item when one finishes.

**UI**
- E-ink-friendly contrast: high-contrast sentence highlight and a **black seek-bar thumb/progress**
  on the grey control panel.

## Architecture

```
ShareActivity ── shared text/URL (toast only) ──┐
MainActivity  ── + / list / tabs ───────────────┴──► SavedTextDb (SQLite)
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

- `MainActivity` — inbox/archive list + tabs, top-of-tab time summary, paste intake, URL
  detection, multi-select archive/delete, permission request, download-refresh receiver.
- `ShareActivity` — invisible (`Theme.NoDisplay`) share target; stores shared text or starts a
  background download and shows only a toast (never opens the app UI).
- `ReaderActivity` — displays text; tap-to-jump, auto-scroll, current-sentence highlight;
  controls playback via media3 `MediaController`.
- `SavedTextAdapter` — list rows with favicon (`FaviconLoader`, off-thread + `LruCache`),
  progress/remaining time, long-press multi-select.
- `SettingsActivity` — speech rate/pitch & forward/rewind intervals (`SeekBarPreference`).
- `service/PlaybackService` — `MediaLibraryService`; builds a `MediaLibrarySession`; handles
  `ACTION_LOAD_TEXT` and forwarded media keys.
- `service/TtsPlayer` — custom media3 `Player`: detects language, picks a voice, synthesizes
  sentences to WAV files and plays them with a `MediaPlayer`. Position model: 1 sentence = 1 s.
- `service/DownloadService` — background fetch + readability extraction + favicon caching → DB.
- `receiver/AppMediaButtonReceiver` — extends media3 `MediaButtonReceiver` (AVRCP forwarding).
- `data/SavedTextDb` — `SQLiteOpenHelper` persistence (no Room/KSP — see notes).
- `SavedText` — `@Parcelize` data model (title, content, createdAt, id, archived, progress,
  iconPath).

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

11. **Title is read before the text, and the wait is real.** `playText()` announces the title
    through a separate announcement `MediaPlayer`; the first sentence is only spoken from that
    player's `onCompletion`, so reading starts exactly when the title audio ends (no fixed
    delay). Any still-playing previous item is paused first, otherwise the old sentence overlaps
    the title.
    A user **seek/tap/skip aborts** any in-progress title or time announcement
    (`abortAnnouncements()` stops the announcement player and bumps an `announceGen` token so the
    stale start/read callbacks are ignored), so a tapped paragraph starts immediately.

12. **Announcements use a reusable English voice** (rate/pitch 1.0) for app messages, but the
    *title* is announced with the **item's own voice** so its language is correct. The chosen
    voice name is part of the announcement cache filename; the item voice is re-applied before
    reading resumes.

13. **Background sharing needs an invisible Activity.** `ACTION_SEND` cannot target a
    `BroadcastReceiver`, and a normal Activity would foreground the app. `ShareActivity` uses
    `@android:style/Theme.NoDisplay`, does no `setContentView`, and calls `finish()` in
    `onCreate` (required with NoDisplay), so it just toasts and returns to the source app.
    The `SEND` intent-filter lives there, not on `MainActivity` (avoids duplicate share targets).

14. **Favicon caching.** `DownloadService` resolves the icon against the *post-redirect* URL,
    validates it decodes via `BitmapFactory` bounds, caps it at 512 KB, writes it to
    `filesDir/favicons/`, and stores the absolute path in the DB. `SavedTextAdapter` loads it
    off the main thread with an `LruCache`, guarding against view recycling via `view.tag`.

15. **Settings live in the `"settings"` SharedPreferences file** (not the default file) and use
    `Int` values (e.g. `speech_rate` = 100 → 1.0×). Mismatched file/type previously made changes
    appear ignored.

16. **E-ink contrast choices.** The current sentence uses a **black background + white text**
    span rather than a light yellow highlight, and the seek bar sets a **black thumb/progress**
    on the grey control panel, because subtle tints were invisible on the e-ink panel.

17. **Top-of-tab time summary** recomputes from the current tab's items on every load/tab
    switch: total ≈ words / (180·rate) minutes, and remaining subtracts each item's saved
    progress.

## Persistence

SQLite via framework `SQLiteOpenHelper` (`readaloud.db`, schema **v4**, table `saved_texts`:
`id/title/content/createdAt/archived/progress/iconPath`). Migrations are additive
(`ALTER TABLE ADD COLUMN`) so existing items survive upgrades. Room was avoided because the
only cached Room (2.8.5) requires
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

## Changelog (0.5.x refinements)

- **0.5.2** — baseline: Bluetooth/AVRCP playback (synth-to-file + in-app MediaPlayer),
  inbox/archive tabs, long-press multi-select, URL download, language-aware voices, rate/pitch.
- **0.5.3** — high-contrast current-sentence highlight (black background / white text);
  clearly visible seek-bar thumb/progress; **read the item title aloud** before the text.
- **0.5.4** — reading waits for the title to **finish** (no fixed delay, no overlap with the
  previous item); sharing handled **in the background** via an invisible `ShareActivity` (toast
  only, app does not open).
- **0.5.5** — article **favicon** fetched, cached and shown in the list; DB schema **v4**
  (`iconPath`).
- **0.5.6** — **total** estimated listening time shown at the top of each tab.
- **0.5.7** — the top-of-tab summary also shows **remaining** time (per-item progress aware).
- **0.5.8** — tapping a paragraph / seeking / forward-rewind now **aborts a still-playing title
  or announcement** and jumps immediately instead of waiting for it to finish.

## Known limitations / next steps

- Sentence splitting is regex-based (`(?<=[.!?])\s+`); may split on abbreviations.
- Downloaded items don't retain the source URL in the DB (title/content/icon only), so the icon
  cannot be re-fetched later without re-downloading.
- Items saved **before v0.5.5** have no favicon (the column was added later); only newly
  downloaded items show one.
- Spoken announcements are English-only; the reading itself uses the detected-language voice.
- The top-of-tab time summary uses a fixed 180 wpm baseline scaled by the speech-rate setting;
  the real duration depends on the chosen engine/voice.

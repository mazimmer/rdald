# NaraTTS (ReadAloud v2)

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
- Package / applicationId: `com.rdald.app` (app label: **NaraTTS**).

## License

NaraTTS is free software: you can redistribute it and/or modify it under the terms of the
**GNU General Public License v3.0** (SPDX: `GPL-3.0-only`). See [`LICENSE`](LICENSE).

Copyright (C) 2026 NaraTTS contributors.

The project shares ideas and architectural approaches with AntennaPod (also GPL-3.0-only).

## Features (current, v0.8.13)

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
- **Language detection** + **random fitting voice** per item (offline voices preferred), chosen
  only from the user's **selected voices**.
- **Voice selection** (`Settings → Voices`): every installed voice is listed as a **collapsible
  tree** — a top **“All languages”** toggle, then **language → country/variant → voices** (all
  folded initially for fast scrolling). Each level has a **tri-state** checkbox (checked = all,
  dash = some, empty = none). Everything is **selected by default**; newly installed voices are
  selected automatically (we persist only the *deselected* set). Tapping a voice **plays a short
  sample** in that voice (localized per language).
- **All engines, unified**: voices are engine-specific, so the app discovers every installed TTS
  engine (`<queries>` for `TTS_SERVICE`) and shows **all their voices together** in the picker
  (the engine label is shown per voice). On open it prunes selections for engines that are no
  longer installed; new engines' voices are selected by default. Selection keys are
  `enginePkg|voiceName`. Playback keeps **one TTS instance per engine** and picks a random selected
  voice across engines for each item, switching engines as needed.
- **Speech rate / pitch** settings applied per item.
- **Configurable time-announcement threshold** (Settings → “Time announcement threshold”, default
  **5 min**, 0 = always): the total/remaining-time announcement only plays when the item's
  remaining time exceeds this many minutes.
- **Tap the reader title bar** to open the article's **original URL** in the default browser
  (downloaded items now store `sourceUrl`).
- Background playback via a foreground `MediaLibraryService`.
- **Reader overflow menu → “Deselect current voice”**: drops the voice currently reading (adds
  its `enginePkg|voiceName` key to deselected) and randomly picks another fitting, still-selected
  voice across engines, then re-reads the current sentence with it.
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
- `SettingsActivity` — speech rate/pitch & forward/rewind intervals (`SeekBarPreference`); opens
  the Voices screen.
- `VoicesActivity` — unified, collapsible, tri-state voice tree aggregating **all installed TTS
  engines**; per-voice preview; prunes selections for missing engines.
- `TtsEngines` — discovers installed TTS engines (`TTS_SERVICE`) and resolves the default one.
- `service/TtsPlayer` (see below) keeps **one `TextToSpeech` per engine** and hops engines per item.
- `service/PlaybackService` — `MediaLibraryService`; builds a `MediaLibrarySession`; handles
  `ACTION_LOAD_TEXT` and forwarded media keys.
- `service/TtsPlayer` — custom media3 `Player`: detects language, picks a voice, synthesizes
  sentences to WAV files and plays them with a `MediaPlayer`. Position model: 1 sentence = 1 s.
- `service/DownloadService` — background fetch + readability extraction + favicon caching → DB.
- `receiver/AppMediaButtonReceiver` — extends media3 `MediaButtonReceiver` (AVRCP forwarding).
- `data/SavedTextDb` — `SQLiteOpenHelper` persistence (no Room/KSP — see notes).
- `SavedText` — `@Parcelize` data model (title, content, createdAt, id, archived, progress,
  iconPath, sourceUrl).

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

SQLite via framework `SQLiteOpenHelper` (`readaloud.db`, schema **v6**, table `saved_texts`:
`id/title/content/createdAt/archived/progress/iconPath/sourceUrl/sortOrder`). Migrations are additive
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
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Release builds (two targets):

```
# F-Droid: UNSIGNED release (F-Droid signs it itself). `release` is unsigned on purpose.
./gradlew assembleFdroid
# -> app/build/outputs/apk/release/app-release-unsigned.apk
#    (+ NaraTTS-<version>-fdroid-unsigned.apk)

# Your own installable build: signed with the debug key (updates in place, keeps data).
./gradlew assembleSelfsigned
# -> app/build/outputs/apk/selfsigned/app-selfsigned.apk
adb install -r app/build/outputs/apk/selfsigned/app-selfsigned.apk
adb shell pm grant com.rdald.app android.permission.POST_NOTIFICATIONS
```

The `release` type is **unsigned** because F-Droid builds `assembleRelease` and signs with its
own key. For local daily-driver installs use the **`selfsigned`** type (debug-key signed, same
signature as before, so it upgrades in place). See **`F-DROID.md`** for the full submission and
per-release process.

`local.properties` sets `sdk.dir`. Version is defined in `app/build.gradle.kts`
(`versionCode` / `versionName`).

## Changelog (0.5.x → 0.7.x)

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
- **0.6.0** — renamed the app to **NaraTTS** (pun on *narrates* + TTS) with a new adaptive
  launcher icon; the `applicationId`/package stays `com.rdald.app`, so existing libraries and
  settings are preserved.
- **0.6.1** — redrew the icon to be **abstract rather than chat-like**: a solid text block with
  three waves radiating to the right (“text read aloud”), paper background/black glyph for e-ink.
- **0.7.0** — **voice picker**: `Settings → Voices` lists all installed voices grouped by
  language → country/variant with checkboxes (all selected by default; storing the deselected
  set means new voices are auto-selected). Random voice selection now uses only selected voices.
- **0.7.1** — voice picker became a **collapsible tree** (folded by default) with a master
  “All languages” toggle and **tri-state** checkboxes at every level, plus a spoken **sample**
  when toggling a voice.
- **0.7.2** — added TTS-engine awareness (voices are per-engine).
- **0.8.13** — text cleanup strips long runs of symbols (`__________`, `-----`, `•••`, …), even when
  attached to words/numbers.
- **0.8.12** — the player broadcasts `ACTION_LIBRARY_CHANGED` when an item finishes; MainActivity
  reloads immediately and the finished card animates out (`DefaultItemAnimator`).
- **0.8.11** — mini player control icons tinted dark for visibility on the light card.
- **0.8.10** — **mini player bar** on the main screen (play/pause, forward, rewind, tap to open the
  item); the list marks the current item with a **speaker icon + shaded card**. Controller connects
  from MainActivity; hides during multi-select/after end.
- **0.8.9** — fix reader/player desync by sharing `TextSegmentation` (same sentence indices); speech
  normalization now happens **per sentence** (display text/indices unchanged).
- **0.8.8** — **speech normalization** before sentence splitting: expands common abbreviations
  (Mr./Dr./z.B./usw./…), converts dates (`10. August` → “10th of August” / “zehnter August”),
  removes the period from initials, fixing false sentence breaks and pronunciation.
- **0.8.7** — text cleanup keeps **numbers** (e.g. `100'000`); it still drops URLs, long symbol runs
  and garbled consonant blobs.
- **0.8.6** — fix overlapping audio on end-of-item/skip: announcements stop the sentence player,
  sentences stop the announcement player, queued auto-advance runnables are cancelled, and an item
  can only be finished once (`finishedItemId`).
- **0.8.5** — Settings gets a **back arrow**; downloaded text is cleaned of **URLs, citation refs and
  junk tokens** (pure symbols, long ids/hashes, keyboard-mash words) and mostly-symbol paragraphs.
- **0.8.4** — fixed a garbled default-value summary (bad format string); summary now shows only
  “Default: N”, while the slider displays the live value. Settings reordered: Voices, Bluetooth,
  Appearance, then the grouped sliders.
- **0.8.3** — Settings sliders use `updatesContinuously` (live value + summary), summaries show the
  **default value**, and a **Reset values** button restores all slider defaults.
- **0.8.2** — changing **speech rate/pitch** now drops and re-renders the current sentence with the
  new values (Settings notifies the service; debounced 500 ms so dragging doesn’t thrash).
- **0.8.1** — fix: Bluetooth “forward/rewind skips articles” now works — handled in
  `MediaSession.Callback.onMediaButtonEvent` (the earlier `onMediaKey` path was never invoked,
  since AVRCP events are dispatched by media3 directly to the session).
- **0.8.0** — **i18n**: follows the system language (English default) with German, French,
  Italian, Spanish, Russian, Chinese and Japanese; UI strings externalized and **spoken
  announcements localized**, using a voice matching the app language.
- **0.7.11** — **drag-to-reorder** the Inbox/Archive lists via a per-row handle; order is persisted
  (`sortOrder`, DB schema **v6**).
- **0.7.10** — optional **Bluetooth article skip**: forward = next article (no archive/finish),
  rewind = previous article (or restart current if none). In-app buttons still seek paragraphs.
- **0.7.9** — **per-sentence language detection**: each sentence's language is detected and a
  fitting voice chosen; unclear sentences keep the item's default language. The whole item is
  pre-scanned at load and upcoming sentences are pre-synthesized with their own voice.
- **0.7.8** — small **version label** beside the app name; article **favicon as notification
  artwork**; optional **compact cards** (title + favicon); lists open scrolled to the newest item.
- **0.7.7** — dragging the **progress slider fully right** finishes the item (100%); the
  end-of-item jump now **stops the current sentence audio** before continuing.
- **0.7.6** — forward on the **last paragraph** now ends the item (archives + continues); downloaded
  articles are screened and exact **duplicate paragraphs/sentences removed**.
- **0.7.5** — configurable **time-announcement threshold** (default 5 min); tapping the reader
  **title bar opens the original article URL** in the browser (DB schema **v5** adds `sourceUrl`).
- **0.7.4** — Reader overflow menu **“Deselect current voice”**: removes the active voice from the
  selection and re-picks another fitting selected voice (across engines), re-reading the current
  sentence. Sent to the service via a media3 custom session command.
- **0.7.3** — **unified all-engine voice view**: the picker aggregates voices from every installed
  engine; missing engines' selections are pruned on open, new engines default to selected, and
  playback holds one TTS instance per engine and randomly picks selected voices across engines
  (switching engines as needed). Selection keys are `enginePkg|voiceName`.

## Known limitations / next steps

- Sentence splitting is regex-based (`(?<=[.!?])\s+`); may split on abbreviations.
- Items saved **before v0.7.5** have no `sourceUrl`, so tapping their title bar shows
  “No source link for this item”; only newly downloaded items store the URL.
- Items saved **before v0.5.5** have no favicon (the column was added later); only newly
  downloaded items show one.
- Spoken announcements are English-only; the reading itself uses the detected-language voice.
- The top-of-tab time summary uses a fixed 180 wpm baseline scaled by the speech-rate setting;
  the real duration depends on the chosen engine/voice.

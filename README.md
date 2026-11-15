# NaraTTS

**Listen to articles and any text with your own Text‑to‑Speech voices.**

![License: GPL-3.0-only](https://img.shields.io/badge/license-GPL--3.0--only-blue)
![Platform: Android 12+](https://img.shields.io/badge/platform-Android%2012%2B-3ddc84)

NaraTTS ("*narrates*") turns links and text into a listening queue.
Share a link or paste some text and it extracts the article, detects the language, and reads it
using the TTS engines already installed on your device. Designed to stay readable and legible on **e‑ink** screens.

## Use case

Great for commuting, chores, accessibility, or catching up on long reads: build a queue of
articles, listen with one of your preferred voices, and pick up where you left off later. No
account, no cloud, no tracking — it uses your device's own speech engines.

## Features

**Reading & playback**
- Sentence‑by‑sentence reading with pause/resume, previous/next sentence, and paragraph‑level
  fast‑forward/rewind.
- Auto‑advance through your queue, with remembered **resume position** per article.

**Voices**
- Uses **any installed TTS engine**
- Randomly picks among your **selected** voices for the article's language, switching engines as
  needed.
- A reader menu item can **drop the current voice** and re‑read with another.

**Library**
- **Inbox / Archive** tabs with long‑press multi‑select; per‑item and per‑tab **listening‑time
  estimates** (total & remaining).
- Tapping the reader **title opens the original URL**.

**Reader view (e‑ink friendly)**
- High‑contrast **current‑sentence highlight**, auto‑scroll, tap‑a‑paragraph‑to‑jump.
- Adjustable **speech rate and pitch**.

**Intake**
- **Share** a link or text from any app (handled in the background, just a toast).
- Paste a URL or text in‑app; URLs are fetched in **reader mode** (Readability4J + Jsoup).

## Requirements

- Android **12 (API 31)** or newer.
- At least one Text‑to‑Speech engine installed (e.g. the system/Google engine, or any other TTS
  app). NaraTTS uses whichever engines are present.

## Install

- **F‑Droid:** submission prepared (see `F-DROID.md`); listing pending.
- **Build from source:**
  ```bash
  ./gradlew assembleSelfsigned
  # APK: app/build/outputs/apk/selfsigned/app-selfsigned.apk
  ```

## Documentation

- **[TECHNICAL.md](TECHNICAL.md)** — architecture, hard‑won implementation notes and changelog.
- **[F-DROID.md](F-DROID.md)** — how the F‑Droid build/submission works and the per‑release process.

## License

NaraTTS is free software under the **GNU General Public License v3.0** (`GPL-3.0-only`).
See [LICENSE](LICENSE). Copyright (C) 2026 NaraTTS contributors.

The project shares ideas and architectural approaches with
[AntennaPod](https://github.com/AntennaPod/AntennaPod) (also GPL-3.0-only).

# Getting NaraTTS into F-Droid

F-Droid builds your app **from source** on its own build server and **signs it with the F-Droid
key**. You never upload an APK and you never ship a signing key. Everything below is one-time
setup plus a short per-release checklist.

> Package / applicationId: **`com.rdald.app`** — never change it (it identifies the app and its
> updates; changing it would look like a new app and lose user data).

---

## 1. Prerequisites (one-time)

1. **A free/open-source license.** F-Droid only accepts FOSS. This project is **GPL-3.0-only**:
   the `LICENSE` file is included and the metadata uses `License: GPL-3.0-only`.
2. **A public source repository** that F-Droid can clone (GitHub, GitLab, Codeberg, …). The
   repository root is the Gradle project, so no `subdir` is needed.
3. **No proprietary bits**: no Google Play Services/Firebase, no committed keys, no prebuilt
   binaries. Current dependencies are all FOSS (AndroidX, Material, media3, Jsoup,
   Readability4J), so this is satisfied.
4. **Git hygiene**: `local.properties`, `build/`, `.gradle/` are already git‑ignored. Never
   commit signing keys.

---

## 2. F‑Droid-friendly build target

The `release` build type is **unsigned on purpose** — F‑Droid signs the APK itself. Use:

```bash
./gradlew assembleFdroid
# -> app/build/outputs/apk/release/app-release-unsigned.apk
#    plus a copy named NaraTTS-<version>-fdroid-unsigned.apk
```

`assembleFdroid` is a convenience target that runs `assembleRelease` (unsigned) and names the
result clearly, so you can verify locally before tagging.

For your **own installable** build (debug‑key signed, updates in place over a daily‑driver
install) use the separate type instead:

```bash
./gradlew assembleSelfsigned
# -> app/build/outputs/apk/selfsigned/app-selfsigned.apk
```

---

## 3. Per-release checklist

1. Bump `versionCode` (must strictly increase) and `versionName` in
   `app/build.gradle.kts`.
2. Add a Fastlane changelog for the new `versionCode`:
   `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`.
3. Verify the F‑Droid build locally: `./gradlew assembleFdroid`.
4. Commit, then create and push a tag:
   ```bash
   git add -A && git commit -m "Release X.Y.Z"
   git tag vX.Y.Z
   git push origin master --tags
   ```
   (F‑Droid's `UpdateCheckMode: Tags` + `AutoUpdateMode: Version` will pick up new tags
   automatically after the app is accepted.)

---

## 4. Submit the app (first time only)

1. Create a fork of the data repo **`gitlab.com/fdroid/fdroiddata`**.
2. Copy `fdroiddata/com.rdald.app.yml` from this repo to `metadata/com.rdald.app.yml` in your
   fork, and edit the placeholders:
   - `License:` (must match your `LICENSE`)
   - `AuthorName`, `AuthorEmail`, `WebSite`, `SourceCode`, `IssueTracker`
   - `Repo:` (your public git URL)
   - `commit:` (the release tag, e.g. `v0.7.5`)
3. (Recommended) Lint/build locally if you have `fdroidserver`:
   ```bash
   pipx install fdroidserver
   cd fdroiddata
   fdroid lint com.rdald.app
   fdroid build com.rdald.app
   ```
4. Commit and open a **Merge Request** against `fdroid/fdroiddata` (“New app: NaraTTS”).
   Respond to reviewer comments. After merge, F‑Droid’s buildserver builds and signs it, and it
   appears in the repo (the first inclusion can take a few days/weeks).

### Metadata highlights (already in the template)
- `RepoType: git`, `Repo: <your git URL>`
- `gradle: - yes` → builds the default **`assembleRelease`** (unsigned)
- `AutoUpdateMode: Version`, `UpdateCheckMode: Tags` → automatic updates from new tags

---

## 5. Notes

- **Signing:** F‑Droid signs with its own key. Anyone installing from F‑Droid gets F‑Droid’s
  signature; your debug‑key/self‑signed APKs are a separate distribution channel (they can’t be
  installed over F‑Droid’s build, and vice versa, without uninstalling).
- **Reproducibility / scanners:** F‑Droid may flag things during review (trackers, non‑free
  assets, network usage). This app has no trackers; it uses the network only when you add a URL.
- **Updating:** once accepted, you do **not** open a new MR for each version — pushing a new tag
  with a bumped `versionCode` is enough (AutoUpdate). Open an MR only to change metadata/build
  config.
- **Versioning:** keep `versionCode` increasing; F‑Droid orders updates by it.

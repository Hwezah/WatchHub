# CLAUDE.md

Guidance for working in this repository (a fork of Watchly).

## Scope of this fork

- **This fork only works on the Android phone app in `mobile/`.**
- `mobile/` is not self-contained. It builds against the shared Gradle module **`tv/core`**
  (`mobile/settings.gradle.kts` maps `:core` to `../tv/core`). Most of the app's logic is there:
  addons, Stremio sync, TorBox, the torrent engine, the players, metadata and storage.
  Treat `tv/core` as part of the mobile app.
- Ignore `tv/app/` and `windows/`. Don't change them, and don't fix TV- or Windows-only issues.
- If a change in `tv/core` would break the TV app's compile, say so. Don't silently fix `tv/app`.
- No feature code without an agreed plan. The owner is a Next.js/TypeScript developer who is new
  to native Android, so explain Android-specific concepts briefly when they come up.

## Direction: CloudStream plugins inside Watchly

Decision: keep this native Kotlin app and its UI, and add a **CloudStream plugin host** so the app can load
CloudStream extension repositories (repo.json → plugins.json → `.cs3` files, e.g. the `phisherrepo` shortcode).
Plugins scrape sites on the device, with no addon server in between. Stremio addons stay as a second source.
Both feed the same sources list.

How it works (CloudStream's own approach, `app/.../plugins/PluginManager.kt` upstream):
- A `.cs3` is a zip holding `classes.dex` and `manifest.json` (`pluginClassName`). It is loaded with
  `PathClassLoader(file, appClassLoader)`, so the plugin calls classes **in our app** by their exact names.
- Those classes come from CloudStream's `library` module (GPL-3.0, `com.lagradost.api`): `MainAPI`, `ExtractorApi`,
  `loadExtractor`, NiceHttp `app`... Pin it to one upstream commit.
- Plugins also reach into app-only classes, so a compat layer must provide them under the same package names.
  In phisher's 86 plugins: `CommonActivity` and `CloudStreamApp` (all 86), `plugins.Plugin` (33),
  `utils.DataStore` (14), `network.CloudflareKiller` (8), plus a few rarer ones.
- A plugin is arbitrary code running with our permissions. Only add repos you trust, and say so in the UI.
- The app ships with **no** repos or plugins. The viewer adds them, the same rule as Watchly's addons.

Integration into the existing UI:
1. Repos and plugins page under the Addons tab: add a repo by URL or shortcode, then install, update,
   enable and remove plugins.
2. Sources on a title page: each enabled provider runs `search(title)` → match by title and year → `load()`
   → the episode → `loadLinks()`. Each `ExtractorLink` (url, referer, headers, quality, M3U8/DASH/VIDEO) becomes
   a direct `Hit`. Each `SubtitleFile` becomes a `Sub`. Results show as they arrive, like addons.
3. Later: provider `mainPage` lists as Home shelves, provider search in Search, and title pages built from `LoadResponse`.

Gaps the player needs fixed for this:
- Add `media3-exoplayer-hls` and `-dash` (most links are m3u8).
- Persist stream headers in History (`Watch.kt` ponytail).
- Make libVLC send every header, not only User-Agent and Referer.

## Stack

| Layer | What |
|---|---|
| Language | Kotlin (JVM 17 target), Jetpack Compose UI (Material3 + custom "Ink" theme in `tv/core/Theme.kt`) |
| Build | Gradle 9.6 wrapper, Android Gradle Plugin 9.3, Kotlin 2.4, compileSdk 37 / targetSdk 36 / minSdk 24 |
| Native | NDK 27.1.12297006 + CMake 3.22.1 for `libholes` (`tv/core/src/main/cpp/holes.c`) |
| Playback | AndroidX Media3 (ExoPlayer) 1.9 + Jellyfin FFmpeg audio decoder; libVLC 3.6 as fallback / option |
| Torrents | libtorrent4j 2.1 (libtorrent), with a loopback HTTP server feeding the player |
| Images | Coil 3 |
| Icons | Lucide (`com.composables:icons-lucide-android`) |
| Cast | Google Cast framework (`play-services-cast-framework`) |
| Networking | `HttpURLConnection` wrapped in `tv/core/Net.kt`; JSON via `org.json` (no Retrofit, no kotlinx.serialization) |
| Storage | `SharedPreferences` through `Kv` (per-profile keys), Keystore-encrypted `Secrets`, files for caches |
| State | Kotlin `object` singletons exposing `StateFlow`; Compose `mutableStateOf` in `Watch`. No ViewModels, no DI |

## Architecture

```
mobile/app/src/main/java/com/torrentviewer/mobile/   (UI + phone-only services)
  MainActivity.kt   Services (engine + Watch singletons), PiP, incoming intents
  App.kt            Shell: bottom tabs, page stack (sealed `Page`), profiles, toasts
  Home / Pages / Detail / ForYou / WatchOrder / TmdbUi.kt   Screens
  Player.kt         Touch player UI (gestures, tracks, subtitles, Up Next, PiP)
  Downloads*.kt     Torrent download engine + foreground service + tab
  Cast.kt, MediaCard.kt, Updates.kt, Onboarding.kt, Ui.kt (touch primitives)

tv/core/src/main/java/com/torrentviewer/core/          (shared logic)
  Addons.kt         Stremio addon client: manifest parsing, list, order, enable/disable
  Search.kt         Stream fan-out to addons, stream → `Hit` parsing, ranking, subtitles (addons + SubDL)
  Meta.kt           Cinemeta + addon catalogs/meta, search, top charts
  Stremio.kt        Stremio account: link-code login, library/progress sync, addon collection sync
  TorBox.kt         TorBox debrid: cache check, add torrent, request direct link
  Watch.kt          One playback session: routes a stream (direct link / TorBox / torrent / external)
  TorrentStream.kt  libtorrent session, piece scheduling, rolling cache (see docs/)
  LocalFileServer.kt  Loopback HTTP range server the player reads torrents through
  ExoView.kt / VlcView.kt  The two player backends behind the `Video` abstraction
  Store.kt          Kv, Settings, Secrets, Profiles, History, SavedStore (My List), Intros
  Trackers.kt       Simkl / MDBList sync; Tmdb, Kids, WatchOrders, Avatars, Updates: the rest
```

Stream flow: Detail page → `searchStreams` / `searchEpisode` ask every enabled addon with a
`stream` resource in parallel → `Hit`s merged and ranked → `Watch.stream(hit)`:
- `url` (debrid or own server) → `playLink` → player opens the URL directly (headers from `proxyHeaders`).
- `infoHash`/magnet → TorBox link if a key is set and it's cached → otherwise `TorrentStream` + `LocalFileServer`.
- `ytId` / `externalUrl` → handed to another app.

The player starts with Media3. If Media3 reports the file unsupported, it falls back to libVLC at the same position
and remembers that file (`NeedsVlc`).

Read `docs/rolling-cache-architecture.md` before touching `TorrentStream.kt`, `LocalFileServer.kt` or `Holes.kt`.
Every constant there was tuned against real swarms.

## Build and run

Requirements: JDK 17 or 21, Android SDK (platform 37), NDK 27.1.12297006, CMake 3.22.1.
Android Studio installs all of these.

```bash
cd mobile
./gradlew compileDebugKotlin          # fast compile check (phone + tv/core)
./gradlew assembleDebug               # APKs in app/build/outputs/apk/debug/ (one per ABI)
./gradlew installDebug                # install "Watchly Next" (com.torrentviewer.app.next) on a USB-connected phone
./gradlew installRelease              # minified release (com.torrentviewer.app)

cd ../tv
./gradlew :core:testDebugUnitTest     # unit tests for shared logic (tv/core/src/test)
```

Optional keys go in `mobile/.env` (git-ignored). They're baked into `BuildConfig`:
`TMDB_KEY`, `SUBDL_KEY`, `SIMKL_CLIENT_ID`, `MDBLIST_CLIENT_ID`. Without them those features are hidden.

Logs: `adb logcat -s watch stremio torbox releases Subtitles`. The engine also writes
`Android/data/<app id>/files/engine.log`.

## Coding conventions (follow the existing code)

- **Put logic in `tv/core` as top-level pure functions** (`toStreamHits`, `parseLibrary`, `mirrored`...),
  and keep side effects in the `object`s. Pure functions get a JVM unit test in `tv/core/src/test`
  (JUnit 4, real `org.json`). Add or update a test with every logic change.
- **UI stays in `mobile/`.** Compose only, no XML layouts. Reuse the primitives in `Ui.kt` and the `Ink`/`Type`
  tokens from `tv/core/Theme.kt`. Don't hard-code colours or text styles.
- **State:** expose `StateFlow` from singletons and collect it with `collectAsState()`. Use `AppScope` for work
  that must outlive a screen, and catch failures with `runCatching` so a dead addon or a Stremio outage never
  breaks playback.
- **Networking:** always go through `Net`. Pass `quiet = true` for addon and debrid calls.
- **Secrets:** addon URLs can contain debrid keys. Never log, display or store a full addon URL outside
  `Addons` (show `addon.host`). Put API keys in `Secrets`, never in `Kv` and never in git.
- **Per-profile data** goes through `Kv.get/set`. App-wide data goes through `Kv.global/setGlobal`.
- **KDoc on every declaration** saying *why*, in plain sentences, like the existing code. Mark a known shortcut
  with a `ponytail:` comment saying what it skips and when to revisit it.
- **Kotlin style:** 4-space indent, long lines are fine (the codebase uses about 160 to 180 characters),
  expression bodies where they read well, and no `!!` except where the code just proved the value isn't null.
- **Protocol fidelity:** follow the Stremio addon protocol
  (https://github.com/Stremio/stremio-addon-sdk/blob/master/docs/protocol.md). Accept every shape it allows.
- **Before finishing a change:** run `./gradlew compileDebugKotlin` in `mobile/`. If `tv/core` changed, also run
  `./gradlew :core:testDebugUnitTest` in `tv/`.
- **Commits:** imperative and short, prefixed with the area, for example `Phone: …` or `Core: …`.

## Fork housekeeping (known, not yet done)

- `tv/core/Updates.kt` and `Release.kt` still point at upstream `sammyZi/Watchly` releases.
- Release builds are signed with the committed `mobile/app/debug.keystore`. Use a private keystore before
  distributing APKs.
- `applicationId` is still upstream's `com.torrentviewer.app`.

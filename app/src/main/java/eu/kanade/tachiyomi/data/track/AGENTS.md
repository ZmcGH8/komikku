# data/track/ — Tracker Services

Built-in tracking services (14+: MyAnimeList, AniList, Kitsu, MangaUpdates, Shikimori, Bangumi,
Anime-Planet, ComicK, MangaBaka, Hikka, Kavita, Komga, Suwayomi, MDList). Adding/removing a tracker
is a 3-step task with zero UI edits — the authoritative procedure lives in `core/README.md`
(implementation: `template/TrackerTemplate.kt`). This file is an index, not a duplicate.

## Structure

| Path | Purpose |
|------|---------|
| `core/` | Framework: `TrackerId` (ID space + `all` set), `TrackerRegistry` (duplicate-ID validation in debug), `TrackerDefinition`/`TrackerProvider`/`TrackerCapabilities`, `TrackerAuthType`/`TrackerLoginMode`, `AbstractCookieTrackerInterceptor`, `TrackerException` |
| `template/TrackerTemplate.kt` | Copy-paste starter for a new tracker |
| `Tracker.kt`, `BaseTracker.kt` | Service contract + base (`getLoginMode()` defaults to `CREDENTIALS`) |
| `TrackerManager.kt` | Registry: one field per tracker + `trackers: List<Tracker>`; companion constants delegate to `TrackerId` |
| `EnhancedTracker.kt`, `DeletableTracker.kt` | Enhanced (Kavita/Komga/Suwayomi, auto-discovered, no credentials UI) vs standard services |
| `TrackerSyncCoordinator.kt`, `TrackStatus.kt` | Cross-service sync coordination + status model |
| `model/` | `TrackSearch`, `TrackMangaMetadata` |
| `interceptor/` | Shared cookie-interceptor subclasses (e.g. 12-line AnimePlanet interceptor) |
| `<service>/` | Per-service package: `<Name>.kt` + `<Name>Api.kt` + `*Interceptor` + `dto/` (see `anilist/` as reference) |

## Auth modes (`TrackerLoginMode`)

`OAUTH` (browser URL via `getAuthUrl()`; token in `trackPreferences.trackToken()`),
`CREDENTIALS` (login dialog, `BaseTracker.saveCredentials`), `WEBVIEW_COOKIE`
(`createCookieLoginIntent()` + `AbstractCookieTrackerInterceptor`: locked, expiry-filtered, 8KB cap),
`ENHANCED_NOOP` (`loginNoop()` only). UI (`SettingsTrackingScreen`) auto-wires from `getLoginMode()`.

## Login activities

OAuth/cookie logins land in dedicated activities (`TrackLoginActivity`, `AnimePlanetLoginActivity`,
`GoogleDriveLoginActivity`) — registered in the manifest, injected via `AppGraph`.

## Conventions

- New tracker = reserve ID in `TrackerId.kt` → class extending `BaseTracker` → register in
  `TrackerManager.kt`. Full steps: `core/README.md`.
- Removing a tracker with `manga_sync` rows needs a `Migration` to clean them.
- Komikku-added trackers → `KMR` strings + `i18n-kmk/`; `// KMK -->` markers.
- `TrackerCapabilities` (`supportsReadingDates`, `supportsScore`, `supportsRereadCount`, …) are
  registry-level, not user-togglable.

## Anti-patterns

- Do not hard-code a tracker list in settings UI — iterate `trackerManager.trackers`.
- Do not reuse a retired `TrackerId` value (keep reserved to avoid `manga_sync` collisions).
- Do not store tokens outside `trackPreferences.trackToken()` / `BaseTracker.saveCredentials`.

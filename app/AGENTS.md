# app/ Module

The monolithic application module containing UI, domain, data services, and DI wiring. Organized by fork origin (4 package roots), not architectural layer.

## Package roots

| Root | Origin | Content |
|---|---|---|
| `eu.kanade.tachiyomi.*` | Original Tachiyomi | Activities, screens, widgets, DI, data services |
| `eu.kanade.domain.*` | Tachiyomi refactor | App-level domain interactors |
| `eu.kanade.presentation.*` | Compose migration | Compose screens, components, theme |
| `exh.*` | TachiyomiSY/ExHentai | E-Hentai, MangaDex, recommendations |
| `mihon.*` | Mihon upstream | Migration, upcoming, Shizuku |

## Key directories

| Path | Purpose |
|------|---------|
| `eu.kanade.tachiyomi.ui.*` | Voyager Screens + ScreenModels |
| `eu.kanade.presentation.*` | Compose UI (screens, components, theme) |
| `eu.kanade.tachiyomi.di/` | AppModule, PreferenceModule |
| `eu.kanade.domain/` | DomainModule, KMKDomainModule, SYDomainModule |
| `eu.kanade.tachiyomi.data.*` | Infrastructure: backup, download, sync, track, coil |
| `exh/` | E-Hentai/ExHentai features (see exh/AGENTS.md) |
| `mihon/feature/` | Self-contained feature modules (migration, upcoming) |

## Dependency injection

Primary: **Metro** (`dev.zacsweers.metro`). `AppGraph` (`mihon/app/di/AppGraph.kt`) exposes typed accessors; resolve via `context.appGraph.x`, `globalAppGraph.x` (non-Context classes), or constructor `@Inject`. ViewModels: `@AssistedInject` + nested `@AssistedFactory` (`@ContributesIntoMap(AppScope)`), resolved with `viewModels<T> { graph.viewModelFactory }`. New bindings go through `@Inject` constructors / `AppBindings` — **not** Injekt.

Legacy Injekt modules (still loaded in `App.onCreate`, kept only for remaining keep-sites + the extension bridge — do not extend):
1. `PreferenceModule` – preference stores
2. `AppModule` – infrastructure singletons (DB, network, downloads)
3. `DomainModule` – core domain interactors + repos
4. `KMKDomainModule` – Komikku-only (library update errors)
5. `SYPreferenceModule` – SY/ExH preferences
6. `SYDomainModule` – SY domain (metadata, merge, feed)

## Screen pattern

Voyager `Screen` + `ScreenModel` in `ui/`, Composable in `presentation/`:
- `LibraryTab.kt` → `LibraryScreenModel.kt` → `presentation/library/`
- `MangaScreen.kt` → `MangaScreenModel.kt` → `presentation/manga/`
- `ReaderActivity` uses `ReaderViewModel` (AndroidX ViewModel, not Voyager)

## Settings framework

Modular preferences with automatic UI wiring — full guide:
`app/src/main/java/eu/kanade/presentation/more/settings/framework/AGENTS.md`.

- Store base: `SettingKey`/`SettingsRegistry` (`core:common`); UI: `SettingDefinition`,
  `SettingRow`/`toItems`, `GenericSettingsScreen`; screen registry: `SettingsCatalog`.
- New setting = key in `*SettingKeys` + definition in `*SettingsHost` + host list entry.
- New screen = register in `SettingsCatalog.searchableScreens` AND `SettingsMainScreen.items`.
- Pilot: webhooks (`WebhookSettingKeys`, `WebhookSettingsHost`).

## Backup & restore

Code in `eu.kanade.tachiyomi.data.backup.{create,restore}`:
- A restore **streams the file**: `BackupDecoder.decodeMetadata` walks the entries by length to
  count them and hands back everything else, and `decodeEntries(uri, include)` yields one
  `BackupManga` at a time, skipping rejected ones by length (`BackupReader.kt` walks the protobuf
  by hand; the wire format is unchanged, so old and new backups are both readable). Never call
  `decode(uri)` on a restore path — it materialises the whole library, which is the thing the
  streaming pass exists to avoid. `decode(uri)` is for the integrity check, which needs every
  entry's trackers.
- Entry order is a **classification**, not a sort: `restorePhaseOf` returns NEW / EXISTING / MERGED
  and `BackupRestorer` walks the file twice to realise it. Sorting would mean holding every entry
  to order it.
- `MangaRestorer.restore` takes a **list** and opens **one transaction for the chunk**, falling
  back to entry-by-entry in `BackupRestorer` if the batch throws. One transaction per entry was
  ~15 000 commits on a large library; see `restore`'s own comment.
- `PreferenceBackupCreator` snapshots `preferenceStore.getAll()` → typed `BackupPreference`
  list; always drops `__APP_STATE_`, drops `__PRIVATE_` unless user opts in.
- `PreferenceRestorer` type-switches values back; remaps category IDs; special-cases
  `LibraryPreferences`/`DownloadPreferences` category key sets + `SourcePreferences.PINNED_SOURCES_PREF_KEY`.
- Key renames require a `mihon.core.migration.Migration` (`MigrateUtils.replacePreferences`);
  backup files match on raw key strings, so framework `SettingKey` values must stay stable.
- Post-restore bookkeeping: `LibraryUpdateJob.startNow` is gated on `RestoreOptions.updateLibrary`
  (default **on**; UI row is `RestoreOptions.updateLibraryEntry`, rendered in its own section
  because it is a follow-up, not backup content — it never counts towards `canRestore()`).
  `restore()` folds the WAL in a `finally` (`DatabaseHandler.checkpoint()` — outside any
  transaction), `fromBooleanArray` reads WorkManager input with `getOrNull` fallbacks so stale
  input from an older build resumes instead of crashing, and the restore screen validates on
  `screenModelScope.launchIO` because the validation decodes the whole file.
- `restoreManga` flushes the partial `pendingEntries` chunk **after the second pass, before the
  merged entries**: `restoreEntries` clears the buffer in its `finally`, so a merged chunk
  written with a partial buffer still pending used to silently drop the tail of the library
  (everything when `entries.size % 250 != 0`) and stop the progress bar short of 100%.

## Theme system

19 named color schemes extending `BaseColorScheme`. KMK adds cover-based dynamic theming via `DynamicMaterialExpressiveTheme`.

## Fork markers

All Komikku additions wrapped in `// KMK -->` ... `// KMK <--`. SY additions in `// SY -->` ... `// SY <--`.

## Conventions

- Logging: `xLogE()` / `xLog()` from `exh.log` for KMK code; `logcat {}` for Mihon code
- Preferences: `eu.kanade.domain.*.service.*Preferences`, keys centralized in co-located
  `*SettingKeys` (see `settings/framework/AGENTS.md`); new UI rows via `*SettingsHost` definitions
- Strings: `KMR` + `i18n-kmk/` for Komikku-only features

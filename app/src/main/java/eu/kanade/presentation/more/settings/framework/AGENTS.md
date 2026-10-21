# settings/framework/ — Modular Settings Framework

Automatic UI wiring for app settings. Package: `eu.kanade.presentation.more.settings.framework`.
Store-level base (`SettingKey`, `SettingHost`, `SettingsRegistry`) lives in `core:common`
(`tachiyomi.core.common.preference`); see `core/common/AGENTS.md`.

## Layers

| Piece | Location | Role |
|---|---|---|
| `SettingKey<T>` | `core:common` | Single source of truth: exact storage key + default |
| `*SettingKeys` objects | Next to each `*Preferences` class | All keys one feature owns (e.g. `WebhookSettingKeys`) |
| `SettingDefinition<T>` | `framework/` | Key + title + store binding + row builder |
| `*SettingsHost` | Next to each settings screen | The feature's definition list; self-registers for duplicate detection |
| `SettingRow` / `List.toItems` | `framework/` | Definitions → `PreferenceItem` via the existing dispatcher |
| `GenericSettingsScreen` | `framework/` | Base class for definition-driven screens (still `SearchableSettings`) |
| `SettingsCatalog` | `../screen/` | Single list of searchable screens (hub + search index source) |

The existing `PreferenceItem` composable (`PreferenceItem.kt`) stays the sole renderer —
framework rows look and behave exactly like hand-built ones. Non-standard rows (navigation,
async data, login widgets) stay hand-built as escape hatches.

## Adding a new setting (3 steps)

1. Add the key to the feature's `*SettingKeys` object (resolved key — apply
   `Preference.privateKey()` / `appStateKey()` here, not at call sites). Never rename a key
   without a migration in `mihon/core/migration/migrations/`.
2. Add one definition to the feature's host (`switchSetting`, `editTextSetting`,
   `intSliderSetting`, `multiSelectSetting`, `listSetting`).
3. Render it: include in the host list consumed by `toItems(store)` / `SettingRow`.

## Adding a new settings screen

1. Create the screen extending `GenericSettingsScreen(titleRes)` (or `SearchableSettings`
   directly if mostly custom rows), building groups from host definitions.
2. Register it in `SettingsCatalog.searchableScreens` (search index) AND add an `Item`
   to `SettingsMainScreen.items` (hub icon/subtitle). Both lists — search breaks silently otherwise.

## Parity rules (regressions live here)

- `editText` / `list` / `multiSelect` helpers default subtitle to `"%s"` (value preview),
  matching the hand-built data-class defaults. Do not "clean up" the `"%s"` fallback.
- `toItems` uses a plain `for` loop: `@Composable` builders cannot be called inside `map{}`.
- Keep item order identical to the screen being migrated; search matches on title text.
- `*Preferences.foo()` accessors stay as thin delegates over the same keys — screens,
  backup (`getAll()` keys), and DI (`globalAppGraph.*`) are unaffected.

## Pilot reference

Webhooks (`WebhookSettingKeys`, `WebhookSettingsHost`, `SettingsWebhookScreen`): 17 rows
migrated, test button / info / async category multi-select kept custom.

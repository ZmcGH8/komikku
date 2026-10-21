<div align="center">

<br/>
<img src="./.github/readme-images/houri-icon.svg" width="200" height="200" alt="Houri icon"/>
<br/>
 <h1 align="center">Houri</h1>

*A new approach to Komikku.*

[![Discord](https://img.shields.io/discord/1540509997719355545.svg?label=&labelColor=6A7EC2&color=7389D8&logo=discord&logoColor=FFFFFF)](https://discord.gg/JcQZYvCbYH)
[![CI](https://img.shields.io/github/actions/workflow/status/PineappleTwilight/komikku-pineapple/build_push.yml?labelColor=27303D&label=CI)](https://github.com/PineappleTwilight/komikku-pineapple/actions/workflows/build_push.yml)
[![License: Apache-2.0](https://img.shields.io/github/license/PineappleTwilight/komikku-pineapple?labelColor=27303D&color=0877d2)](/LICENSE)

## Download

[![Stable](https://img.shields.io/github/release/PineappleTwilight/komikku-pineapple.svg?maxAge=3600&label=Stable&labelColor=06599d&color=043b69)](https://github.com/PineappleTwilight/komikku-pineapple/releases/latest)

*Requires Android 8.0 or higher.*

<div align="left">
A free, open source, and bleeding edge manga & novel reader, forked from Komikku (itself based on TachiyomiSY & Mihon/Tachiyomi).

<div align="left">

## Features

### Houri's unique features:
- Pineapple-themed UI and branding (adaptive icon with monochrome variant).
- **A full EPUB & novel reader** — open `.epub` files as *books*, not image strips: the publisher's own typography survives (drop caps, ruby annotations, vertical writing, tables), illustrations, stylesheets and embedded fonts are carried inside the page itself so they always render, and DRM-locked books are detected and refused instead of shown as garbage. Handles both EPUB 2 (`toc.ncx`) and EPUB 3 (`nav.xhtml`) contents, declares the charset from the file itself (Shift_JIS/EUC-JP books are not mojibake), and resolves percent-encoded and non-ASCII file names.
- **Books live in the manga library** — an `.epub` dropped into any library folder opens as a novel. Entries are auto-detected as BOOK or COMIC from the file's own content, art-book imports keep the comic reader, and a per-entry "Render as book / Render as comic" choice survives library updates; extensions can declare their entry types through a new optional API. A book's real title, author(s), description and declared cover land in the library.
- **Continuous book reading** — a book scrolls like the continuous manga viewer: the next section flows in before you reach the end, the previous one flows back in above you when you scroll up, and pushing past the very end of a book moves to the next volume.
- **Adjustable reading appearance, with your own fonts** — font size and scale, family (including fonts you import yourself — any `.ttf`/`.otf`/`.woff`/`.woff2` from your device), text and page colours where the text colour is auto-derived for contrast, line/paragraph/letter spacing, margins, and justification, hyphenation and vertical writing as tri-states that default to *follow the book*. Changes apply to the open chapter instantly, and the general reader theme (including black backgrounds with auto-inverted text) works on books too.
- **Book navigation** — the chapter selector renders the book's own table of contents (indented like a printed ToC, opened scrolled to where you are), the prev/next buttons step the book's own sections — volumes only at the ends — and the bottom-bar slider shows your progress through the current chapter as a percentage, draggable to seek.
- **In-chapter search** — search the chapter you're reading as you type, case-insensitive, with selectable context snippets and a jump straight to the match.
- **Positions that survive anything** — where you stopped is remembered per chapter, measured in characters rather than pages, so a book reopens on the same paragraph at a different font size, on a tablet, or in landscape.
- **Long novels open instantly** — the book's spine is read first and each section's text is loaded only when you reach it, so a five-hundred-file novel opens as fast as a short one.
- **Book-aware settings** — while reading a book, the settings sheet drops every option that cannot act on text and gains a **Reading appearance** tab instead.
- **Text-source extension API** — extensions implementing the new `TextSource` interfaces get this reader automatically (incremental loading, contents, in-chapter search, resource streaming), with optional interfaces for per-document base URLs, entry types and mixed-content sources.
- **WebGPU reader** — a hardware-accelerated (Vulkan) page renderer as the modern viewer: HDR pages (PQ/HLG/UltraHDR) at full brightness with clean SDR tone-mapping, memory that sizes itself to your device *and* to what is actually free right now, GPU device-loss recovery, page transition animations (flip, stack, sphere, cube, fade), LUT colour presets plus custom LUT files, brightness/contrast/HLG-exposure filters, smart dark mode that darkens pages on dark backgrounds, an e-ink preset, built-in ArtCNN upscaling, dual-page spreads with deterministic height matching, border crop in the strip reader, and configurable preload ahead/behind.
- **Library subcategories** — hierarchical categories with an optional Android home-screen-style folder layout, a virtual "All" view, subcategory reordering, per-subcategory library refresh, orphan cleanup, and full backup/restore support.
- **Smart categories** — bind a category to a tracker status and entries move between categories on their own: finish chapter 1 on a remote tracker and the entry leaves *Wish to read* and lands in *Reading* automatically. Unbound categories are never touched.
- **Per-category reader settings & update schedules** — categories and subcategories can override reader settings (inherits parent by default, first-matching-category priority for multi-category manga), pin a reading mode for a whole shelf, and set their own library update interval (daily → monthly, shortest interval wins across categories).
- **Smart Scanlator filter & chapter deduplication** — deduplicate chapters by scanlator with advanced controls: chapter count ranges, per-scanlator enable/disable, coverage view, and a quick toggle from the manga details menu. On by default, preferring the group with the most complete coverage, with drag-to-reorder scanlator priority — and groups that split the work both keep their chapters.
- **Find duplicates** — a library screen listing every entry that has more than one copy, with the existing duplicate review, side-by-side open and jump-to-migration.
- **Enhanced E-Hentai/ExHentai** — automatic censorship status detection (censored / decensored / uncensored) and mosaic vs. full marking, shown as dedicated tags with matching sort/filter options and colors.
- **Extra trackers** — MangaBaka (with full progress sync including the series' total chapter count, so finishing the last chapter completes it), Anime-Planet, ComicK (rebuilt on the official API), and Hikka in addition to the built-in trackers, with cookie-based auth storage and raw cover support.
- **Sequel & prequel relations** — relations reported by your trackers (AniList, MangaBaka…) shown as cards in manga details, with a MangaDex fallback; web novels route to the browser instead of the reader.
- **Bookmark colours** — colour a bookmarked chapter to record why you kept it, and filter a chapter list by any combination of colours.
- **Dynamic colour theming** — cover-based dynamic themes, and Material You wallpaper palettes that work below Android 12 too.
- **Webhooks** — send app events (chapter started/finished, manga finished, library update finished, backup created) to Discord webhooks or generic JSON endpoints, including series covers, reading time, and a test button.
- **Improved Discord RPC** — richer reading presence that respects subcategory filtering.
- **Mihon backports** — the latest reader, experimental decoder, and in-app updater (with a download progress bar).
- **Chapter completion moan** — optional, randomized (gacha-style 60/30/10) audio when finishing a chapter, with support for user-provided sound packs and configurable 50/50 placement.
- **AI-powered translation & grammar fixing** — opt-in hybrid pipeline (on-device NCNN detection, ONNX OCR — or the compact PaddleOCR v5 — AOT-GAN inpainting, Canvas typesetting) with cloud LLM translation (Gemini/OpenRouter/custom), a fully on-device LLM option (bundled llama.cpp with a Vulkan backend, or Gemini Nano offline fallback), and an English grammar/vocab fixer; per-page WEBP cache, editable titles/descriptions translation, and breadcrumb notes for consistency.
- **On-device AI upscaling** — optional Real-CUGAN / Real-ESRGAN / Waifu2x / ArtCNN via NCNN/ONNX Runtime with Vulkan/NPU auto-detect, per-series toggle, presets and 200 MB LRU cache (MTL-gated).
- **Manga rereading support** — track rereads with full tracker wiring and per-manga reread counts.
- **Library improvements** — article-ignoring sort (Yokai-style, "A Sign of Affection" sorts under S), a staggered library grid, custom cover image cropping with nudge/zoom controls, source/language badges, sortable categories & subcategories (name, date added, date modified — driving the tab order too), and global-search filtering by source category.
- **Censor lewd mangas** — blur thumbnails and redact names, tags, and descriptions (works in the library, catalogue, and manga details).
- **Source-level manga filtering** — set default filters per source, including per-sort defaults.
- **Heavily improved Feed** — a much more capable feed, opt-in by default.
- **Hardened database** — startup integrity checks, automatic restore from rolling backups, corrupt-file quarantine, SQLCipher encryption support, fast batched backup restore on large libraries, and one-tap full-database export from Settings → Data.
- **Self-updating extensions** — new versions of your extensions install in the background on a schedule you pick (6-hourly to weekly), with the Private or Shizuku installers.
- **Private extension installer** — the only installer that needs no install permission at all, verified against the repository signing key before it is stored, and fast even on a large collection.
- **Extension diagnostics** — failed extensions say why (icon, version, repository, failing source), with one-tap reporting and switchable error popups.
- **FlareSolverr support** — point Houri at your own FlareSolverr instance for Cloudflare challenges, with a test action and automatic fallback to the built-in solver.
- **Reader fixes for e-ink / ereaders** — deterministic double-page height matching, corrected scaling and RTL/LTR progress bar inversion, page offset for WebGPU, and other rendering fixes.
- **Achievements** — collectible rank system (Novice → Ultimate) with 50+ achievements, negative/shame, backlog, reading-time, daily/weekly rotating pools (with real progress bars and a library tab that puts the active ones first), per-achievement progress and animated tier gradients — and nothing unlocks until you've finished onboarding and decided to have them.
- **WebAssembly engine for extensions** — J2V8 + WASM runtime for site WASM bundles (keygen/auth via `WebAssembly.Memory/Table`), LRU module cache and standalone fallback, used for MangaBaka auth and other site WASM.
- All features from Komikku, Mihon, and TachiyomiSY.
- And much more not listed!


<details>
  <summary>Features from Mihon / Tachiyomi</summary>

#### All up-to-date features from Mihon / Tachiyomi (original), include:

* Online reading from a variety of sources
* Local reading of downloaded content
* A configurable reader with multiple viewers, reading directions and other settings.
* Tracker support: [MyAnimeList](https://myanimelist.net/), [AniList](https://anilist.co/), [Kitsu](https://kitsu.app/), [MangaUpdates](https://mangaupdates.com), [Shikimori](https://shikimori.one), [Bangumi](https://bgm.tv/)
* Categories to organize your library
* Light and dark themes
* Schedule updating your library for new chapters
* Create backups locally to read offline or to your desired cloud service
* Continue reading button in library

</details>

<details>
  <summary>Features from Tachiyomi SY</summary>

#### All features from TachiyomiSY:
* Feed tab, where you can easily view the latest entries or saved search from multiple sources at same time.
* Automatic webtoon detection, allowing the reader to switch to webtoon mode automatically when viewing one
* Manga recommendations, uses MAL and Anilist, as well as Neko Similar Manga for Mangadex manga (Thanks to Az, She11Shocked, Carlos, and Goldbattle)
* Lewd filter, hide the lewd manga in your library when you want to
* Tracking filter, filter your tracked manga so you can see them or see non-tracked manga, made by She11Shocked
* Search tracking status in library, made by She11Shocked
* Custom categories for sources, liked the pinned sources, but you can make your own versions and put any sources in them
* Manga info edit
* Manga Cover view + share and save
* Dynamic Categories, view the library in multiple ways
* Smart background for reading modes like LTR or Vertical, changes the background based on the page color
* Force disable webtoon zoom
* Hentai features enable/disable, in advanced settings
* Quick clean titles
* Source migration, migrate all your manga from one source to another
* Saving searches
* Autoscroll
* Page preload customization
* Customize image cache size
* Batch import of custom sources and featured extensions
* Advanced source settings page, searching, enable/disable all
* Click tag for local search, long click tag for global search
* Merge multiple of the same manga from different sources
* Drag and drop library sorting
* Library search engine, includes exclude, quotes as absolute, and a bunch of other ways to search
* New E-Hentai/ExHentai features, such as language settings and watched list settings
* Enhanced views for internal and integrated sources
* Enhanced usability for internal and delegated sources

Custom sources:
* E-Hentai/ExHentai

Additional features for some extensions, features include custom description, opening in app, batch add to library, and a bunch of other things based on the source:
* 8Muses (EroMuse)
* Mangadex
* NHentai
* Puruin
* LANraragi

</details>

## Issues, Feature Requests and Contributing

Pull requests are welcome. For major changes, please open an issue first to discuss what you would like to change.

<details><summary>Issues</summary>

1. **Before reporting a new issue, take a look at the [changelog](https://github.com/PineappleTwilight/komikku-pineapple/releases) and the already opened [issues](https://github.com/PineappleTwilight/komikku-pineapple/issues).**
2. If you are unsure, ask here: [![Discord](https://img.shields.io/discord/1540509997719355545.svg?label=&labelColor=6A7EC2&color=7389D8&logo=discord&logoColor=FFFFFF)](https://discord.gg/JcQZYvCbYH)

</details>

<details><summary>Bugs</summary>

* Include version (More → About → Version)
 * If not latest, try updating, it may have already been solved
 * Preview version is equal to the number of commits as seen on the main page
* Include steps to reproduce (if not obvious from description)
* Include screenshot (if needed)
* If it could be device-dependent, try reproducing on another device (if possible)
* Don't group unrelated requests into one issue

Use the [issue forms](https://github.com/PineappleTwilight/komikku-pineapple/issues/new/choose) to submit a bug.

</details>

<details><summary>Feature Requests</summary>

* Write a detailed issue, explaining what it should do or how.
* Include screenshot (if needed).
</details>

<details><summary>Contributing</summary>

See [CONTRIBUTING.md](./CONTRIBUTING.md).
</details>

<details><summary>Code of Conduct</summary>

See [CODE_OF_CONDUCT.md](./CODE_OF_CONDUCT.md).
</details>

<div align="center">

### Credits

Thank you to all the people who have contributed!

<a href="https://github.com/PineappleTwilight/komikku-pineapple/graphs/contributors">
    <img src="https://contrib.rocks/image?repo=PineappleTwilight/komikku-pineapple" alt="Houri contributors" title="Houri contributors" width="800"/>
</a>

### Disclaimer

The developer(s) of this application does not have any affiliation with the content providers available, and this application hosts zero content.

<div align="left">

## License

    Copyright 2015 Javier Tomás

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.

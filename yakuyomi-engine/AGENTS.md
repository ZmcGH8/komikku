# yakuyomi-engine/ Module

On-device + cloud manga translation ("AI Translation" / MTL). Package root: `exh.yakuyomi.*`.
Komikku-side orchestration; the native vision pipeline (`li.joye.yakuyomi.engine.*`:
Detector/Ocr/Inpainter/Grouping/Renderer/Pipeline) lives in `external/yakuyomi-engine`,
a git **submodule** (PineappleTwilight/houri-engine, branch `main`) — commit the submodule
pointer BEFORE the main repo. ABIs: arm64 + armeabi-v7a (x86 degrades to "not ready").

## Pipeline (per page)

detect (DBNet NCNN 1024) → OCR (ONNX 48px CTC) → group (union-find + MST) →
translate (LLM) → inpaint (AOT-GAN NCNN, overlaps translate) → typeset (Canvas).
Removal covers **all** detected regions; translation only the OCR-readable ones.
Never overwrite a good translation with a worse one (§11 rule).

## Architecture

Three decisions are made exactly once. Do not re-derive them at a call site.

| Decision | Owner |
|---|---|
| Which backend translates (image service → Gemini Nano → local GGUF → cloud) | `TranslationProviderResolver` |
| Prompt / parse / align / offline-fallback protocol | `TextTranslationProtocol` + `TranslationRequest` |
| Which pages are translatable, and at what sample size | `PageImageValidator` |

```
TranslationManager          facade: gates, status, errors. No provider, cache or queue logic.
├── TranslationProviderResolver   one answer for "who is translating", incl. cache identity
├── TextTranslationProtocol       one implementation of the line protocol
│   └── TranslationRequest        per-request inputs (langs, breadcrumb, glossary, policy)
├── PageQueue                     per-chapter page-ordered work, pending cap, lifecycle
├── PageResultCache               saved-page store + content-addressed cache, in one place
├── PageImageValidator            size/dimension limits, sample size
├── PageImageEncoder              bitmap → JPEG (vision) / WEBP (output/cache)
└── YakuyomiEngine                native session lifetime + dispatch only
    ├── EngineConfigFactory       prefs → EngineConfig; typeface, text colour, orientation.
    │                             Owns the list of knobs that require a session rebuild.
    ├── NativeComponents          the three native sessions; built all-or-nothing
    └── LongPageStitcher          slice → per-slice pipeline → stitch, stats, regions
```

Backends are thin: each supplies only a `generate` (or parsed-lines) function and inherits the
protocol. Adding a provider means one `TranslationProviderKind` value plus one branch in
`TranslationProviderResolver` — the `when` over that enum is exhaustive, so a missed site is a
compile error rather than a stale cache key.

## Extending

**A new text provider**
1. Implement the stage as a `Translator` (see `LocalLlmTranslator` — ~30 lines) that hands its raw
   output to `TextTranslationProtocol.run` / `fromParsedLines`. Do **not** re-implement prompt
   building, `isEnglishFix`, parsing or line alignment.
2. Add a `TranslationProviderKind` value and one branch in `TranslationProviderResolver.resolve()`.
3. Switch on that enum where the stage is constructed. The `when` is exhaustive, so the compiler
   points at every site that needs the new case.
4. If the provider needs a cache identity, it comes from the resolver — never re-derive it.

**A new engine tuning knob**
1. Map it in `EngineConfigFactory.defaultConfig()`.
2. Add it to `EngineConfigFactory.configChanges()` **in the same edit**, or the native session will
   not rebuild and the setting will silently do nothing until the app restarts. Render settings are
   the exception: they are read per page and need no entry.

**A new page-level rule** (limit, sample size, encoding) belongs in `PageImageValidator` or
`PageImageEncoder`, not inline in the manager.

## Facades and their collaborators

Two former god classes are now facades. **Their public API did not change** — `app/` reaches them
through `globalAppGraph`, and `yakuyomi-stub/` mirrors the same surface for the `nomtl` flavor, so
every public member stays on the original class and delegates. The collaborators are `internal` and
held `private`, so nothing outside the module can see them; a split must not widen an API.

`MangaTranslatorService` (466 lines, 10 public) — Ichigo image translation
- `MangaTranslatorFingerprint` — `clientUuid`, `fingerprint`, `buildFingerprint`,
  `deviceMemoryBucket`, `screenInfo`, `canvasHash`, `hashString`. Depends on `prefs` + `context`.
  It fabricates a **fixed** web-client profile (WebGL, connection, browser capability, touch,
  orientation, perf) so the service sees a browser. The strings are fixed on purpose — consistency
  matters more than describing the real device — so a partial edit here changes the identity the
  account was registered with.
- `MangaTranslatorAuth` — token storage, `refreshAccessToken`, `login`, `signup`, `logout`,
  `clearAuth`, `isLoggedIn`, `getCurrentUser`, and the two header builders. Takes `baseUrl` and the
  retry helper as lambdas: it posts to `baseUrl()/auth/*`, and the retry helper must reach the
  stored token, which is the whole point of retrying with a refreshed one.
- The service keeps `baseUrl` / `isPrivateHost`, the `allowedTargetLangs` / `allowedModels`
  allowlists and their sanitisers, the translation path, and `executeWithAuthRetry`.

`LocalLlmManager` (141 lines, 24 public) — on-device GGUF provider
- `ImportedGgufModels` — the custom-model directory, `importGguf`, and its file mechanics. An
  import stages into a `.tmp` and drops it on **any** failure: a partial file is invisible to
  `importedModels` (which lists only `.gguf`) yet still counts against the storage check that gates
  the next import, so a leak strands the user with no way to see or clear it.
- `LocalLlmSampling` — the per-model override map, stored as one JSON blob under a single pref.
- `LocalLlmSession` — the llama.cpp backend, its mutex, and running/loading state. Takes
  `resolveModel` as a lambda rather than resolving for itself.
- The manager keeps model *selection* (`resolveModel`, `modelById`, `isModelReady`,
  `isLocalProvider`) because it is the one decision all three read from, plus the download
  passthroughs. The public `running` / `loading` / `importing` are typed `StateFlow<Boolean>`, not the
  collaborators' own types.

`OrtUpscaleSession` (301), `ModelManager` (405) and `LocalLlmDownloadManager` (333) were checked and
are each already single-concern; do not split them for line count alone.

## Key classes

| Class | Role |
|---|---|
| `TranslationManager` | Facade: gates, per-manga toggles, status, user-facing errors |
| `TranslationProviderResolver` | Single provider decision + the cache identity that goes with it |
| `TextTranslationProtocol` / `TranslationRequest` | The line protocol and its per-request inputs |
| `PageQueue` | Per-chapter page-ordered work, pending cap, cancel/pause/resume |
| `PageResultCache` | Both page stores; exposes the three persistence policies by name |
| `PageImageValidator` | Page size/dimension limits and the decode sample size |
| `PageImageEncoder` | bitmap → JPEG for vision, WEBP for output and cache |
| `YakuyomiEngine` | Native session lifetime and dispatch; nothing else |
| `EngineConfigFactory` | prefs → config, typeface, text colour, orientation, rebuild triggers |
| `LongPageStitcher` | Tall-page slicing, stitching, stats summing, region offsetting |
| `NativeComponents` | The detector/OCR/inpainter sessions as one unit |
| `YakuyomiTranslator` | Cloud LLM (openrouter/gemini/opencode_zen/nvidia_nim/custom_openai) |
| `GeminiNanoTranslator` | On-device ML Kit GenAI (`genai-prompt:1.0.0-beta4`); priority LLM when AVAILABLE |
| `LocalLlmManager` / `LocalLlmTranslator` / `LlamaCppLlmBackend` | Local GGUF via llama.cpp; per-model sampling overrides |
| `MangaTranslatorService` / `MangaTranslatorAuth` / `MangaTranslatorFingerprint` | Ichigo image translation; facade over session + device identity |
| `LocalLlmAccelerator` | Probes whether `gpuLayers` can actually offload (GPU backend built in? Vulkan compute?) |
| `LocalLlmDownloadManager` | Resumable GGUF+mmproj downloads (Range resume, throttled emits) |
| `ModelManager` | Downloads detector/OCR/inpainter models |
| `TranslationCache` / `TranslatedPageStore` | Low-level stores; go through `PageResultCache` |
| `BreadcrumbNotes` / `MangaInfoTranslation(Store)` | Cross-page consistency notes; per-manga toggle store |
| `TranslationPreferences` | ~54 prefs (engine tuning, per-provider keys); stub duplicate in `yakuyomi-stub/` |
| `TranslationPrompt.kt` | Prompt text, sanitisation, line alignment, JSON parsing |
| `TranslationStatus` / `TranslationErrorMapper` / `TranslationMetrics` | Status, error mapping, metrics |
| `TranslationException` | Provider failed and offline fallback is off (page → FAILED, retryable) |

## Gotchas

- ML Kit beta4 API differs from docs: `Generation.getClient()` → `GenerativeModel`;
  `checkStatus(): Int` (0–3); `download(): Flow<DownloadStatus>`; request via
  `generateContentRequest(ImagePart, TextPart) { temperature; maxOutputTokens }`.
- llama.cpp backend: `updateGenerateParams(...)` MUST be called or models output nothing;
  wrap prompts with `applyChatTemplate`.
- The llama.cpp runtime is **not** the `com.llamatik:library` AAR — it is built from the
  `external/llamatik` fork by `:llamatik-native` (see `README-local-llm.md`). The upstream AAR
  is CPU-only, so its `gpuLayers` offloads nothing.
- `external/llamatik` is a submodule with **nested** submodules: use
  `git submodule update --init --recursive external/llamatik` or CMake aborts.
- GPU offload (ggml Vulkan) is opt-in via `-Pmtl.gpuOffload=true`. It needs Vulkan-Hpp +
  SPIRV-Headers (the NDK has `glslc` but not those) and compiles against **API 29+**, because
  ggml-vulkan needs a Vulkan 1.1 core symbol the NDK's `libvulkan.so` stub only exports from
  there. Stripped cost: 6.6 MB/ABI CPU-only vs 64.2 MB/ABI with Vulkan.
- llama.cpp never fails an offload it cannot perform — it logs
  `compiled without support for GPU offload` and runs on the CPU. `LlamaCppLlmBackend` therefore
  forces `gpuLayers` to 0 unless `LocalLlmAccelerator` says offload is real.
- Renderer caps translated text at the original glyph size (`originalFontSize` = median
  line-quad thickness, measured on `TextLine.tightQuad` — the **pre-unclip** rect, since
  DBNet's 2.3x unclip inflates a 30x200 line to ~90x260); `RenderConfig.fixedTextColor` +
  `"fixed"` colorMode override it.
- Inpainting removes **glyph strokes**, not region boxes: `buildSegMask` intersects DBNet's
  stroke mask with the region boxes. Masking whole boxes hands AOT-GAN a hole several times
  the glyph area, which returns as a flat light patch over artwork.
- The **removal** set is every detected region (m-i-t parity — unreadable text is erased too,
  so no raw source script survives beside the English); the **translation** set is only regions
  with readable OCR. Removal's per-region box fallback is gated on readable text, so false
  detections are not blanketed out of the artwork.
- `colorMode="fixed"` resolves outline == fill on light backgrounds, so the renderer must skip
  the outline there — stroking black-on-black emboldens glyphs and closes their counters.
- Settings UI: `SettingsYakuyomiScreen` (+ LLM/engine advanced screens); Gemini Nano toggle
  + live status row; per-model sampling sliders hidden without a model.
- Prompt/parse helpers are shared — do not fork per-provider copies.
- Fork markers: `// KMK -->` for Komikku additions.

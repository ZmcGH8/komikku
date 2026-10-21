# Timeline (append-only)

## 2026-09-24T10:40:16-04:00 | lead | init
- action: case-init
- command_or_ref: skills/scripts/case-init.sh
- result_summary: case directory created; scope ready_for_act=true
- artifacts: [scope.md, workitems.md]
- evidence_ids: []
- decision_delta: [case_initialized]
- carry_forward_refs: [scope.md]
- next: open PRIMARY SKILL.md and ACT within scope

## 2026-09-24T11:00:00-04:00 | lead | static-triage
- action: acquired and hashed public release APK; extracted managed assembly and symbols
- command_or_ref: E-001, E-002
- result_summary: K3 v2.6.0 is a .NET MAUI Android app with recoverable managed services
- artifacts: [apk/K3Manga.AutoTranslate.v2.6.0.apk, analysis/dotnet/K3_Manga_AutoTranslate_Mobile.dll, analysis/dotnet/K3_Manga_AutoTranslate_Mobile.pdb]
- evidence_ids: [E-001, E-002]
- decision_delta: [static_analysis_anchor_established]
- carry_forward_refs: [E-001, E-002]
- next: reconstruct service call path

## 2026-09-24T11:20:00-04:00 | lead | pipeline-reconstruction
- action: traced OCR, Gemini upload, block matching, inpainting, rendering, and long-page slicing
- command_or_ref: E-003, E-004
- result_summary: K3 prioritizes image-grounded translation and pre-downscale strip processing; it uses geometric/text matching and flat/AOT cleanup
- artifacts: [analysis/dotnet/decompiled/MangaTranslatorMobile/Services/OcrService.cs, GeminiService.cs, Phase4PrepService.cs, InpaintService.cs, RenderService.cs, ManhwaSlicerService.cs]
- evidence_ids: [E-003, E-004]
- decision_delta: [quality_first_adaptation_matrix_created]
- carry_forward_refs: [E-003, E-004]
- next: implement Houri-side quality adaptations

## 2026-09-24T12:00:00-04:00 | lead | adaptation
- action: added stable region IDs, ID-aligned response parsing, Gemini image context, uniform-bubble AOT routing, long-page slicing, and default-on settings
- command_or_ref: E-005
- result_summary: quality controls are wired through wrapper, external engine, provider path, and advanced settings
- artifacts: [yakuyomi-engine/src/main/kotlin/exh/yakuyomi/TranslationPrompt.kt, LongPageSlicer.kt, YakuyomiEngine.kt, TranslationManager.kt, YakuyomiTranslator.kt, external/yakuyomi-engine/engine/src/main/kotlin/li/joye/yakuyomi/engine/Inpainter.kt, app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsYakuyomiEngineAdvancedScreen.kt]
- evidence_ids: [E-005]
- decision_delta: [implementation_completed_uncommitted]
- carry_forward_refs: [E-005]
- next: resolve reported compiler error and validate without Gradle

## 2026-09-24T12:20:00-04:00 | lead | compile-fix
- action: corrected Inpainter maxOf import after user-provided compile error
- command_or_ref: E-006
- result_summary: kotlin.math.maxOf -> kotlin.comparisons.maxOf; no agent Gradle run performed
- artifacts: [external/yakuyomi-engine/engine/src/main/kotlin/li/joye/yakuyomi/engine/Inpainter.kt]
- evidence_ids: [E-006]
- decision_delta: [compile_error_observed_and_minimally_fixed]
- carry_forward_refs: [E-006]
- next: report, journal, and user-side compile verification

## 2026-09-24T12:40:00-04:00 | lead | validation
- action: ran git diff checks, XML parse validation, static source inspection, and evidence creation
- command_or_ref: E-005, E-006
- result_summary: main and submodule diff checks pass; XML parses; LSP remains environment-blocked and was not treated as a clean build result
- artifacts: [reports/, evidence/]
- evidence_ids: [E-005, E-006]
- decision_delta: [static_validation_completed_with_gradle_limitation]
- carry_forward_refs: [E-005, E-006]
- next: handoff report and anonymized field journal

## 2026-10-01T21:40:00-04:00 | lead | v3-acquisition
- action: acquired and hashed the v3.1.0 public release APK; carved and decompiled the managed assembly
- command_or_ref: E-007
- result_summary: v3.1.0 is unobfuscated .NET MAUI; 58 decompiled files vs 52 at v2.6.0, +25% lines; managed PE sits at ELF offset 16384
- artifacts: [apk/K3Manga.AutoTranslate.v3.1.0.apk, analysis/dotnet/v3/managed.dll, analysis/dotnet/decompiled-v3/]
- evidence_ids: [E-007]
- decision_delta: [v3_pipeline_delta_identified]
- carry_forward_refs: [E-007]
- next: establish what may be reused before changing any code

## 2026-10-01T22:10:00-04:00 | lead | licensing-boundary
- action: checked the public repository for source and for any licence grant
- command_or_ref: E-008
- result_summary: zero .cs files in the v2.6.0 and v3.1.0 trees; no LICENSE has ever existed; the download page states "All rights reserved"
- artifacts: [evidence/E-008.md]
- evidence_ids: [E-008]
- decision_delta: [adaptation_is_behavioural_not_textual]
- carry_forward_refs: [E-008]
- next: implement the adopted techniques against documented provider APIs only

## 2026-10-01T22:35:00-04:00 | lead | v3-adaptation
- action: pinned the Gemini reply to a JSON response schema and added a per-line censored stand-in
- command_or_ref: E-009
- result_summary: responseMimeType + responseSchema with index-keyed parsing and loose-parser fallback; a declined line no longer fails the page
- artifacts: [yakuyomi-engine/src/main/kotlin/exh/yakuyomi/TranslationPrompt.kt, yakuyomi-engine/src/main/kotlin/exh/yakuyomi/YakuyomiTranslator.kt]
- evidence_ids: [E-009]
- decision_delta: [structured_cloud_reply_adopted; ocr_swap_blocked_on_hosting]
- carry_forward_refs: [E-009]
- next: user-side build verification; OCR swap only once weights are published

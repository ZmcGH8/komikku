# Work Items

| ID | title | role | targets | surface | status | evidence | notes |
|----|-------|------|---------|---------|--------|----------|-------|
| WI-001 | Establish scope and auth | lead | case | process | completed | scope.md | written authorization, authorized_target_only |
| WI-002 | Triage public APK and recover managed assembly | lead | public APK | static | completed | E-001, E-002 | v2.6.0, .NET MAUI DLL/PDB |
| WI-003 | Reconstruct OCR, Gemini, matching, inpainting, rendering | lead | K3 services | static | completed | E-003 | quality-oriented upstream behavior |
| WI-004 | Reconstruct long-page slicing behavior | lead | ManhwaSlicerService | static | completed | E-004 | constants, gutter and energy cuts |
| WI-005 | Adapt quality controls into Houri | lead | wrapper + external engine | source | completed | E-005 | uncommitted main/submodule changes |
| WI-006 | Resolve compile error and perform non-Gradle validation | lead | Inpainter.kt | source | completed | E-006 | maxOf import corrected; Gradle not rerun by agent |
| WI-007 | Acquire and decompile v3.1.0; diff against the v2.6.0 baseline | lead | v3.1.0 APK | static | completed | E-007 | managed PE carved at ELF offset 16384; 58 files vs 52 |
| WI-008 | Adapt v3.1.0 behaviours into Houri (behavioural, not textual) | lead | Gemini path | source | completed | E-008, E-009 | structured reply + per-line censored; OCR swap blocked on hosting |
| WI-009 | Bound the licensing question before porting any implementation | lead | public repo | remote | completed | E-008 | repo has no source and states all rights reserved |

## Coverage
- [x] Recon/analysis complete for in_scope assets
- [x] Critical/High candidates triaged (or N/A for pure RE)
- [x] Validated findings have Evidence (E-*)
- [x] Path documented (attack/call/solve)
- [x] Timeline continuous across major phases
- [x] Report via docs-generator
- [x] field-journal anonymized

## Refs
- skills/ops/timeline-workitem.md
- skills/ops/evidence-finding-path.md

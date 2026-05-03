# Docs Directory — Agent Guidelines

## Directory structure

```plaintext
docs/
  notes/    Architecture Decision Records. Filename: adr-NNN-<topic>.md
  plans/    Implementation plans. Filename: phase-N-<scope>.md
  specs/    Public API contract and pipeline descriptions.
  AGENTS.md (this file)
```

## Rules

1. **Do not create new subdirectories** inside `docs/`. The three folders above cover all
   documentation needs for this project.

2. **Do not create `docs/superpowers/`** or any skill-, framework-, or agent-internal
   directory here. Those artefacts do not belong in project documentation.

3. **One canonical file per topic.** Do not create date-prefixed copies of existing files
   (e.g. `2026-05-01-phase-2-android.md` alongside `phase-2-android.md`). Update the
   existing file in place.

4. **Step-by-step execution plans with embedded code snippets are not persistent docs.**
   Write a concise summary plan instead and keep the working code in the source tree.

## Where to put things

| What you are adding                  | Where                                           |
| ------------------------------------ | ----------------------------------------------- |
| New implementation plan              | `docs/plans/phase-N-<scope>.md`                 |
| API / type reference update          | `docs/specs/api-contract.md` (extend existing)  |
| Internal pipeline description update | `docs/specs/scan-pipeline.md` (extend existing) |
| Architecture decision (ADR)          | `docs/notes/adr-NNN-<topic>.md` (next: 005)     |
| Design context / background          | `docs/notes/` using ADR format                  |

## Existing files (do not duplicate)

- `docs/specs/api-contract.md` — public `scan()` API, types, error codes
- `docs/specs/scan-pipeline.md` — internal processing pipeline per platform
- `docs/plans/phase-1-js-wrapper.md` — JS layer implementation (complete)
- `docs/plans/phase-2-android.md` — Android ML Kit implementation (complete)
- `docs/plans/phase-3-ios.md` — iOS VisionKit + gallery crop implementation (complete)
- `docs/notes/adr-001-android-mlkit.md`
- `docs/notes/adr-002-ios-gallery-crop.md`
- `docs/notes/adr-003-package-boundaries.md`
- `docs/notes/adr-004-ios-crop-editor-realdevice-fixes.md`

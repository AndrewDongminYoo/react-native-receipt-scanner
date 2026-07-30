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
   directory here. Those artifacts do not belong in project documentation.

3. **One canonical file per topic.** Do not create date-prefixed copies of existing files
   (e.g. `2026-05-01-phase-2-android.md` alongside `phase-2-android.md`). Update the
   existing file in place.

4. **Step-by-step execution plans with embedded code snippets are not persistent docs.**
   Write a concise summary plan instead and keep the working code in the source tree.

## Where to put things

| What you are adding                  | Where                                                                   |
| ------------------------------------ | ----------------------------------------------------------------------- |
| New implementation plan              | `docs/plans/phase-N-<scope>.md`                                         |
| API / type reference update          | `docs/specs/api-contract.md` (extend existing)                          |
| Internal pipeline description update | `docs/specs/scan-pipeline.md` (extend existing)                         |
| Architecture decision (ADR)          | `docs/notes/adr-NNN-<topic>.md` — derive NNN from `ls docs/notes/adr-*` |
| Design context / background          | `docs/notes/` using ADR format                                          |

## Existing files (do not duplicate)

**List the directory before adding a file** — `ls docs/specs docs/plans docs/notes`. This section used to enumerate them, fell behind, and so caused the duplication it existed to prevent: an agent trusting a stale inventory creates the file it was told already existed elsewhere.

A spec is **normative** when it defines a contract the code must satisfy, rather than describing or planning one. Extend a normative spec in place; do not write a competing document beside it. Currently normative:

- `docs/specs/api-contract.md` — the public `scan()` API, types, and error codes
- `docs/specs/scan-pipeline.md` — the internal processing pipeline per platform
- `docs/specs/multilingual-ocr.md` — `ocrLanguages`, `getOcrCapabilities()`, language resolution and its error contract

Apply the criterion rather than trusting this list to stay complete: a new spec that defines a contract is normative whether or not anyone updated this section.

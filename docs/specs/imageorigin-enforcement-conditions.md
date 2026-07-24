# `imageOrigin` Enforcement — Re-Design Conditions (Deferred)

**Spec version:** 0.1 (gate conditions — enforcement remains deferred)
**Created:** 2026-06-11
**Status:** Deferred. This document defines the conditions under which re-design may begin; it does **not** re-enable filtering.
**Parent decision:** phase-6 Decision 1 + Deferred "`imageOrigin` enforcement"
**Related:** [`api-contract.md` § `imageOrigin` platform behavior](./api-contract.md#imageorigin-platform-behavior), [`../notes/platform-asymmetries.md` § 5 ImageOrigin 분류](../notes/platform-asymmetries.md#5-imageorigin-분류), [`../notes/adr-005-android-gallery-strategy.md`](../notes/adr-005-android-gallery-strategy.md)
**Wiki:** `concepts/image-origin-classification.md`, `concepts/image-origin-filtering.md`

## Purpose

phase-6 Decision 1 froze `imageOrigin` as telemetry-only and said a future version "can revisit it only after a measured classifier combines origin, EXIF, OCR, server validation, and user-outcome labels with acceptable false-positive rates."
That sentence is a gate, not a plan.
This document makes the gate concrete: it enumerates the conditions that must each be satisfied before any enforcement re-design starts, so the question "is it time yet?" has a checklist answer instead of a judgment call.

Nothing here turns enforcement on.
Re-design does not begin until **every** condition below is met, and even then the first step is measurement, not blocking.

## Current behavior (the baseline this gate protects)

`imageOrigin` is a stable public enum — `"camera" | "screenshot" | "download" | "unknown"` — populated by platform-specific inference and surfaced for analysis only.
No code in this package or (per Decision 1) the consuming app blocks an upload based on it.

How it is inferred today (precedence-ordered rules are documented in [`api-contract.md` § `imageOrigin` platform behavior](./api-contract.md#imageorigin-platform-behavior); the points below name only the signals the failure-mode analysis depends on):

- **iOS** (`RNGalleryPickerDelegate.m`): an EXIF heuristic over `dateTimeOriginal` / `make` / `model`. With no camera metadata at all the EXIF heuristic returns `download`; partial metadata falls through to `unknown`.
- **Android** (`ImageProcessor.inferOrigin`): `MediaStore.BUCKET_DISPLAY_NAME` exact-match, else the same EXIF heuristic. The EXIF fallback never returns `download` — only an explicit bucket match does.
- The **GMS camera gallery-import path** strips EXIF and hides the original URI, collapsing origin to `unknown` — one reason that path was disabled.

## Why enforcement is deferred: the false-positive mechanism

The classifier infers origin from signals that are **not reliably present on legitimate uploads**, so blocking on it would reject real users. The concrete failure modes:

1. **Stripped EXIF on legitimate camera photos.**
   Messaging apps (KakaoTalk, etc.), social re-encoding, cloud-sync, and editors routinely strip EXIF.
   A user who photographs a receipt, sends it to themselves via KakaoTalk, then uploads from the gallery has a _camera-original_ receipt whose EXIF is gone — the iOS heuristic classifies it `download`, Android `unknown`.
   Blocking `download`/`unknown` blocks this legitimate user.

2. **Legitimate e-receipt screenshots.**
   Digital/e-receipts (card-app receipts, delivery-app order confirmations) are a **valid** receipt class for a reward app, and they arrive as `screenshot`.
   "Screenshot" is therefore not inherently fraud — treating it as such is a _product-policy_ error, not just a technical one.

3. **Bucket-name fragility (Android).**
   `BUCKET_DISPLAY_NAME` is a folder label that varies by OEM, locale, and user organization.
   A Korean device or a custom gallery folder may not match `"camera"`, dropping a real camera capture to the EXIF fallback and then to `unknown`.

4. **Cross-platform non-comparability.**
   iOS and Android fill the same enum from different signals (EXIF vs bucket+EXIF), and `unknown` is _permissive, not suspicious_ by design.
   A single cross-platform enforcement threshold is structurally invalid — the platforms' `unknown`/`download` populations are not the same thing.

The cost is asymmetric: **blocking a legitimate user is far more expensive than letting a questionable image through**, because the latter is caught downstream by server-side receipt validation, while the former is a silent, unrecoverable rejection of a paying user. This asymmetry is why the bar for enforcement is high.

## Re-design conditions (the gate)

Re-design may begin **only when all of the following hold**. Each is verifiable, not aspirational.

- **C1 — Product policy defined first.**
  A written product decision states _which_ origins are disallowed and why — in particular whether e-receipt `screenshot`s are allowed (see failure mode 2). Without this, "enforcement" has no target.

- **C2 — Labeled outcome corpus exists.**
  A corpus of real production uploads, each carrying ground-truth labels for (a) true origin, (b) legitimate vs. fraudulent/non-receipt, and (c) the server-side validation outcome. Built from the telemetry already being collected (see [Inputs](#inputs-already-being-collected)).

- **C3 — Classifier is multi-signal, not origin-alone.**
  Per Decision 1, the enforcement decision combines `imageOrigin` **and** EXIF **and** OCR quality **and** server validation **and** user-outcome labels. `imageOrigin` alone is never the gate.

- **C4 — Measured false-positive rate within an agreed budget.**
  The classifier's FP rate (legitimate uploads it would block) is measured **per platform** on a held-out partition of the C2 corpus and is at or below a budget agreed with product. No cross-platform threshold.

- **C5 — Reversible staged rollout with a kill switch.**
  Enforcement ships behind remote config as telemetry → warn → soft-block (with easy override) → block, each stage gated on the FP metric staying within budget, and instantly revertible. Mirrors the OCR Floor rollout.

- **C6 — Known false-positive sources explicitly handled.**
  The design states how it avoids each failure mode above — at minimum: stripped-EXIF camera photos are not blocked on EXIF absence alone, and allowed e-receipt screenshots (per C1) are excluded from the block set.

If any condition is unmet, the correct action is to keep `imageOrigin` telemetry-only and continue collecting data — not to ship a partial gate.

## Inputs already being collected

The data that will eventually satisfy C2/C3/C4 is accruing now; this gate is the consumer of that work, not a separate data effort:

- **`imageOrigin` distribution telemetry** (Decision 1) — origin mix by source/platform, observation-only.
- **Raw EXIF presence telemetry** — `software` patterns and long-tail tag presence as classifier inputs (telemetry-only, bucketed, no raw values).
- **OCR Floor telemetry** — `textLength`/`lineCount`/`confidence` buckets and reject distribution.
- **Server-side validation outcomes** — the ground-truth signal for legitimate vs. rejected, owned by the backend.

## Non-goals (explicitly out of scope until the gate opens)

- Do **not** block on `imageOrigin` alone (violates C3 and Decision 1).
- Do **not** treat `unknown` as suspicious — it is permissive by design.
- Do **not** treat all `screenshot`s as fraud before C1 defines policy.
- Do **not** set a single cross-platform threshold (violates C4 / failure mode 4).
- Do **not** tighten any client-side gate before the FP rate is measured (a phase-6 Risk).

## Decision log

- **2026-06-11** — Conditions opened. Converted Decision 1's prose gate into six verifiable conditions (C1–C6), grounded the deferral in the actual iOS/Android classification code and four concrete false-positive mechanisms, and tied the gate to the telemetry already being collected (origin distribution). No enforcement enabled; `imageOrigin` stays telemetry-only.

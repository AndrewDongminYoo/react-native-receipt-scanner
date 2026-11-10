# Missing OCR confidence policy (issue #28)

Preserve the default compatibility policy and let callers explicitly reject unavailable confidence when `minConfidence > 0`.
The normative contract lives in `docs/specs/api-contract.md`.

1. Add mocked-native regressions for absent confidence, real zero, and values below, equal to, and above the threshold under both policies; observe strict-policy failure first.
2. Extend `OcrFloor` and its JS predicate with `missingConfidence: "accept" | "reject"`, defaulting to `"accept"`; keep the native provider mapping unchanged.
3. Update public types, README and platform documentation with provider sentinel limitations and unchanged OCR-disabled behavior.
4. Run `yarn typecheck`, `yarn lint`, `yarn test`, scoped `trunk fmt`, and `trunk check`; review the candidate and complete the PR lifecycle.

Owned paths are `src/types.ts`, `src/scan.native.tsx`, `src/__tests__/index.test.tsx`, `README.md`, `docs/specs/api-contract.md`, `docs/notes/platform-asymmetries.md`, and this plan.
No native implementation or visual behavior changes are required.
Device observations of old and new Play Services are outside this JS policy verification; native sentinel behavior is assessed from source only.

# Allocation-Free OCR Overlap Core

Tracking: [issue #67](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/issues/67).
The accepted [pilot specification](../specs/ocr-overlap-pilot.md) supplies direction and acceptance criteria.

## Scope and ownership

Add the canonical C ABI, implementation, standalone CMake build and host tests under `cpp/receipt_core`.
Add a Linux CI job and deterministic source metadata for downstream vendoring.
Keep production JS/Dart defaults and mobile build wiring unchanged; adapters and adoption remain #68, #69 and #70.

## Sequence

1. Specify fixed-width ABI layout, statuses, borrowed lifetimes, null/empty semantics and malformed-range behavior in the header.
2. Observe an exact-overlap regression fail against a zero-overlap implementation, then implement validation and deepest exact matching without allocations.
3. Exercise literal boundary/malformed cases, concurrent calls and the shared merge corpus through a host-only normalization/assembly adapter.
4. Run Linux CMake/CTest with ASan/UBSan and C header/linkage checks, plus the repository's Yarn and scoped Trunk gates.
5. Review safety, contract coverage and affected prose; commit canonical sources before pinning their immutable revision and SHA-256 metadata.

## Acceptance and limits

All shared merge cases must retain their literal results; existing JS quality cases continue validating host policy.
Negative controls must reject a corrupted expectation and a modified vendored source.
The C ABI validates every descriptor before comparing data, returns zero on invalid input with writable output, and allows no exceptions, allocations, retained pointers or mutable global state.
Host correctness and sanitizers do not establish mobile adapter integration, device performance or production adoption.

# Receipt Overlap Core

Experimental canonical source for [issue #67](https://github.com/AndrewDongminYoo/react-native-receipt-scanner/issues/67), under the [accepted pilot boundary](../../docs/specs/ocr-overlap-pilot.md).
Production package calls continue using their existing host matchers.
This directory provides no mobile adapter or device performance claim.

## ABI v1

`receipt_core.h` is the canonical layout, status, lifetime and calling-convention contract.
Check `receipt_core_abi_version()` against `RECEIPT_CORE_ABI_VERSION` before calling `receipt_core_find_overlap_v1`.
All lengths, offsets, counts and statuses are `uint32_t`; input units are native-endian `uint16_t`.
The output pointer must be separate from the inputs.
Descriptors may reference empty or repeated ranges; host adapters filter empty OCR lines before calling.
The core compares units literally and leaves Unicode normalization, original text, assembly and quality policy to each host.

The matcher validates both pages, searches deepest first and uses constant auxiliary memory.
Its worst case follows the existing host search: quadratic in line count, multiplied by compared line length.
It makes no allocation or library call, retains no input, uses no mutable global state, and builds with C++ exceptions disabled.
Sanitizers inspect declared accessible test storage; no C ABI can prove an arbitrary pointer is accessible.

## Host verification

Requirements: CMake 3.20+, C/C++ compiler, threads, and Node for the host-only shared-corpus adapter.
From the repository root on Linux:

```bash
cmake -S cpp/receipt_core -B /tmp/receipt-core-build -D CMAKE_BUILD_TYPE=Release
cmake --build /tmp/receipt-core-build --parallel 2
ctest --test-dir /tmp/receipt-core-build --output-on-failure
CC=clang CXX=clang++ cmake -S cpp/receipt_core -B /tmp/receipt-core-san -D RECEIPT_CORE_SANITIZERS=ON -D BUILD_SHARED_LIBS=ON
cmake --build /tmp/receipt-core-san --parallel 2
ASAN_OPTIONS=detect_leaks=1 UBSAN_OPTIONS=halt_on_error=1 ctest --test-dir /tmp/receipt-core-san --output-on-failure
```

CTest exercises C compilation/linkage, exact matching, malformed descriptors and concurrent calls.
The host-only corpus adapter normalizes the shared synthetic merge inputs, invokes the real core for adjacent original pages and compares assembled results with literal expectations.
It does not move page assembly into native code or replace the existing JS quality tests.
Run `yarn test` for those host policy tests.

## Vendoring

Copy this source directory and preserve `LICENSE` and `manifest.json`.
The manifest pins the upstream repository, an immutable commit containing these exact source bytes, ABI version and SHA-256 hashes.
It excludes only itself; no consumer build fetches source.
Verify a copied directory offline with `python3 vendor.py --check` from that directory.
When checking the canonical checkout, add `--upstream-check` to compare every file with the pinned local Git revision as well.
Hashes establish snapshot integrity; offline checking alone cannot authenticate the upstream repository or prove that a revision is published.

After source changes, commit the canonical files first, then run the following from the repository root and commit the resulting metadata separately:

```bash
python3 cpp/receipt_core/vendor.py --pin <full-source-commit-sha>
python3 cpp/receipt_core/vendor.py --check --upstream-check
```

For a vendored consumer build, pass `-D BUILD_TESTING=OFF` and link target `receipt_core`.
Canonical corpus tests additionally require the repository's shared fixture directory; they are not a standalone vendored fixture snapshot.
Static builds are the default; `-D BUILD_SHARED_LIBS=ON` exports the same C symbols.
Adapter integration and packed-consumer/device acceptance remain separate tracked work.

#!/usr/bin/env bash
set -euo pipefail
repo_root=$(cd "$(dirname "$0")/../.." && pwd)
simulator=${1:?Usage: run.sh booted-simulator-UUID [contract.json]}
fixture=${2:-"${repo_root}/src/__tests__/fixtures/ocr-contracts/v1/contract.json"}
build_dir=$(mktemp -d "${TMPDIR:-/tmp}/receipt-geometry.XXXXXX")
trap 'rm -f "$build_dir/geometry-reader"; rmdir "$build_dir" 2>/dev/null || true' EXIT
sdk=$(xcrun --sdk iphonesimulator --show-sdk-path)
architecture=$(uname -m)
xcrun --sdk iphonesimulator clang -target "${architecture}-apple-ios16.0-simulator" \
  -isysroot "${sdk}" -fobjc-arc -Wall -Wextra -Werror \
  -I "${repo_root}/ios" "${repo_root}/scripts/apple-geometry/main.m" \
  "${repo_root}/ios/RNOcrGeometry.m" "${repo_root}/ios/RNQuadGeometry.m" \
  -framework Foundation -framework CoreGraphics -framework UIKit \
  -o "${build_dir}/geometry-reader"
xcrun simctl spawn "${simulator}" "${build_dir}/geometry-reader" "${fixture}"

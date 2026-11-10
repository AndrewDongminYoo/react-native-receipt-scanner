#!/usr/bin/env bash
set -euo pipefail
repo_root=$(cd "$(dirname "$0")/../.." && pwd)
simulator=${1:?Usage: run.sh booted-simulator-UUID}
build_dir=$(mktemp -d "${TMPDIR:-/tmp}/receipt-metadata.XXXXXX")
trap 'rm -f "$build_dir/metadata-reader"; rmdir "$build_dir" 2>/dev/null || true' EXIT
sdk=$(xcrun --sdk iphonesimulator --show-sdk-path)
architecture=$(uname -m)
xcrun --sdk iphonesimulator clang -target "${architecture}-apple-ios16.0-simulator" \
  -isysroot "${sdk}" -fobjc-arc -Wall -Wextra -Werror -Wno-unused-parameter \
  -I "${repo_root}/ios" "${repo_root}/scripts/apple-metadata/main.m" \
  "${repo_root}/ios/RNImageProcessor.m" "${repo_root}/ios/RNQuadGeometry.m" \
  -framework Foundation -framework CoreGraphics -framework UIKit \
  -framework ImageIO -framework CoreImage -framework UniformTypeIdentifiers \
  -o "${build_dir}/metadata-reader"
xcrun simctl spawn "${simulator}" "${build_dir}/metadata-reader"

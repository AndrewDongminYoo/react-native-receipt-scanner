#!/bin/sh
set -eu

build_number="${BUILD_NUMBER:-$(TZ='Asia/Seoul' date +'%y%m%d%H')}"

case "${build_number}" in
"" | *[!0-9]*)
  echo "error: BUILD_NUMBER must be numeric; got '${build_number}'" >&2
  exit 1
  ;;
*) ;;
esac

source_info_plist="${SRCROOT}/ReceiptScannerExample/Info.plist"
generated_info_plist="${DERIVED_FILE_DIR}/ReceiptScannerExample-Info.plist"

if [ ! -f "${source_info_plist}" ]; then
  echo "error: source Info.plist not found at ${source_info_plist}" >&2
  exit 1
fi

mkdir -p "$(dirname "${generated_info_plist}")"
cp "${source_info_plist}" "${generated_info_plist}"

/usr/libexec/PlistBuddy -c "Set :CFBundleVersion ${build_number}" "${generated_info_plist}"
echo "Generated Info.plist with CFBundleVersion ${build_number}"

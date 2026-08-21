#!/usr/bin/env bash
# SPDX-License-Identifier: MPL-2.0
# Downloads the pinned VideoLAN VLCKit XCFramework to the gitignored path used
# by Kotlin/Native and the local Swift package. Skips a valid existing copy.
set -euo pipefail

VERSION="4.0.0a23"
URL="https://download.videolan.org/cocoapods/unstable/VLCKit-4.0-20260805-1123.zip"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST_DIR="$REPO_ROOT/ios-app/Frameworks"
XCFRAMEWORK="$DEST_DIR/VLCKit.xcframework"
STAMP="$DEST_DIR/.vlckit-$VERSION.stamp"
ARCHIVE="$DEST_DIR/VLCKit-4.0-20260805-1123.zip"
TMP=""

OLD_XCFRAMEWORK="$DEST_DIR/MobileVLCKit.xcframework"
OLD_STAMP="$DEST_DIR/.mobilevlckit-3.7.3.stamp"
OLD_ARCHIVE="$DEST_DIR/MobileVLCKit-3.7.3.tar.xz"

if [[ -d "$XCFRAMEWORK" &&
      -d "$XCFRAMEWORK/ios-arm64" &&
      -d "$XCFRAMEWORK/ios-arm64_x86_64-simulator" &&
      -f "$STAMP" &&
      "$(<"$STAMP")" == "$VERSION" ]]; then
  echo "VLCKit $VERSION already present at $XCFRAMEWORK"
  exit 0
fi

cleanup() {
  [[ -z "$TMP" ]] || rm -rf "$TMP"
  rm -f "$ARCHIVE"
}
trap cleanup EXIT

mkdir -p "$DEST_DIR"
echo "Downloading VLCKit $VERSION..."
curl -fSL --retry 3 -o "$ARCHIVE" "$URL"

echo "Extracting..."
TMP="$(mktemp -d)"
ditto -x -k "$ARCHIVE" "$TMP"
SRC="$(find "$TMP" -maxdepth 4 -name VLCKit.xcframework -type d | head -1)"
if [[ -z "$SRC" ]]; then
  echo "ERROR: VLCKit.xcframework not found in archive" >&2
  exit 1
fi
if [[ ! -d "$SRC/ios-arm64" || ! -d "$SRC/ios-arm64_x86_64-simulator" ]]; then
  echo "ERROR: VLCKit.xcframework is missing the required iOS device/simulator slices" >&2
  exit 1
fi
rm -rf "$XCFRAMEWORK"
mv "$SRC" "$XCFRAMEWORK"
echo "$VERSION" > "$STAMP"
rm -rf "$OLD_XCFRAMEWORK"
rm -f "$OLD_STAMP" "$OLD_ARCHIVE"
echo "VLCKit $VERSION ready at $XCFRAMEWORK"

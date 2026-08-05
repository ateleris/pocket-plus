#!/usr/bin/env bash
set -euo pipefail

VERSION="0.10.1"

case "${1:-}" in
  linux)     platform="linux" ;;
  mac-arm64) platform="mac-arm64" ;;
  mac-x86)   platform="mac-x64" ;;
  win)       platform="win" ;;
  *) echo "Usage: $0 (linux|mac-arm64|mac-x86|win)" >&2; exit 1 ;;
esac

root="$(cd "$(dirname "$0")" && pwd)"
dest="$root/tools/stainless"
url="https://github.com/epfl-lara/stainless/releases/download/v$VERSION/stainless-dotty-standalone-$VERSION-$platform.zip"

zip="$(mktemp)"
trap 'rm -f "$zip"' EXIT

echo "Downloading $url"
curl -fL -o "$zip" "$url"

rm -rf "$dest"
mkdir -p "$dest"
unzip -q "$zip" -d "$dest"
echo "Installed Stainless $VERSION to $dest"

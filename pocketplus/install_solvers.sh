#!/usr/bin/env bash
# Downloads the z3 and cvc5 binaries, which Stainless uses as external solvers (`smt-z3` and
# `smt-cvc5` in scala/stainless.conf), from their GitHub releases into tools/solvers/bin.
# Without them, Stainless falls back to the Z3 bundled in its compiler plugin, which does not prove
# all the VCs.
#
# Usage: ./install_solvers.sh [Z3_VERSION] [CVC5_VERSION]
#   e.g. ./install_solvers.sh 5.4.1 1.4.2. A version is `latest` by default: the highest release
#   number (GitHub's own "latest" flag lags behind for z3).
#
# Linux (x64, arm64), macOS (arm64, x64) and Windows (x64, under Git Bash). Needs curl, unzip and
# python3. Set GITHUB_TOKEN to avoid the rate limit of the GitHub API (CI does). On GitHub Actions,
# tools/solvers/bin is also added to the PATH of the next steps.
set -euo pipefail

z3_version="${1:-latest}"
cvc5_version="${2:-latest}"

root="$(cd "$(dirname "$0")" && pwd)"
dest="$root/tools/solvers/bin"

case "$(uname -s)-$(uname -m)" in
  Linux-x86_64)               z3_asset='x64-glibc-.*\.zip';   cvc5_asset='Linux-x86_64-static\.zip';   exe="" ;;
  Linux-aarch64|Linux-arm64)  z3_asset='arm64-glibc-.*\.zip'; cvc5_asset='Linux-arm64-static\.zip';    exe="" ;;
  Darwin-arm64)               z3_asset='arm64-osx-.*\.zip';   cvc5_asset='macOS-arm64-static\.zip';    exe="" ;;
  Darwin-x86_64)              z3_asset='x64-osx-.*\.zip';     cvc5_asset='macOS-x86_64-static\.zip';   exe="" ;;
  MINGW*-x86_64|MSYS*-x86_64) z3_asset='x64-win\.zip';        cvc5_asset='Win64-x86_64-static\.zip';   exe=".exe" ;;
  *) echo "Unsupported platform: $(uname -s) $(uname -m)" >&2; exit 1 ;;
esac

github_api() {
  local auth=()
  if [ -n "${GITHUB_TOKEN:-}" ]; then auth=(-H "Authorization: Bearer $GITHUB_TOKEN"); fi
  curl -fsSL "${auth[@]}" -H "Accept: application/vnd.github+json" "https://api.github.com/repos/$1"
}

# The download URL of the asset of release `<tag_prefix><version>` of `repo` whose name matches
# `pattern`; `latest` is the non-prerelease release with the highest version.
asset_url() {
  local repo="$1" tag_prefix="$2" version="$3" pattern="$4"
  github_api "$repo/releases?per_page=100" | python3 -c '
import json, re, sys
prefix, version, pattern = sys.argv[1:]
releases = [r for r in json.load(sys.stdin) if not r["draft"] and not r["prerelease"]
            and re.fullmatch(re.escape(prefix) + r"\d+(\.\d+)*", r["tag_name"])]
def number(r): return tuple(int(n) for n in r["tag_name"][len(prefix):].split("."))
if version == "latest":
    release = max(releases, key=number, default=None)
else:
    release = next((r for r in releases if r["tag_name"] == prefix + version), None)
if release is None:
    sys.exit(f"No release {prefix}{version}")
urls = [a["browser_download_url"] for a in release["assets"] if re.search(pattern + "$", a["name"])]
if len(urls) != 1:
    sys.exit(f"Expected one asset matching {pattern} in {release['tag_name']}, found {len(urls)}")
print(urls[0])
' "$tag_prefix" "$version" "$pattern"
}

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$dest"

# Downloads the zip at `url` and copies the executable `name` from it into $dest
install_binary() {
  local name="$1" url="$2"
  echo "Downloading $url"
  curl -fsSL -o "$tmp/$name.zip" "$url"
  unzip -q -o "$tmp/$name.zip" -d "$tmp/$name"
  local bin
  bin="$(find "$tmp/$name" -type f -path "*/bin/$name$exe" | head -n 1)"
  [ -n "$bin" ] || { echo "No bin/$name$exe in $url" >&2; exit 1; }
  cp "$bin" "$dest/$name$exe"
  chmod +x "$dest/$name$exe"
}

# Assigned first so that `set -e` stops the script when no release or asset matches
z3_url="$(asset_url Z3Prover/z3 z3- "$z3_version" "$z3_asset")"
cvc5_url="$(asset_url cvc5/cvc5 cvc5- "$cvc5_version" "$cvc5_asset")"
install_binary z3 "$z3_url"
install_binary cvc5 "$cvc5_url"

"$dest/z3$exe" --version
"$dest/cvc5$exe" --version | head -n 1

if [ -n "${GITHUB_PATH:-}" ]; then
  echo "$dest" >> "$GITHUB_PATH"
fi
echo "Installed z3 and cvc5 into $dest. python/pocketplus/verify.py uses them from there; for"
echo "other tools, add it to the PATH."

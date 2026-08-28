#!/usr/bin/env bash
# Build the POCKET+ shared library on macOS/Linux (the Unix equivalent of pocketplus.vcxproj):
# regenerate pocketplus.c from PocketPlus.scala via Stainless GenC when needed, then compile
# the generated codec + pp_shim into native/build/libpocketplus.{dylib,so}.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
repo="$(dirname "$here")"

jar="$(echo "$repo"/tools/stainless/lib/stainless-dotty-standalone-*.jar)"
[ -f "$jar" ] || { echo "Stainless jar not found; run ./install.sh first." >&2; exit 1; }

scala_src="$repo/scala/PocketPlus.scala"
genc_c="$here/generated/pocketplus.c"
jvm_args="-Xss512m --sun-misc-unsafe-memory-access=allow"

if [ ! -f "$genc_c" ] || [ "$scala_src" -nt "$genc_c" ]; then
  echo "[GenC] PocketPlus.scala -> pocketplus.c"
  (cd "$repo" && java $jvm_args -jar "$jar" --config-file=false --genc --genc-output="$genc_c" "$scala_src")
  [ -f "$genc_c" ] || { echo "GenC did not produce $genc_c" >&2; exit 1; }
fi

# The GenC output exports generic symbol names (compress, decompress, main). On
# Linux (flat ELF namespace) a host process that already has libz in its global
# scope — e.g. Ubuntu's python3, which links libz.so.1 — interposes zlib's
# compress() over ours inside the shim's internal calls, returning Z_BUF_ERROR
# (-5) or crashing. -Bsymbolic-functions binds the library's internal calls to
# its own definitions. macOS needs nothing: two-level namespaces bind per-library.
case "$(uname -s)" in
  Darwin) lib="libpocketplus.dylib"; ldflags="" ;;
  *)      lib="libpocketplus.so";    ldflags="-Wl,-Bsymbolic-functions" ;;
esac

mkdir -p "$here/build"
echo "[cc] $lib"
cc -O2 -shared -fPIC $ldflags -I"$here/generated" \
   "$here/generated/pocketplus.c" "$here/src/pp_shim.c" \
   -o "$here/build/$lib"
echo "Built $here/build/$lib"

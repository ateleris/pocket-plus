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

# Lint flags. The codec is built as C99 (the standard the GenC output targets: it relies on
# variable-length arrays, which are optional in C11 and rejected by MSVC's cl) and warnings are
# errors so CI catches regressions. Keep this set in sync with native/pocketplus.vcxproj.
# -Wundef is deliberately absent: the generated header tests `#elif __GNUC__>=3`, undefined on MSVC.
std="-std=c99 -pedantic"
warn="-Wall -Wextra -Wshadow -Wstrict-prototypes -Wmissing-prototypes -Wpointer-arith -Wconversion -Werror"

# native/generated/ is machine-written and never hand-edited, so two warnings we cannot fix at the
# Scala source are waived for that file only; the hand-written shim is compiled fully strict.
#   -Wunused-variable: `main` needs a `given State` for StdOut.println that GenC emits but never uses
#   -Wparentheses:     GenC drops the parentheses the Scala already has around `a || (b && c)`
genc_waive="-Wno-unused-variable -Wno-parentheses"

mkdir -p "$here/build"
obj="$here/build"
cc="${CC:-cc}"
echo "[cc] pocketplus.c"
$cc $std $warn $genc_waive -O2 -fPIC -I"$here/generated" -c "$here/generated/pocketplus.c" -o "$obj/pocketplus.o"
echo "[cc] pp_shim.c"
$cc $std $warn -O2 -fPIC -I"$here/generated" -c "$here/src/pp_shim.c" -o "$obj/pp_shim.o"
echo "[ld] $lib"
$cc -shared $ldflags "$obj/pocketplus.o" "$obj/pp_shim.o" -o "$here/build/$lib"
echo "Built $here/build/$lib"

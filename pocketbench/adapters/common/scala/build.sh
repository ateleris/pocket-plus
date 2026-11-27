#!/usr/bin/env bash
# Compiles a JVM adapter with the Scala compiler bundled in the Stainless jar (so no sbt or Scala
# install is needed), then writes <build dir>/adapter, a launcher for <main class>.
# Usage: build.sh <stainless install dir> <build dir> <main class> <scala sources...>
set -euo pipefail

stainless="$(cd "$1" && pwd)"
build="$2"
main="$3"
shift 3

lib="$stainless/lib"
jar="$(echo "$lib"/stainless-dotty-standalone-*.jar)"
[ -f "$jar" ] || { echo "Stainless jar not found under $lib; run pocketplus/install.sh first." >&2; exit 1; }
cp="$jar:$lib/stainless-library.jar"

rm -rf "$build/classes"
mkdir -p "$build/classes"
# The flags Stainless's own frontend passes: qualified types are accepted and left to the verifier.
java -Xss512m -cp "$jar" dotty.tools.dotc.Main -color:never \
  -language:experimental.qualifiedTypes -language:experimental.qualifiedTypes.silent \
  -Yqualified-types-anf -classpath "$cp" -d "$build/classes" "$@"

classes="$(cd "$build/classes" && pwd)"
cat > "$build/adapter" <<EOF
#!/bin/sh
exec java -Xss1g -cp "$classes:$cp" $main "\$@"
EOF
chmod +x "$build/adapter"

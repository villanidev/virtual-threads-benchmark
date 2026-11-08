#!/usr/bin/env bash
# Builds a static (musl) native image of the baseline server with GraalVM CE.
# Usage: bench/native-build.sh   (also: ./run.sh native-build)  -> target/native/baseline (BaselineServer: vt-heap/vt-off/...)
# Env: OPT (-O level, default 3) LINK (musl = fully static, default | mostly = static except libc | dynamic) NI_HOME (default ~/.sdkman/candidates/java/25.0.2-graalce) NI_EXTRA BIN_SUFFIX (e.g. -mostly: binary name target/native/baseline<suffix>)
set -euo pipefail
cd "$(dirname "$0")/.."
GRAALVM_HOME=${NI_HOME:-$HOME/.sdkman/candidates/java/25.0.2-graalce}
NI="$GRAALVM_HOME/bin/native-image"
[ -x "$NI" ] || { echo "native-image not found in $GRAALVM_HOME"; exit 2; }
mvn -q compile
[ -f target/classpath.txt ] || mvn -q dependency:build-classpath -Dmdep.outputFile=target/classpath.txt >/dev/null
mkdir -p target/native

SDK=$HOME/.m2/repository/org/graalvm/sdk
SDK_CP="$SDK/nativeimage/25.0.2/nativeimage-25.0.2.jar:$SDK/word/25.0.2/word-25.0.2.jar"
MAIN=dev.villani.baseline.BaselineServer; OUT=baseline
CP="target/classes:bench/native:$(cat target/classpath.txt):$SDK_CP"
case ${LINK:-musl} in
  musl) LINK_FLAGS="--static --libc=musl";;
  mostly) LINK_FLAGS="--static-nolibc";;
  dynamic) LINK_FLAGS="";;
  *) echo "bad LINK ${LINK}"; exit 2;;
esac

# shellcheck disable=SC2086
"$NI" --no-fallback -O${OPT:-3} --gc=serial $LINK_FLAGS \
  --enable-native-access=ALL-UNNAMED \
  -Dsun.net.httpserver.nodelay=true \
  ${NI_EXTRA:-} \
  -cp "$CP" -o "target/native/$OUT${BIN_SUFFIX:-}" "$MAIN"
ls -l "target/native/$OUT${BIN_SUFFIX:-}"
file "target/native/$OUT${BIN_SUFFIX:-}" | cut -c1-160

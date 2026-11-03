#!/usr/bin/env bash
# Builds static (musl) native images of the servers with GraalVM CE.
# Usage: bench/native-build.sh [flat|baseline]   (also: ./run.sh native-build [flat|baseline])
#   flat     -> target/native/agendamento   (AgendamentoServer: loop-off, direct and ffm chosen at runtime by -Dflat.memory)
#   baseline -> target/native/baseline      (BaselineServer: vt-heap/vt-off/...)
# Env: OPT (-O level, default 3) LINK (musl = fully static, default | mostly = static except libc | dynamic) NI_HOME (default ~/.sdkman/candidates/java/25.0.2-graalce) NI_EXTRA BIN_SUFFIX (e.g. -mostly: binary name target/native/agendamento<suffix>)
set -euo pipefail
cd "$(dirname "$0")/.."
WHICH=${1:-flat}
GRAALVM_HOME=${NI_HOME:-$HOME/.sdkman/candidates/java/25.0.2-graalce}
NI="$GRAALVM_HOME/bin/native-image"
[ -x "$NI" ] || { echo "native-image not found in $GRAALVM_HOME"; exit 2; }
mvn -q compile
[ -f target/classpath.txt ] || mvn -q dependency:build-classpath -Dmdep.outputFile=target/classpath.txt >/dev/null
mkdir -p target/native

SDK=$HOME/.m2/repository/org/graalvm/sdk
SDK_CP="$SDK/nativeimage/25.0.2/nativeimage-25.0.2.jar:$SDK/word/25.0.2/word-25.0.2.jar"
case $WHICH in
  flat) MAIN=dev.villani.application.agendamento.AgendamentoServer; OUT=agendamento; CP=target/classes:bench/native:$SDK_CP;;
  baseline) MAIN=dev.villani.baseline.BaselineServer; OUT=baseline; CP="target/classes:bench/native:$(cat target/classpath.txt):$SDK_CP";;
  *) echo "bad target $WHICH"; exit 2;;
esac
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

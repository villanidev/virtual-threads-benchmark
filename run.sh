#!/usr/bin/env bash
# Usage: ./run.sh <server|repeat|jmh|native-build> ...   (see bench/ and the README)
set -euo pipefail
cd "$(dirname "$0")"
if [ "$1" = "server" ]; then
  # ./run.sh server <N> [vus]  -> real load test of the Agendamento server (see bench/server.sh)
  exec bash bench/server.sh "$2" "${3:-}"
fi
if [ "$1" = "repeat" ]; then
  # ./run.sh repeat [N] [reps] -> loop-off x direct/ffm/native repeated, median/min/max (see bench/repeat.sh)
  exec bash bench/repeat.sh "${2:-40000}" "${3:-5}"
fi
if [ "$1" = "jmh" ]; then
  # ./run.sh jmh [JMH args] -> access microbenchmark (Region direct/ffm, raw ByteBuffer, StructLayout) pinned to CPU 0
  mvn -q test-compile && mvn -q dependency:build-classpath -Dmdep.includeScope=test -Dmdep.outputFile=target/test-classpath.txt >/dev/null
  shift
  exec taskset -c 0 java -cp "target/test-classes:target/classes:$(cat target/test-classpath.txt)" --enable-native-access=ALL-UNNAMED org.openjdk.jmh.Main "${@:-AccessBench}"
fi
if [ "$1" = "native-build" ]; then
  exec bash bench/native-build.sh
fi
echo "usage: ./run.sh <server|repeat|jmh|native-build>"; exit 2

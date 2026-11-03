#!/usr/bin/env bash
# Usage: ./run.sh <cached|fixed|virtual|flat> <N> [cpu-list=0]
# Pins to 1 vCPU, 128m heap, JFR with allocation sampling. Output: jfr/<model>-<N>.jfr
set -euo pipefail
cd "$(dirname "$0")"
if [ "$1" = "server" ]; then
  # ./run.sh server <N> [vus]  -> real load test of the Agendamento server (see bench/server.sh)
  exec bash bench/server.sh "$2" "${3:-}"
fi
if [ "$1" = "repeat" ]; then
  # ./run.sh repeat [N] [reps] -> loop-off/vt-off x direct/ffm repeated, median/min/max (see bench/repeat.sh)
  exec bash bench/repeat.sh "${2:-40000}" "${3:-5}"
fi
if [ "$1" = "jmh" ]; then
  # ./run.sh jmh [JMH args] -> access microbenchmark (Region direct/ffm, raw ByteBuffer, StructLayout) pinned to CPU 0
  mvn -q test-compile && mvn -q dependency:build-classpath -Dmdep.includeScope=test -Dmdep.outputFile=target/test-classpath.txt >/dev/null
  shift
  exec taskset -c 0 java -cp "target/test-classes:target/classes:$(cat target/test-classpath.txt)" --enable-native-access=ALL-UNNAMED org.openjdk.jmh.Main "${@:-AccessBench}"
fi
if [ "$1" = "compare" ]; then
  # ./run.sh compare <N> [vus]  -> concurrency x memory matrix under the same k6 load (see bench/compare.sh)
  exec bash bench/compare.sh "$2" "${3:-}"
fi
MODEL=$1; N=$2; CPU=${3:-0}
mkdir -p jfr
[ -d target/classes/dev/villani/benchmark ] || mvn -q compile
TAG=${TAG:-}
# JAVA_EXTRA: extra JVM flags, e.g. JAVA_EXTRA=-XX:+UseCompactObjectHeaders TAG=coh
# shellcheck disable=SC2086
taskset -c "$CPU" java -Xmx128m -Xms128m -XX:+AlwaysPreTouch ${JAVA_EXTRA:-} \
  --enable-native-access=ALL-UNNAMED \
  -XX:StartFlightRecording=filename=jfr/${MODEL}-${N}${TAG:+-$TAG}.jfr,settings=profile,dumponexit=true \
  -cp target/classes dev.villani.benchmark.Bench "$MODEL" "$N" "${POOL:-500}" "${SLEEP_MS:-1000}" 2>&1 | grep -v -i -E 'jfr|flight'

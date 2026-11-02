#!/usr/bin/env bash
# Usage: ./run.sh <cached|fixed|virtual|flat> <N> [cpu-list=0]
# Pins to 1 vCPU, 128m heap, JFR with allocation sampling. Output: jfr/<model>-<N>.jfr
set -euo pipefail
cd "$(dirname "$0")"
MODEL=$1; N=$2; CPU=${3:-0}
mkdir -p jfr
[ -d target/classes/dev/villani ] || mvn -q compile
TAG=${TAG:-}
# JAVA_EXTRA: extra JVM flags, e.g. JAVA_EXTRA=-XX:+UseCompactObjectHeaders TAG=coh
# shellcheck disable=SC2086
taskset -c "$CPU" java -Xmx128m -Xms128m -XX:+AlwaysPreTouch ${JAVA_EXTRA:-} \
  --enable-native-access=ALL-UNNAMED \
  -XX:StartFlightRecording=filename=jfr/${MODEL}-${N}${TAG:+-$TAG}.jfr,settings=profile,dumponexit=true \
  -cp target/classes dev.villani.Bench "$MODEL" "$N" "${POOL:-500}" "${SLEEP_MS:-1000}" 2>&1 | grep -v -i -E 'jfr|flight'

#!/usr/bin/env bash
# Real load test of the flat Agendamento server.
# Usage: bench/server.sh <N=total requests per phase> [vus=50]   (also reachable as: ./run.sh server <N>)
# Load generator: k6 only (bench/k6/agendamento.js).
# Pinning: this CPU has SMT (cpu0 and cpu1 share a physical core), so the server uses CPU 0, CPU 1 stays idle
# and k6 runs on CPUs 2-3 (the other physical core).
# Env: RATE (0 = closed model; >0 = k6 constant-arrival-rate req/s) VUS (50) DELAY_MS (20) WARM (0 = no boot warm-up) PHASES (write,read,mixed) PORT (8080)
#      SERVER_CPU (0) LOAD_CPU (2-3) TAG JAVA_EXTRA
# Server: 1 vCPU, -Xmx128m -Xms128m, JFR profile + GC log. Load generator: k6 pinned via LOAD_CPU.
set -euo pipefail
cd "$(dirname "$0")/.."
N=${1:-1000}; VUS=${2:-${VUS:-50}}
PORT=${PORT:-8080}; TAG=${TAG:-}; NAME="server-${N}${TAG:+-$TAG}"
mkdir -p jfr bench/results
[ -d target/classes/dev/villani/application ] || mvn -q compile

# shellcheck disable=SC2086
taskset -c "${SERVER_CPU:-0}" java -Xmx128m -Xms128m -XX:+AlwaysPreTouch ${JAVA_EXTRA:-} \
  --enable-native-access=ALL-UNNAMED \
  -Xlog:gc:file=jfr/${NAME}.gc.log \
  -XX:StartFlightRecording=filename=jfr/${NAME}.jfr,settings=profile,dumponexit=true \
  -cp target/classes dev.villani.application.agendamento.AgendamentoServer \
  "$PORT" "${DELAY_MS:-20}" "${WARM:-0}" > "jfr/${NAME}.server.log" 2>&1 &
SERVER=$!
trap 'kill $SERVER 2>/dev/null || true' EXIT

# the real port only opens after the (optional) warm-up, so wait for the server's own "listening" line
for _ in $(seq 1 "${BOOT_TIMEOUT_S:-600}"); do
  grep -q "listening" "jfr/${NAME}.server.log" 2>/dev/null && break
  kill -0 "$SERVER" 2>/dev/null || { echo "server died during boot"; cat "jfr/${NAME}.server.log"; exit 1; }
  sleep 1
done

OUT=bench/results/${NAME}.txt
PHASES=${PHASES:-write,read,mixed}
{
  echo "# N=$N vus=$VUS delay=${DELAY_MS:-20}ms warm=${WARM:-0} rate=${RATE:-0} jopts=${JAVA_EXTRA:-} tool=k6 $(date -Is)"
  printf '%-6s %8s %5s %9s %9s %8s %8s %8s %8s\n' phase requests vus time_ms req/s p50_ms p90_ms p99_ms max_ms
  WRITTEN=0
  for PHASE in ${PHASES//,/ }; do
    taskset -c "${LOAD_CPU:-2-3}" k6 run --quiet --no-usage-report --no-color \
      -e PHASE="$PHASE" -e N="$N" -e VUS="$VUS" -e PORT="$PORT" -e WRITTEN="$WRITTEN" -e RATE="${RATE:-0}" -e TAG="$TAG" bench/k6/agendamento.js
    [ "$PHASE" = write ] && WRITTEN=$((WRITTEN + N))
    [ "$PHASE" = mixed ] && WRITTEN=$((WRITTEN + N / 5))
  done
  echo "server RSS (KB): $(awk '/VmRSS/{print $2}' /proc/$SERVER/status)  threads: $(awk '/Threads/{print $2}' /proc/$SERVER/status)"
} | tee "$OUT"

kill "$SERVER"
wait "$SERVER" 2>/dev/null || true
echo "GCs: $(grep -c 'Pause' "jfr/${NAME}.gc.log" || true)" | tee -a "$OUT"
echo "JFR: jfr/${NAME}.jfr"

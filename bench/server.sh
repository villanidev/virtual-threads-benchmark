#!/usr/bin/env bash
# Real load test of the flat Agendamento server.
# Usage: bench/server.sh <N=total requests per phase> [vus=50]   (also reachable as: ./run.sh server <N>)
# Load generator: k6 only (bench/k6/agendamento.js).
# Pinning: this CPU has SMT (cpu0 and cpu1 share a physical core), so the server uses CPU 0, CPU 1 stays idle
# and k6 runs on CPUs 2-3 (the other physical core).
# Env: RATE (0 = closed model; >0 = k6 constant-arrival-rate req/s) VUS (50) DELAY_MS (20) WARM (0 = no boot warm-up) PHASES (write,read,mixed) PORT (8080)
#      IMPL (unset = legacy flat run; loop-off|vt-heap|vt-off|fixed-heap|fixed-off|cached-heap|cached-off:
#      comparison mode, outputs go to results/compare/raw, NMT on) POOL (200, fixed models)
#      MEM_API (direct|ffm: off-heap backend, -Dflat.memory) PIN (cpu number: pins the loop thread via FFM sched_setaffinity, -Dflat.pin;
#      must be inside SERVER_CPU; loop-off only)
#      HEAP (128, MB) GC (unset = JVM default, serial on 1 vCPU; serial|zgc|g1|parallel: adds -XX:+Use<GC>GC and a name suffix)
#      SERVER_CPU (0) LOAD_CPU (2-3) TAG JAVA_EXTRA
#      NATIVE (1 = run target/native/{agendamento|baseline} instead of the JVM; needs IMPL; no JFR/NMT)
# Server: 1 vCPU, -Xmx<HEAP>m -Xms<HEAP>m, JFR profile + GC log. Load generator: k6 pinned via LOAD_CPU.
set -euo pipefail
cd "$(dirname "$0")/.."
N=${1:-1000}; VUS=${2:-${VUS:-50}}
PRETOUCH=${PRETOUCH-1}; MEM_API=${MEM_API:-direct}; PIN=${PIN:-}; HEAP=${HEAP:-128}; GC=${GC:-}; PORT=${PORT:-8080}; TAG=${TAG:-}; IMPL=${IMPL:-}; POOL=${POOL:-200}
if [ -n "$IMPL" ]; then
  OUT_DIR=${OUT_DIR_OVERRIDE:-results/compare/raw}; JFR_DIR=$OUT_DIR
  SUF=""; case $IMPL in fixed-*) SUF="-p$POOL";; esac
  [ "$HEAP" != 128 ] && SUF="$SUF-h$HEAP"
  [ -n "$GC" ] && SUF="$SUF-$GC"
  [ "$MEM_API" != direct ] && SUF="$SUF-$MEM_API"
  [ -n "$PIN" ] && SUF="$SUF-pin$PIN"
  [ -n "${NATIVE:-}" ] && SUF="$SUF-native${NATIVE_TAG:+-$NATIVE_TAG}"
  NAME="${IMPL}${SUF}-${N}${TAG:+-$TAG}"
else
  OUT_DIR=bench/results; JFR_DIR=jfr
  NAME="server-${N}${TAG:+-$TAG}"
fi
export OUT_DIR
mkdir -p "$JFR_DIR" "$OUT_DIR"
[ -d target/classes/dev/villani/application ] || mvn -q compile
JVM_FLAGS=""
case "$GC" in "") ;; serial) JVM_FLAGS="-XX:+UseSerialGC";; zgc) JVM_FLAGS="-XX:+UseZGC";; g1) JVM_FLAGS="-XX:+UseG1GC";; parallel) JVM_FLAGS="-XX:+UseParallelGC";; *) echo "bad GC $GC"; exit 2;; esac
JVM_FLAGS="$JVM_FLAGS -Dflat.memory=$MEM_API${PIN:+ -Dflat.pin=$PIN}"
MAIN=(dev.villani.application.agendamento.AgendamentoServer "$PORT" "${DELAY_MS:-20}" "${WARM:-0}")
CP=target/classes
if [ -n "$IMPL" ]; then
  JVM_FLAGS="$JVM_FLAGS -XX:NativeMemoryTracking=summary"
  if [ "$IMPL" != loop-off ]; then
    [ -f target/classpath.txt ] || mvn -q dependency:build-classpath -Dmdep.outputFile=target/classpath.txt >/dev/null
    CP="target/classes:$(cat target/classpath.txt)"
    # the JDK HttpServer leaves Nagle on by default: headers+body in two segments hit the 40 ms delayed ACK
    JVM_FLAGS="$JVM_FLAGS -Dsun.net.httpserver.nodelay=true"
    CONC=${IMPL%%-*}; MEM=${IMPL#*-}
    case $CONC in vt) CONC=VIRTUAL;; fixed) CONC=FIXED;; cached) CONC=CACHED;; *) echo "bad IMPL $IMPL"; exit 2;; esac
    case $MEM in heap) MEM=HEAP;; off) MEM=OFFHEAP;; *) echo "bad IMPL $IMPL"; exit 2;; esac
    MAIN=(dev.villani.baseline.BaselineServer "$PORT" "${DELAY_MS:-20}" "$CONC" "$MEM" "$POOL" "${WARM:-0}")
  fi
fi

BOOT_T0=$(date +%s%N)
if [ -n "${NATIVE:-}" ]; then
  # static native image (bench/native-build.sh): same arguments, no JFR/NMT/GC log; heap comes from -Xmx/-Xms
  # NATIVE_JFR=1: image built with NI_EXTRA=--enable-monitoring=jfr BIN_SUFFIX=-jfr; adds JFR and GC logging
  NI_FLAGS=""; JSUF=""
  [ -n "${NATIVE_JFR:-}" ] && { JSUF=-jfr; NI_FLAGS="-XX:+PrintGC -XX:+VerboseGC -XX:+FlightRecorder -XX:StartFlightRecording=filename=${JFR_DIR}/${NAME}.jfr,settings=profile,dumponexit=true"; }
  BIN=target/native/agendamento${BIN_SUFFIX:-}$JSUF; [ "$IMPL" = loop-off ] || BIN=target/native/baseline${BIN_SUFFIX:-}$JSUF
  [ -x "$BIN" ] || { echo "$BIN missing: run bench/native-build.sh"; exit 2; }
  # shellcheck disable=SC2086
  taskset -c "${SERVER_CPU:-0}" "$BIN" $NI_FLAGS -Xmx${HEAP}m -Xms${HEAP}m -Dflat.memory=$MEM_API ${PIN:+-Dflat.pin=$PIN} \
    -Dsun.net.httpserver.nodelay=true "${MAIN[@]:1}" > "${JFR_DIR}/${NAME}.server.log" 2>&1 &
else
# shellcheck disable=SC2086
taskset -c "${SERVER_CPU:-0}" java -Xmx${HEAP}m -Xms${HEAP}m ${PRETOUCH:+-XX:+AlwaysPreTouch} ${JAVA_EXTRA:-} $JVM_FLAGS \
  --enable-native-access=ALL-UNNAMED \
  -Xlog:gc:file=${JFR_DIR}/${NAME}.gc.log \
  -XX:StartFlightRecording=filename=${JFR_DIR}/${NAME}.jfr,settings=profile,dumponexit=true \
  -cp "$CP" "${MAIN[@]}" > "${JFR_DIR}/${NAME}.server.log" 2>&1 &
fi
SERVER=$!
trap 'kill $SERVER 2>/dev/null || true' EXIT

# the real port only opens after the (optional) warm-up, so wait for the server's own "listening" line
for _ in $(seq 1 "${BOOT_TIMEOUT_S:-600}"); do
  grep -q "listening" "${JFR_DIR}/${NAME}.server.log" 2>/dev/null && break
  kill -0 "$SERVER" 2>/dev/null || { echo "server died during boot"; cat "${JFR_DIR}/${NAME}.server.log"; exit 1; }
  sleep 1
done

BOOT_MS=$(( ($(date +%s%N) - BOOT_T0) / 1000000 ))
OUT=${OUT_DIR}/${NAME}.txt
PHASES=${PHASES:-write,read,mixed}
{
  echo "# impl=${IMPL:-loop-off} pool=$POOL N=$N vus=$VUS delay=${DELAY_MS:-20}ms warm=${WARM:-0} rate=${RATE:-0} mem=$MEM_API native=${NATIVE_TAG:-${NATIVE:-no}} pin=${PIN:-none} heap=$HEAP gc=${GC:-default} jopts=${JAVA_EXTRA:-} tool=k6 $(date -Is)"
  printf '%-6s %8s %5s %9s %9s %8s %8s %8s %8s\n' phase requests vus time_ms req/s p50_ms p90_ms p99_ms max_ms
  WRITTEN=0
  for PHASE in ${PHASES//,/ }; do
    taskset -c "${LOAD_CPU:-2-3}" k6 run --quiet --no-usage-report --no-color \
      -e PHASE="$PHASE" -e N="$N" -e VUS="$VUS" -e PORT="$PORT" -e WRITTEN="$WRITTEN" -e RATE="${RATE:-0}" -e TAG="$NAME" bench/k6/agendamento.js
    [ "$PHASE" = write ] && WRITTEN=$((WRITTEN + N))
    [ "$PHASE" = mixed ] && WRITTEN=$((WRITTEN + N / 5))
  done
  echo "# load-end $(date -Is)"
  echo "boot to listening (ms): $BOOT_MS  binary (KB): $( [ -n "${NATIVE:-}" ] && du -k "$BIN" | cut -f1 || echo jvm)"
  echo "server RSS (KB): $(awk '/VmRSS/{print $2}' /proc/$SERVER/status)  threads: $(awk '/Threads/{print $2}' /proc/$SERVER/status)"
  echo "server RssAnon (KB): $(awk '/RssAnon/{print $2}' /proc/$SERVER/status)  RssFile (KB): $(awk '/RssFile/{print $2}' /proc/$SERVER/status)"
  if [ -n "$IMPL" ] && [ -z "${NATIVE:-}" ]; then jcmd "$SERVER" VM.native_memory summary > "${JFR_DIR}/${NAME}.nmt.txt" 2>&1 || true; fi
} | tee "$OUT"

kill "$SERVER"
wait "$SERVER" 2>/dev/null || true
if [ -z "${NATIVE:-}" ]; then
  echo "GCs: $(grep -c 'Pause' "${JFR_DIR}/${NAME}.gc.log" || true)" | tee -a "$OUT"
  echo "JFR: ${JFR_DIR}/${NAME}.jfr"
fi

#!/usr/bin/env bash
# Repeats loop-off with the direct/ffm/native backends, R times each (interleaved so drift hits every cell equally),
# then aggregates median/min/max of the k6 p99 and the RSS. Raw outputs: results/repeat/raw (ignored by git).
# Usage: bench/repeat.sh [N=40000] [reps=5]     (also: ./run.sh repeat [N] [reps])
# Env: CELLS ("loop-off:direct loop-off:ffm"; cell = impl:mem[:native[:transport]], e.g. "loop-off:direct::uring" for io_uring on the JVM; "loop-off:native:musl|mostly" runs the static native image) SUMMARY (output file) RATE (2000) DELAY_MS (20) WARM (10000) LOAD_CPU (1-3) VUS (50)
set -euo pipefail
cd "$(dirname "$0")/.."
N=${1:-40000}; REPS=${2:-5}
CELLS=${CELLS:-"loop-off:direct loop-off:ffm"}
export RATE=${RATE:-2000} DELAY_MS=${DELAY_MS:-20} WARM=${WARM:-10000} LOAD_CPU=${LOAD_CPU:-1-3} VUS=${VUS:-50}
mvn -q compile
mkdir -p results/repeat
if [ "${KEEP:-0}" != 1 ]; then rm -f results/repeat/raw/*; fi
for r in $(seq 1 "$REPS"); do
  for cell in $CELLS; do
    echo "=== rep $r ${cell}"
    IFS=: read -r c_impl c_mem c_native c_transport <<<"$cell"
    IMPL=$c_impl MEM_API=$c_mem TRANSPORT=${c_transport:-nio} NATIVE=${c_native:+1} NATIVE_TAG=${c_native:-no} BIN_SUFFIX=$([ "${c_native:-}" = mostly ] && echo -mostly) TAG="r$r" OUT_DIR_OVERRIDE=results/repeat/raw bash bench/server.sh "$N" "$VUS" >/dev/null
  done
done
SUMMARY=${SUMMARY:-results/repeat/summary.md}
python3 bench/summarize_repeat.py "$N" > "$SUMMARY"
echo "summary: $SUMMARY"

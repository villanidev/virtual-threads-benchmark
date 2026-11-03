#!/usr/bin/env bash
# Step 12b: repeats loop-off and vt-off, direct vs ffm, R times each (interleaved so drift hits every cell equally),
# then aggregates median/min/max of the k6 p99 and the RSS. Raw outputs: results/repeat/raw (ignored by git).
# Usage: bench/repeat.sh [N=40000] [reps=5]     (also: ./run.sh repeat [N] [reps])
# Env: CELLS ("loop-off:direct loop-off:ffm vt-off:direct vt-off:ffm") RATE (2000) DELAY_MS (20) WARM (10000) LOAD_CPU (1-3) VUS (50)
set -euo pipefail
cd "$(dirname "$0")/.."
N=${1:-40000}; REPS=${2:-5}
CELLS=${CELLS:-"loop-off:direct loop-off:ffm vt-off:direct vt-off:ffm"}
export RATE=${RATE:-2000} DELAY_MS=${DELAY_MS:-20} WARM=${WARM:-10000} LOAD_CPU=${LOAD_CPU:-1-3} VUS=${VUS:-50}
mvn -q compile
mvn -q dependency:build-classpath -Dmdep.outputFile=target/classpath.txt >/dev/null
mkdir -p results/repeat
if [ "${KEEP:-0}" != 1 ]; then rm -f results/repeat/raw/*; fi
for r in $(seq 1 "$REPS"); do
  for cell in $CELLS; do
    echo "=== rep $r ${cell}"
    IMPL=${cell%%:*} MEM_API=${cell##*:} TAG="r$r" OUT_DIR_OVERRIDE=results/repeat/raw bash bench/server.sh "$N" "$VUS" >/dev/null
  done
done
python3 bench/summarize_repeat.py "$N" > results/repeat/summary.md
echo "summary: results/repeat/summary.md"

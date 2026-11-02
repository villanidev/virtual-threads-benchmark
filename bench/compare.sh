#!/usr/bin/env bash
# Step 11: same API, same k6 load, same 1 vCPU / 128m: concurrency model x memory layout.
# Usage: bench/compare.sh <N=requests per phase> [vus=50]    (also: ./run.sh compare <N> [vus])
# Env: IMPLS (space list, default: all) POOLS (pool sizes for fixed-*, default "200 16") RATE (2000; 0 = closed model)
#      DELAY_MS (20) WARM (10000) LOAD_CPU (1-3) TAG ; anything else is forwarded to bench/server.sh.
# Output: results/compare/raw/* (ignored by git) and results/compare/summary.md (versioned).
set -euo pipefail
cd "$(dirname "$0")/.."
N=${1:-60000}; VUS=${2:-${VUS:-50}}
IMPLS=${IMPLS:-"loop-off vt-heap vt-off fixed-heap fixed-off cached-heap cached-off"}
POOLS=${POOLS:-"200 16"}
export RATE=${RATE:-2000} DELAY_MS=${DELAY_MS:-20} WARM=${WARM:-10000} LOAD_CPU=${LOAD_CPU:-1-3}
mvn -q compile
mvn -q dependency:build-classpath -Dmdep.outputFile=target/classpath.txt >/dev/null
for impl in $IMPLS; do
  case $impl in
    fixed-*) for p in $POOLS; do echo "=== $impl pool=$p"; IMPL=$impl POOL=$p bash bench/server.sh "$N" "$VUS"; done ;;
    *) echo "=== $impl"; IMPL=$impl bash bench/server.sh "$N" "$VUS" ;;
  esac
done
python3 bench/summarize.py "$N" > results/compare/summary.md
echo "summary: results/compare/summary.md"

#!/usr/bin/env bash
# Usage: ./run.sh <server|repeat|compare|native-build> ...
# Baseline Agendamento server (JDK HttpServer): 1 vCPU, 128m heap, JFR, k6 load.
set -euo pipefail
cd "$(dirname "$0")"
case "${1:-}" in
  server)
    # ./run.sh server <N> [vus]  -> one load test; IMPL=vt-off|vt-heap|fixed-*|cached-* (see bench/server.sh)
    exec bash bench/server.sh "${2:-1000}" "${3:-}" ;;
  repeat)
    # ./run.sh repeat [N] [reps] -> vt-off/vt-heap repeated, median/min/max (see bench/repeat.sh)
    exec bash bench/repeat.sh "${2:-40000}" "${3:-5}" ;;
  compare)
    # ./run.sh compare <N> [vus] -> concurrency x memory matrix under the same k6 load (see bench/compare.sh)
    exec bash bench/compare.sh "${2:-60000}" "${3:-}" ;;
  native-build)
    # ./run.sh native-build -> static native image target/native/baseline (GraalVM CE, see bench/native-build.sh)
    exec bash bench/native-build.sh ;;
  *)
    echo "usage: ./run.sh <server|repeat|compare|native-build>"; exit 2 ;;
esac

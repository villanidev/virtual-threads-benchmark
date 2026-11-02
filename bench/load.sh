#!/usr/bin/env bash
# Load generator on a different CPU than the server (server uses CPU 0, wrk uses CPU 1).
# Usage: bench/load.sh <url> [connections=64] [duration=10s] [lua-script]
set -euo pipefail
URL=$1; CONN=${2:-64}; DUR=${3:-10s}; LUA=${4:-}
exec taskset -c "${LOAD_CPU:-1}" wrk -t1 -c"$CONN" -d"$DUR" --latency ${LUA:+-s "$LUA"} "$URL"

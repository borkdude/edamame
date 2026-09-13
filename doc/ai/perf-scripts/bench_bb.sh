#!/usr/bin/env bash
# usage: bench_bb.sh <bb-binary> [runs]
set -eo pipefail
bb_bin=$1
runs=${2:-10}
cd "$(dirname "$0")"
deps='{:mvn/local-repo "/tmp/m2" :deps {meander/epsilon {:mvn/version "0.0.650"}}}'
expr='(time (require (quote [meander.epsilon :as m])))'

"$bb_bin" -Sdeps "$deps" -e "$expr" > /dev/null 2>&1

for _ in $(seq 1 "$runs"); do
  "$bb_bin" -Sdeps "$deps" -e "$expr" 2>/dev/null | grep -o '[0-9.]* msecs'
done | awk '{print $1}' | sort -n |
  awk '{a[NR]=$1} END {printf "runs=%d min=%.1f median=%.1f max=%.1f ms\n", NR, a[1], a[int((NR+1)/2)], a[NR]}'

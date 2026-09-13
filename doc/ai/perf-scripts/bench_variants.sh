#!/usr/bin/env bash
# usage: bench_variants.sh [runs]
# Runs the meander require on each variant's bb, one run per variant in turn.
set -eo pipefail
export LC_ALL=C
W=/Users/borkdude/dev/babashka/.claude/worktrees
cd "$(dirname "$0")"
deps='{:mvn/local-repo "/tmp/m2" :deps {meander/epsilon {:mvn/version "0.0.650"}}}'
expr='(time (require (quote [meander.epsilon :as m])))'
variants="baseline hoist cache both"
runs=${1:-30}

rm -f times-*.txt
for v in $variants; do
  "$W/sci-parse-$v/bb" -Sdeps "$deps" -e "$expr" > /dev/null 2>&1
done

for _ in $(seq 1 "$runs"); do
  for v in $variants; do
    "$W/sci-parse-$v/bb" -Sdeps "$deps" -e "$expr" 2>/dev/null |
      grep -o '[0-9.]* msecs' | awk '{print $1}' >> "times-$v.txt"
  done
done

for v in $variants; do
  sort -n "times-$v.txt" |
    awk -v v="$v" '{a[NR]=$1} END {printf "%-8s runs=%d min=%.1f median=%.1f max=%.1f ms\n", v, NR, a[1], a[int((NR+1)/2)], a[NR]}'
done

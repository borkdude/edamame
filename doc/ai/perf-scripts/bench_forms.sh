#!/usr/bin/env bash
# usage: bench_forms.sh [rounds]
# load-string of 20000 cheap top-level forms on each variant's bb, one variant at a time in turn.
set -eo pipefail
export LC_ALL=C
W=/Users/borkdude/dev/babashka/.claude/worktrees
cd "$(dirname "$0")"
variants="baseline hoist cache both"
rounds=${1:-5}
expr='(def src (apply str (repeat 20000 ":k ")))
(dotimes [_ 3] (load-string src))
(let [t0 (System/nanoTime)]
  (dotimes [_ 5] (load-string src))
  (println (/ (- (System/nanoTime) t0) 1e6 5)))'

rm -f forms-*.txt
for _ in $(seq 1 "$rounds"); do
  for v in $variants; do
    "$W/sci-parse-$v/bb" -e "$expr" >> "forms-$v.txt"
  done
done

for v in $variants; do
  sort -n "forms-$v.txt" |
    awk -v v="$v" '{a[NR]=$1} END {printf "%-8s rounds=%d min=%.1f median=%.1f max=%.1f ms per load-string of 20000 forms\n", v, NR, a[1], a[int((NR+1)/2)], a[NR]}'
done

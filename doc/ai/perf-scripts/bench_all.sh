#!/usr/bin/env bash
S=/private/tmp/claude-501/-Users-borkdude-dev-edamame/6b2348e5-2ad1-44b5-935d-05ab86144387/scratchpad
W=/Users/borkdude/dev/edamame/.claude/worktrees
run() {
  label=$1; dir=$2
  echo "##### $label ($(git -C $dir log --oneline -1))"
  (cd $dir && clojure -Sdeps '{:deps {criterium/criterium {:mvn/version "0.4.6"}}}' -M $S/bench_fixes.clj 2>&1 | grep -E "===|Execution time mean")
}
run master $W/parse-fixes-base
for c in 1df95f3:fix1 75a277b:fix2 ece1c4b:fix3 1c50d94:fix4; do
  git -C $W/parse-fixes-verify checkout -q --detach ${c%%:*}
  run ${c##*:} $W/parse-fixes-verify
done
run master-again $W/parse-fixes-base

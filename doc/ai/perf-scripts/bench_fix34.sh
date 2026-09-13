#!/usr/bin/env bash
S=/private/tmp/claude-501/-Users-borkdude-dev-edamame/6b2348e5-2ad1-44b5-935d-05ab86144387/scratchpad
V=/Users/borkdude/dev/edamame/.claude/worktrees/parse-fixes-verify
for round in 1 2 3; do
  for c in ece1c4b:fix3 1c50d94:fix4; do
    git -C $V checkout -q --detach ${c%%:*}
    echo "##### round $round ${c##*:}"
    (cd $V && clojure -Sdeps '{:deps {criterium/criterium {:mvn/version "0.4.6"}}}' -M $S/bench_fixes.clj 2>&1 | grep -E "===|Execution time mean")
  done
done

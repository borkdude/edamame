# ClojureDart (cljd) port - status

Branch: `cljd`. Target: compile edamame to Dart so SCI can run on ClojureDart.

## Status: core parsing DONE, 32/38 tests pass

Run tests:

    clj -M:cljd test edamame.core-test

(Needs Dart SDK >= 3.0 and the `:cljd` alias in deps.edn, which uses a
local/root for tensegritics/clojuredart - adjust path or switch to a git sha.)

## What is cljd-specific

- `src/edamame/impl/reader_types.cljd` - self-contained string reader
  (indexing + pushback + char/number/util helpers) that replaces
  clojure.tools.reader on cljd. line/col are 1-based.
- `#?(:cljd ...)` reader-conditional arms across parser.cljc, core.cljc,
  syntax_quote.cljc, read_fn.cljc, macros.cljc.
- Tests: clj/cljs-only tests are gated with `#?(:cljd nil :clj ...)` so cljd
  test discovery skips them. core_test.cljc stays cross-platform.

## Works (all SCI needs)

numbers, strings, chars, keywords, symbols, colls, location metadata,
reader conditionals (incl :default), syntax-quote/unquote, namespaced maps,
deref/quote/var/fn-literals, regex via :regex, tagged literals, uneval,
:auto-resolve.

## Gaps (the 6 failing tests)

1. edn fallback `read` - STUBBED, throws ("edn fallback reader not supported").
   In reader_types.cljd. Only hit by `'` without :quote opt and some unhandled
   dispatch macros. SCI passes full opts so unlikely to hit. <- real gap
2. source-logging reader (:source opt) - STUBBED, throws. buf/log-source are
   no-ops. SCI default parsing does not use it. <- real gap
3. byte-array notation (byte/0) - unsupported, ignore for now.
4. ReaderConditional :preserve prints as map, not #?@(...) - cosmetic, needs
   IPrint impl on the cljd ReaderConditional.
5. (?i) inline regex flag - Dart RegExp limitation (test-side).
6. fn-test meta has extra :line/:column/:tag - cljd compiler leak, not edamame.

## Where to start next

If SCI needs them: implement #1 (edn fallback read) and #2 (source-logging)
in reader_types.cljd. Otherwise edamame is good enough for the SCI port.

See also the gotchas: cljd const-folds (Object.) -> use ^:unique; keywords
not reference-equal -> kw-identical? uses =; cljd ExceptionInfo is not a Dart
Exception (thrown? Exception won't catch ex-info); no array-map; StringBuffer
.write returns void.

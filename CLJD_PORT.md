# ClojureDart (cljd) port - status

Branch: `cljd`. Target: compile edamame to Dart so SCI can run on ClojureDart.

## Status: all 38 tests pass

Run tests:

    clj -M:cljd test edamame.core-test

(Needs Dart SDK >= 3.0 and the `:cljd` alias in deps.edn, which uses a
local/root for tensegritics/clojuredart - adjust path or switch to a git sha.)

## What is cljd-specific

- `src/edamame/impl/reader_types.cljd` - self-contained string reader
  (indexing + pushback + char/number/util helpers) that replaces
  clojure.tools.reader on cljd. line/col are 1-based. Includes source
  logging (readers know their string + index, so :source is a subs)
  and a ReaderConditional deftype with IPrint for :preserve printing.
- `#?(:cljd ...)` reader-conditional arms across parser.cljc, core.cljc,
  syntax_quote.cljc, read_fn.cljc, macros.cljc.
- `reader-types/list` replaces `cljd.core/list` in parser, read-fn and
  syntax-quote via `:refer-clojure :exclude`. Upstream cljd bug: the `()`
  literal inside `cljd.core/list` carries const-folded compiler meta
  (:line 3436 :tag PersistentList etc) which leaks into every list it
  builds. Worth reporting to ClojureDart.
- Tests: clj/cljs-only tests are gated with `#?(:cljd nil ...)` so cljd
  test discovery skips them. Cljd-specific shields in core_test.cljc:
  - quote-test: `'` without :quote opt needs the edn fallback reader.
  - edge-cases-test: Dart RegExp has no inline `(?i)` flag, uses `[Ii]`.
  - thrown? assertions use cljd.core/ExceptionInfo since cljd ex-info
    is not a Dart Exception.

## Works (all SCI needs)

numbers, strings, chars, keywords, symbols, colls, location metadata,
reader conditionals (incl :default and :preserve printing), syntax-quote,
namespaced maps, deref/quote/var/fn-literals, regex via :regex, tagged
literals, uneval, :auto-resolve, :source (source-logging reader).

## Remaining gap

edn fallback `read` in reader_types.cljd - STUBBED, throws. Only hit by
`'` without the :quote opt and some unhandled dispatch macros. SCI passes
full opts so unlikely to hit. Implement if SCI turns out to need it.

See also the gotchas: cljd const-folds (Object.) -> use ^:unique; keywords
not reference-equal -> kw-identical? uses =; cljd ExceptionInfo is not a Dart
Exception (thrown? Exception won't catch ex-info); no array-map; StringBuffer
.write returns void; defrecord always emits IPrint so a custom -print needs
deftype; cljd.core/list leaks const meta (see above).

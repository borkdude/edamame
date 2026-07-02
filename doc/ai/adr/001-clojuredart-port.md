# ClojureDart (cljd) port - status

Branch: `cljd`. Target: compile edamame to Dart so SCI can run on ClojureDart.

## Status: all 38 tests pass

Run tests:

    clj -M:cljd test edamame.core-test

(Needs Dart SDK >= 3.0. `:cljd` pins ClojureDart to a git sha and runs in
CI via `script/test/cljd`. `:cljd-local` uses the local checkout at
~/dev/ClojureDart for hacking on ClojureDart itself.)

## What is cljd-specific

- Two cljd-only namespaces replace clojure.tools.reader, split so SCI
  can require the reader types directly (as it does with tools.reader):
  - `src/edamame/impl/cljd_reader_types.cljd` - self-contained
    string-backed indexing push-back reader, line/col 1-based, with
    source logging (readers know their string + index, so :source is a
    subs). Stands in for clojure.tools.reader.reader-types: the `r` and
    `rt` aliases in parser.cljc and core.cljc point here on cljd, and
    SCI's parser should too.
  - `src/edamame/impl/cljd_shim.cljd` - the rest of the tools.reader
    surface: char/number reading, edn fallback, ns-keys, ReaderConditional
    deftype with IPrint for :preserve printing. The `edn`, `i`, `utils`
    and `commons` aliases in parser.cljc point here on cljd.
  Call sites stay untouched on all platforms.
- `#?(:cljd ...)` reader-conditional arms across parser.cljc, core.cljc,
  syntax_quote.cljc, read_fn.cljc, macros.cljc.
- `cljd-shim/list` replaces `cljd.core/list` in parser, read-fn and
  syntax-quote via `:refer-clojure :exclude`. Upstream cljd bug: the `()`
  literal inside `cljd.core/list` carries const-folded compiler meta
  (:line 3436 :tag PersistentList etc) which leaks into every list it
  builds. Worth reporting to ClojureDart.
- Tests: clj/cljs-only tests are gated with `#?(:cljd nil ...)` so cljd
  test discovery skips them. Cljd-specific shields in core_test.cljc:
  - edge-cases-test: Dart RegExp has no inline `(?i)` flag, uses `[Ii]`.
  - thrown? assertions use cljd.core/ExceptionInfo since cljd ex-info
    is not a Dart Exception.
  - array-map-test: gated off. ClojureDart has no PersistentArrayMap, so
    all maps are hash maps and small map literals lose insertion order.
    ACCEPTED: order is cosmetic for SCI, callers needing it can pass an
    ordered map constructor via the :map opt. Real fix belongs upstream.

## Works (all SCI needs)

numbers, strings, chars, keywords, symbols, colls, location metadata,
reader conditionals (incl :default and :preserve printing), syntax-quote,
namespaced maps, deref/quote/var/fn-literals, regex via :regex, tagged
literals, uneval, :auto-resolve, :source (source-logging reader), edn
fallback. No known gaps.

## edn fallback

`read` in cljd_shim.cljd is a minimal tools.reader.edn stand-in. The
fallback is only reachable in two cases: `'` without the :quote opt
(quote is a symbol constituent in edn, reads as a symbol) and dispatch
macros disabled by opts (throws "No dispatch macro"). All other dispatch
chars are handled by parse-sharp before the catch-all.

`cljd.reader` (in the ClojureDart repo) was considered as the fallback:
it is a full ClojureDart reader with sync read-string and chunked stream
support, but it tracks no line/col so it cannot replace edamame parsing,
and its semantics diverge from tools.reader.edn (`'foo` reads as
`(quote foo)` instead of the symbol `'foo`), which would make cljd
behave differently from clj/cljs.

See also the gotchas: cljd const-folds (Object.) -> use ^:unique; keywords
not reference-equal -> kw-identical? uses =; cljd ExceptionInfo is not a Dart
Exception (thrown? Exception won't catch ex-info); no array-map; StringBuffer
.write returns void; defrecord always emits IPrint so a custom -print needs
deftype; cljd.core/list leaks const meta (see above).

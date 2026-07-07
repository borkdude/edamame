# Speed up JVM parsing with a single-pass string reader and record-field ctx

Date: 2026-07-07

## Status

Accepted, extraction in progress.

## Where to resume

The full implementation (all decisions below) lives on the `perf` branch:
https://github.com/borkdude/edamame/tree/perf . It is based on the v1.6.41
master, before the item-2 extraction, so re-derive its diff against current
master before extracting further.

Extraction order and per-change measurements are in the "Extraction order"
section. General JVM tricks and the verification recipes are in the
`clojure-performance` skill.

Done so far:

- Item 2 (record-field ctx + `Delims` + key renames): merged to master in
  https://github.com/borkdude/edamame/pull/146 . Measured in isolation off
  master: ~10% faster on JVM, ~4% on ClojureScript.

## Context

Parsing clojure/core.clj (697 top level forms) with sci's options took 8.33ms
on the JVM, 1.8x slower than clojure.core/read. Profiling (clj-async-profiler,
collapsed stacks) showed:

- ~30% of CPU in reader plumbing: three stacked tools.reader readers
  (IndexingPushbackReader over PushbackReader over StringReader), with
  StringReader using `(nth s pos)` which goes through the generic
  `RT.nthFrom` instead of `.charAt`.
- `Options` record: keys that are not declared fields live in `__extmap`,
  a hash map. `:auto-resolve-ns` was looked up on every parse-next call and
  `::expected-delimiter`/`::opened-delimiter` were assoc'ed per collection.
  Each assoc of a non-field key copies the record and rebuilds the extmap.
- Per-node overhead in parse-next: a `Loc` record allocated per form,
  `vary-meta` with a closure, a `(not location?)` test per node, and a meta
  lookup per collection element for read-cond splicing.

Benchmark profiles used throughout:

- sci opts: `{:all true :row-key :line :col-key :column :read-cond :allow
  :location? seq? :end-location false}`
- default opts: `{:all true :auto-resolve-ns true}`

## Decision

1. Add `IndexingStringReader`, a JVM-only deftype replacing the three-reader
   tools.reader stack. It reads with `.charAt`, keeps line/column bookkeeping
   in primitive unsynchronized-mutable fields, and mirrors tools.reader
   semantics exactly, including `\return\newline` normalization and unread
   behavior. It implements the tools.reader protocols inline so
   `indexing-reader?`, the `edn/read` fallback, and user code keep working.
2. Add fast paths on that reader behind an `IFastOps` interface:
   - `readToken`: a token cannot contain a newline, so it is a substring of
     the source plus a column increment. No StringBuilder, no per-char
     protocol calls, and the terminator is not read-then-unread.
   - `readString`: strings without escapes and without `\return` are
     substrings. One scan finds the closing quote and counts newlines.
   - `skipWhitespace`: primitive char loop with inline `\r\n` handling.
3. Declare every hot ctx key as an `Options` record field. Rename the
   internal keys `::expected-delimiter`, `::opened-delimiter` and
   `::fn-literal` to unqualified keywords, since namespaced keywords can
   never be record fields. `:map` is deliberately not a field: on
   ClojureDart a record field named `map` conflicts with Dart's `Map.map`.
4. Store the expected and opened delimiter in one `Delims` record in a
   single ctx field. Entering a collection costs one record copy instead of
   two plus a map allocation.
5. parse-next: two int locals instead of a `Loc` record per form,
   `with-meta` with direct assocs instead of `vary-meta` plus closure, and
   `:location?` defaulted to `(fn [_] true)` in normalize-opts so the
   per-node `(not location?)` test disappears.
6. dispatch: `identical?` instead of `=` for the `\#` check. Valid on the
   JVM and JS because ASCII chars are cached/interned, kept `=` on
   ClojureDart where chars are strings.
7. Gate the read-cond splicing meta check in parse-to-delimiter on
   `:read-cond` being set.

## Consequences

- sci opts: 8.33ms -> 3.68ms (2.26x). Default opts: 10.05ms -> 4.81ms.
- edamame now parses core.clj 1.25x faster than clojure.core/read and 2.5x
  faster than tools.reader with an indexing reader, both measured with
  line/col metadata and `:read-cond :allow`.
- The internal ctx keys are now unqualified (`:delims`, `:fn-literal`).
  They were never documented, but code reaching into the ctx breaks.
- `normalize-opts` now always returns a `:location?` function.
- cljs, ClojureDart and ClojureCLR keep the tools.reader stack via reader
  conditionals. All four test suites pass, plus sci's suite against this
  branch via `:local/root`.
- The `:test` alias moved from Clojure 1.9.0 to 1.10.0.

## Extraction order

This branch is the staging ground, the changes will be extracted separately.
Ranked by measured impact on the sci opts benchmark. Percentages are of the
8.33ms baseline, from the incremental measurements during the session, not
remeasured in isolation:

1. `IndexingStringReader` deftype replacing the tools.reader stack: -2.7ms,
   -33%. Self-contained apart from `string-reader` and `reader` wiring.
2. `Options` record fields for hot ctx keys plus the internal key renames
   (`:delims`, `:fn-literal`): -0.55ms fields, -0.4ms `Delims` and int
   `case`, together -11%. The `Delims` record depends on the key rename,
   extract together. DONE, merged in #146 (measured ~10% off master;
   the int `case` stayed with item 3 since it lives inside `readToken`).
3. `readToken` fast path: -0.37ms, -4.5%. Depends on 1.
4. `skipWhitespace` fast path and the deftype loop-lift restructuring:
   -0.35ms, -4%. Depends on 1.
5. `readString` fast path plus parse-next cleanups (no `Loc` per form,
   `with-meta` instead of `vary-meta`, `:location?` default fn): -0.2ms,
   -2% on sci opts, -9% on default opts since end locations double the
   savings. The parse-next cleanups are independent of 1.
6. Small, unmeasured individually: `identical?` for `\#` in dispatch,
   read-cond gate in parse-to-delimiter.

On the size of `IndexingStringReader`: the reader core (read-char,
peek-char, unread, line/col fields) is ~60 lines mirroring tools.reader and
delivers item 1 on its own. The `IFastOps` fast paths (items 3-5) are
another ~120 lines for a combined ~11%. If the code size is not worth it,
extract the reader core without `IFastOps` and drop items 3-5, `read-token`,
`parse-string*` and `skip-whitespace` fall back to the generic loops
unchanged. The differential test against the tools.reader stack is the
safety net either way.

## Notes for future performance work

### Measuring

- criterium via `clojure -Sdeps '{:deps {criterium/criterium {:mvn/version
  "0.4.6"}}}' -M bench.clj`, run from the project root or the project
  deps.edn is silently dropped.
- clj-async-profiler needs `-J-Djdk.attach.allowAttachSelf` before `-M` and
  cannot run in the Claude sandbox (JVM self-attach uses a unix socket).
- `(prof/stop {:generate-flamegraph? false})` returns collapsed stacks in
  /tmp/clj-async-profiler/results. Aggregate leaf frames with awk, find a
  hot frame's callers by grepping the collapsed lines.

### Verification

- Differential test: parse tricky cases plus all of core.clj with the new
  reader and with the tools.reader stack, compare forms including all
  metadata. Normalize regex Patterns to strings and ignore syntax-quote
  gensym numbering, which differs per run.
- ClojureDart: `bb test:cljd`, needs the sandbox off because dart writes
  telemetry under ~/.dart-tool.
- ClojureCLR locally: `dotnet tool install --global Clojure.Main --version
  1.12.3-alpha3` and `Clojure.Cljr --version 0.1.0-alpha8`. Clojure.Main
  1.11.0 cannot load `.cljr` files. CLR tools.deps has no `:mvn` coord
  support, so run with `CLJ_CONFIG` pointed at an empty dir when
  ~/.clojure/deps.edn contains mvn deps.

### Traps hit

- Unbounded `String.indexOf`: scanning for a char that never occurs walks to
  the end of the input. Used per string literal this made parsing O(n^2)
  and *slower* than the baseline. indexOf has no end bound, use a charAt
  loop for bounded scans.
- A `loop` in expression position inside a deftype method compiles to a
  closure allocated per call, visible as `IndexingStringReader$fn__NNNN`
  leaf frames. Keep loops in tail position, use an extra interface method
  for shared finish code.
- "Must assign primitive to primitive mutable": loop results in binding
  position are boxed, coerce with `(long x)` before `set!`.
- The `whitespace?` :clj macro hints its arg `^Character`, passing a
  primitive char fails to compile. Use `Character/isWhitespace` and int
  compares on primitives.
- ClojureDart compiles a .cljc twice: the Dart pass reads with feature set
  `#{:cljd}`, the host macro pass with `#{:cljd :cljd/clj-host :clj}`
  (cljd compiler.cljc, load-input/host-load-input). The first matching
  branch in form order wins, so `#?(:clj (deftype ...))` without an earlier
  `:cljd` branch is host-compiled against the cljd shim requires and fails.
  Put a `:cljd` branch before `:clj`, or use `:cljd/clj-host` to address
  only the host pass. `:default` alone is not enough.
- sci's resolve-test opens a network connection to www.clojure.org and
  errors in a sandbox without network. Not an edamame regression.

General JVM Clojure tricks extracted to the `clojure-performance` skill.

# Speed up JVM parsing with a single-pass string reader and record-field ctx

Date: 2026-07-07, updated 2026-09-13

## Status

Parked. All parse changes together make bb load big libraries about 5%
faster: native bb, meander require ~211ms -> ~201ms, estimated from the
separate measurements below. Parsing is only ~25ms of that load, the rest is
sci analysis and evaluation. Not worth the risk of the large reader change.
Bugs and small low-risk fixes found along the way get extracted separately.

## Branches

edamame (borkdude/edamame):

- `perf`: the original staging branch with every change from the Decision
  section, based on v1.6.41.
- `unwrapped-reader`: `IndexingStringReader` with the `IFastOps` fast paths
  on current master, plus `test/edamame/string_reader_test.cljc`.
- `parse-fixes`: parser fixes that do not need the reader, see "Parser fixes
  without the reader".
- `perf-notes`: this ADR and the scripts in `doc/ai/perf-scripts`.

sci (babashka/sci):

- `edamame-string-reader`: `eval-string*`, `load-string` and `load-string*`
  read strings through `edamame.core/reader` on the JVM. No effect without
  `unwrapped-reader`.
- `parse-opts-once`: `load-reader*` and `eval-string*` build the edamame
  options once per load instead of per form.
- `parse-opts-cache`, `parse-opts-once-cache`: an identity cache in
  `parse-next`, measured no better than `parse-opts-once`.
- `edamame-reader`: the reader switch plus timers printed with
  `SCI_PARSE_STATS=1`, used by the bb experiment.

babashka (babashka/babashka):

- `edamame-reader`: bb on sci `edamame-reader` with edamame
  `1.6.44-unwrapped-reader-SNAPSHOT`. That jar only existed in a local
  ~/.m2: `lein do clean, install` on `unwrapped-reader` with that version in
  `resources/EDAMAME_VERSION`.

Merged: item 2 of the extraction order (record-field ctx, `Delims`, key
renames) in https://github.com/borkdude/edamame/pull/146 , ~10% faster on the
JVM, ~4% on ClojureScript.

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

The `perf` branch is the staging ground, the changes are extracted
separately. Ranked by measured impact on the sci opts benchmark. Percentages
are of the 8.33ms baseline, from the incremental measurements during the
session, not remeasured in isolation:

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

## Unwrapped reader on current master (2026-09-13)

Branch `unwrapped-reader`: items 1, 3, 4 and the `readString` part of 5.

- core.clj against the tools.reader stack: sci opts 8.87ms -> 4.56ms,
  default opts 10.02ms -> 6.76ms.
- `test/edamame/string_reader_test.cljc` compares the reader with the
  tools.reader stack on 10000 random sequences of reader ops (read, peek,
  unread, skip-whitespace, read-token, read-string, line and column after
  each op), on random parser input under four option sets, and on
  clojure.core. The inputs include non-ASCII characters.
- 12846 real-world files (95MB from bb, sci, clj-kondo, rewrite-clj, nbb and
  ~/.gitlibs) parse identically with both readers, incl. metadata and errors.
- `e/reader` on a string returns `edamame.impl.parser.IndexingStringReader`
  instead of tools.reader's `IndexingPushbackReader`.
- The `skipWhitespace` fast path on `perf` miscounts lines when the input
  ends in `\r\n` or `\r\f`. tools.reader reads the end of input after the
  pair and counts it as one more newline, the fast path skipped that read.
  The random differential test found it. Fixed on `unwrapped-reader`.
- `readString` on `perf` does not update `prev-column` for a newline inside
  a string. Only a second unread can observe it. Fixed on `unwrapped-reader`.

## Effect on bb (2026-09-13)

All native bb builds from the same bb commit, meander 0.0.650.

- sci builds its own tools.reader stack in `eval-string*` and `load-string*`,
  so bb only gets the new reader once sci reads strings through
  `edamame.core/reader` (sci `edamame-string-reader`).
- Unwrapped reader plus the sci switch: meander require (median of 10)
  211ms -> 203ms. Parsing all 27 meander sources in bb 22.5ms -> 13.2ms.
- With the timers from sci `edamame-reader` (`SCI_PARSE_STATS=1`): of the
  204ms require, sci `parse-next` takes 18ms and edamame `parse-next` 12.7ms,
  over 952 forms. The other ~186ms is analysis and evaluation.
- sci `parse-opts-once`, with edamame 1.6.43: load-string of 20000 cheap
  forms 17.4ms -> 9.6ms, meander require 206.8ms -> 205.5ms, about 0.4
  microseconds per form. The identity cache variants measured the same. The
  18ms vs 12.7ms gap above is mostly not the options, what else sits between
  sci and edamame `parse-next` is unknown.
- The JVM JIT removes most per-form allocation cost that native image pays.
  Measure sci changes in a native build.

## Parser fixes without the reader (2026-09-13)

Branch `parse-fixes` off master. fast-edn parses a 2.4MB EDN file 6.8x
faster than edamame with `unwrapped-reader`. A profile of edamame on that file
spread the time over number parsing (tools.reader's regex `match-number`), map
literal construction (`take-nth`, `apply distinct?`, `apply array-map`),
per-form work in `parse-next` and `dispatch`, and symbol and keyword scanning.

Cumulative JVM numbers against master (average of two master runs):

| Commit | EDN, no location | EDN, location | core.clj, sci opts | core.clj, default opts |
|---|---|---|---|---|
| master | 95.2ms | 109.8ms | 7.76ms | 9.57ms |
| Numbers without regex (JVM only) | 84.3ms | 98.0ms | 7.89ms | 9.40ms |
| Map literals via a transient | 72.3ms | 85.1ms | 7.81ms | 9.17ms |
| Per-form overhead in `parse-next` and `dispatch` | 73.9ms | 79.5ms | 7.41ms | 9.16ms |

A fourth commit that scanned symbol and keyword tokens once measured within
the run-to-run spread over three rounds and is reverted on the branch.

Verification: a snapshot records a sha256 of the normalized parse result for
12846 corpus files and 8000 seeded random inputs under three option sets.
Normalized means metadata as data, number and map classes, array map entry
order, and error class, message and data. The branch matches master on every
entry except four that also differ between two master runs.

## Notes for future performance work

### Scripts

`doc/ai/perf-scripts` holds the scripts from the 2026-09-13 session. They
contain absolute paths to a session scratch directory, adjust before reuse.

- `snapshot.clj`: sha256 of the normalized parse result per input and option
  set, for a corpus file list plus seeded random inputs. Diff the output of
  two branches line by line.
- `snapshot_dump.clj`, `debug_input.clj`: print the full normalized result
  for chosen entries, to find what differs.
- `corpus_diff.clj`: parse a file list with the tools.reader stack and with
  `e/reader`, report differences. Build the list with `find` and `-prune`,
  `file-seq` walks into node_modules, target and symlink loops.
- `bench_reader.clj`, `bench_fixes.clj`: criterium JVM benchmarks on core.clj
  and a generated 2.4MB EDN string. `bench_edn.clj` adds clojure.edn and
  fast-edn.
- `profile_edn.clj`: clj-async-profiler on the EDN string and core.clj.
- `bench_sci_parse.clj`: sci parse options per form vs once, JVM.
- `bench_bb.sh`, `bench_variants.sh`, `bench_forms.sh`: native bb meander
  require and load-string of many forms, variants run in turn.
- `bench_all.sh`, `bench_fix34.sh`: benchmark a list of commits one after
  another.

### Measuring

- criterium via `clojure -Sdeps '{:deps {criterium/criterium {:mvn/version
  "0.4.6"}}}' -M bench.clj`, run from the project root or the project
  deps.edn is silently dropped.
- clj-async-profiler needs `-J-Djdk.attach.allowAttachSelf` before `-M` and
  cannot run in the Claude sandbox (JVM self-attach uses a unix socket).
- `(prof/stop {:generate-flamegraph? false})` returns collapsed stacks in
  /tmp/clj-async-profiler/results. Aggregate leaf frames with awk, find a
  hot frame's callers by grepping the collapsed lines.
- Benchmarks next to native image builds or test suites are noise. Start
  them after everything else finished, and run the variants in turn.
- ClojureScript: compile to a file with `cljs.main -t nodejs -O simple -o
  out.js -c <ns>` and run it with node. `cljs.main -re node` hangs in the
  sandbox.
- 2.4MB EDN, JVM: clojure.edn 57.1ms, fast-edn 8.5ms, edamame with
  `unwrapped-reader` 57.9ms without location metadata and 68.3ms with it.

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
- A parse snapshot has to be deterministic before it can compare branches.
  Diff two runs of the same code first. Noise came from identity hash codes
  printed by `str` on arbitrary objects, and from the iteration order of hash
  maps whose keys hash by identity, like regex literals.
- edamame leaks its internal eof sentinel at the end of input. `` ` `` then
  `@` returns a form containing it, `#?(` throws "Feature should be a keyword:
  java.lang.Object@...", and `#` then `~` puts it in a "No reader function for
  tag" message. Fixed in https://github.com/borkdude/edamame/pull/153 .
- In a fresh checkout of sci or edamame without pubspec.yaml,
  `script/test/cljd` runs `clojure -M:cljd init`, which overwrites the
  tracked README.md, CHANGELOG.md and .gitignore with Dart templates. Running
  it next to sci's node suite in the same checkout fails node tests that read
  README.md.

General JVM Clojure tricks extracted to the `clojure-performance` skill.

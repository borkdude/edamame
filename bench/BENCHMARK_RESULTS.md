# Syntax-Quote Optimization Benchmark Results

## Implementation

Branch: https://github.com/yenda/edamame/tree/optimize-syntax-quote

The optimization avoids `concat`/`sequence`/`seq` scaffolding when no `~@` (unquote-splicing) is present, producing direct collection literals instead.

## Form Size Comparison

| Form | Upstream (chars) | Optimized (chars) | Reduction |
|------|------------------|-------------------|-----------|
| `` `[] `` | 83 | 2 | 97.6% |
| `` `[1 2 3 4 5] `` | 193 | 11 | 94.3% |
| `` `[[1 2] [3 4]] `` | 527 | 19 | 96.4% |
| `` `{:a 1 :b 2} `` | 243 | 18 | 92.6% |
| `` `[{:a 1} {:b 2}] `` | 605 | 22 | 96.4% |
| `` `[1 ~x 3] `` | 149 | 7 | 95.3% |
| `` `[1 ~@xs 2] `` | 130 | 130 | 0% (expected) |

## Runtime Eval Benchmark (criterium)

Benchmarks the cost of evaluating the generated code at runtime (not parsing).

| Form | Upstream | Optimized | Speedup |
|------|----------|-----------|---------|
| `` `[] `` | 44-78 ns | 8-9 ns | **5-9x** |
| `` `[1 2 3 4 5] `` | 598 ns | 21 ns | **28x** |
| `` `[[1 2] [3 4]] `` | 576-590 ns | 18-26 ns | **22-33x** |
| `` `{:a 1 :b 2} `` | 418-446 ns | 15-16 ns | **26-28x** |
| `` `[{:a 1} {:b 2}] `` | 539-543 ns | 18 ns | **30x** |
| `` `[1 ~x 3] `` | 435-448 ns | 36 ns | **12x** |
| `` `[1 ~@xs 2] `` | 407 ns | 321-381 ns | ~same |

## Parse Benchmark (criterium)

Parse time is essentially unchanged (~121 µs vs ~123 µs for 18 forms) since both versions do similar parsing work.

## How to Run

```bash
# Eval benchmark
clj -Sdeps '{:paths ["src" "resources" "bench"] :deps {org.clojure/clojure {:mvn/version "1.12.0"} org.clojure/tools.reader {:mvn/version "1.5.2"} criterium/criterium {:mvn/version "0.4.6"}}}' -M -m eval-bench

# Parse benchmark
clj -Sdeps '{:paths ["src" "resources" "bench"] :deps {org.clojure/clojure {:mvn/version "1.12.0"} org.clojure/tools.reader {:mvn/version "1.5.2"} criterium/criterium {:mvn/version "0.4.6"}}}' -M -m syntax-quote-bench
```

## Test Results

All 57 existing tests pass, plus 14 new tests for the optimization covering:
- Empty collections, literals, nested structures
- Unquote without splice, quoted symbols
- Eval equivalence, size reduction
- Metadata preservation, gensym, map types

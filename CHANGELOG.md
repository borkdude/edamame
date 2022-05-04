# Changelog

For a list of breaking changes, check [here](#breaking-changes)

## 1.0.0

- Fix #76: nested fn literals not allowed

## 0.0.19

- Improve error message when reading invalid symbol

## 0.0.18

- Small performance improvement

## 0.0.17

- Fix bug introduced in 0.0.16 by change in issue #86

## 0.0.16

- Support reading with `PushbackReader` which is not an indexing reader [#86](https://github.com/borkdude/edamame/issues/86)

## 0.0.15

- Fix reading of `##Inf` with `clojure.lang.LineNumberingPushbackReader` [#85](https://github.com/borkdude/edamame/issues/85)

## 0.0.14

- `1@2` should be parsed as `1 (clojure.core/deref 2)`

## 0.0.13

- `foo@bar` should be parsed as `foo (clojure.core/deref bar)` [#83](https://github.com/borkdude/edamame/issues/83)

## 0.0.12

- Revert [#70](https://github.com/borkdude/edamame/issues/70), instead return `cljs.tagged-literals/JSValue` object which should
  be evaluated manually.

## 0.0.11

- Expose expected delimiter as public data in exception [#81](https://github.com/borkdude/edamame/issues/81)
- Return array-based map when c <= 16 [#78](https://github.com/borkdude/edamame/issues/78)
- `:end-location` option [#75](https://github.com/borkdude/edamame/issues/75)
- Auto-resolved map fails for current ns [#74](https://github.com/borkdude/edamame/issues/74)
- `location?` predicate [#72](https://github.com/borkdude/edamame/issues/72)
- Handle whitespace after reader conditional splice [#71](https://github.com/borkdude/edamame/issues/71)
- Fix parsing of trailing uneval in reader conditional [#65](https://github.com/borkdude/edamame/issues/65)
- Return JS array when using #js [1 2 3] [#70](https://github.com/borkdude/edamame/issues/70)
- Expose `iobj?` fn
- Upgrade to tools.reader `1.3.4`
- Support capturing source string [#67](https://github.com/borkdude/edamame/issues/67)
- Expose parse-next [#5](https://github.com/borkdude/edamame/issues/5)
- Allow value for `:readers` to be a map or a function
- Throw EOF while reading with single quote [#61](https://github.com/borkdude/edamame/issues/61)
- Fix issue with stack depth and comment parsing
- preserve metadata by allowing postprocess function [#52](https://github.com/borkdude/edamame/issues/52)
- Support parsing Clojure code in tagged literals [#50](https://github.com/borkdude/edamame/issues/50)
- Preserve metadata on anonymous function body
- allow function in `:read-cond` [#47](https://github.com/borkdude/edamame/issues/47)
- fully qualify unquote
- fix line numbers when using shebang [#48](https://github.com/borkdude/edamame/issues/48)

## Breaking changes

### 0.0.12

- Revert [#70](https://github.com/borkdude/edamame/issues/70), instead return `cljs.tagged-literals/JSValue` object which should
  be evaluated manually.

### 0.0.10

- Function literals now expand into a call to `fn*` instead of `fn`

### 0.0.8

- Options to tools.reader, like `:readers` for passing reader tag functions, now
have to be passed as `:tools.reader/opts` in the options.
- The `:dispatch` option has been deprecated. It still works in 0.0.8 but is now undocumented and planned to be removed in a future version.

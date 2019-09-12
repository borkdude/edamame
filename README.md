# Edamame

EDN parser with location metadata and pluggable dispatch table.

[![CircleCI](https://circleci.com/gh/borkdude/edamame/tree/master.svg?style=shield)](https://circleci.com/gh/borkdude/edamame/tree/master)
[![Clojars Project](https://img.shields.io/clojars/v/borkdude/edamame.svg)](https://clojars.org/borkdude/edamame)
[![cljdoc badge](https://cljdoc.org/badge/borkdude/edamame)](https://cljdoc.org/d/borkdude/edamame/CURRENT)

## Rationale

This library can be useful when:

- You want to include locations in feedback about EDN files.
- You want to parse Clojure-like expressions and want to add support for unsupported EDN characters.

This library came out of [sci](https://github.com/borkdude/sci), a small Clojure interpreter.

## Features

- Parse EDN values with location as metadata.
- Pluggable dispatch table to extend EDN.

This library works with:

- Clojure on the JVM
- ClojureScript

## Usage

``` clojure
(require '[edamame.core :refer [parse-string]])
```

Locations are attached as metadata:

``` clojure
(def s "
[{:a 1}
 {:b 2}]")
(map meta (parse-string s))
;;=>
({:row 2, :col 2}
 {:row 3, :col 2})

(->> "{:a {:b {:c [a b c]}}}"
     parse-string
     (tree-seq coll? #(if (map? %) (vals %) %))
     (map meta))
;;=>
({:row 1, :col 1}
 {:row 1, :col 5}
 {:row 1, :col 9}
 {:row 1, :col 13}
 {:row 1, :col 14}
 {:row 1, :col 16}
 {:row 1, :col 18})
```

Dispatch on a character, even if it's unsupported in EDN:

``` clojure
(parse-string "@foo" {:dispatch {\@ (fn [val] (list 'deref val))}})
;;=> (deref foo)
```

Dispatch on dispatch characters:

``` clojure
(parse-string "#\"foo\"" {:dispatch {\# {\" #(re-pattern %)}}})
;;=> #"foo"

(parse-string "#(inc 1 2 %)" {:dispatch {\# {\( (fn [expr] (read-string (str "#" expr)))}}})
;;=> (fn* [p1__11574#] (inc 1 2 p1__11574#))
```

## Test

    script/test/jvm
    script/test/node
    script/test/all

## Status

Experimental. Breaking changes are expected to happen at this phase.

## Installation

Use as a dependency:

[![Clojars Project](https://img.shields.io/clojars/v/borkdude/edamame.svg)](https://clojars.org/borkdude/edamame)

## Credits

The code is largely inspired by
[rewrite-clj](https://github.com/xsc/rewrite-clj) and derived projects.

## License

Copyright © 2019 Michiel Borkent

Distributed under the Eclipse Public License 1.0. This project contains code
from Clojure and ClojureScript which are also licensed under the EPL 1.0. See
LICENSE.

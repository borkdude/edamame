# Edamame

Configurable EDN parser with location metadata.

[![CircleCI](https://circleci.com/gh/borkdude/edamame/tree/master.svg?style=shield)](https://circleci.com/gh/borkdude/edamame/tree/master)
[![Clojars Project](https://img.shields.io/clojars/v/borkdude/edamame.svg)](https://clojars.org/borkdude/edamame)
[![cljdoc badge](https://cljdoc.org/badge/borkdude/edamame)](https://cljdoc.org/d/borkdude/edamame/CURRENT)

## Rationale

This library can be useful when:

- You want to include locations in feedback about EDN files.
- You want to parse Clojure-like expressions without any evalution.

This library came out of [sci](https://github.com/borkdude/sci), a small Clojure interpreter.

## Features

- Parse EDN values with location as metadata.
- Is able to parse Clojure code without evaluation
- Configurable

This library works with:

- Clojure on the JVM
- ClojureScript

## Usage

``` clojure
(require '[edamame.core :refer [parse-string]])
```

### Location metadata

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

### Parser options

Edamame's API consists of two functions: `parse-string` which parses a the first
form from a string and `parse-string-all` which parses all forms from a
string. Both functions take the same options. See the docstring of
`parse-string` for all the options.

Examples:

``` clojure
(parse-string "@foo" {:deref true})
;;=> (deref foo)

(parse-string "#(* % %1 %2)" {:fn true})
;;=> (fn [%1 %2] (* %1 %1 %2))

(parse-string "#=(+ 1 2 3)" {:read-eval true})
;;=> (read-eval (+ 1 2 3))

(parse-string "#\"foo\"" {:regex true})
;;=> #"foo"

(parse-string "`(+ 1 2 3 ~x ~@y)" {:syntax-quote true :unquote true :unquote-splicing true})
;;=> (syntax-quote (+ 1 2 3 (unquote x) (unquote-splicing y)))

(parse-string "#'foo" {:var true})
;;=> (var foo)

(parse-string "#(alter-var-root #'foo %)" {:all true})
;;=> (fn [%1] (alter-var-root (var foo) %1))
```

Note that standard behavior is overridable with functions:

``` clojure
(parse-string "#\"foo\"" {:regex #(list 're-pattern %)})
(re-pattern "foo")
```

Process reader conditionals:

``` clojure
(parse-string "[1 2 #?@(:cljs [3 4])]" {:features #{:cljs} :read-cond :allow})
;;=> [1 2 3 4]

(parse-string "[1 2 #?@(:cljs [3 4])]" {:features #{:cljs} :read-cond :preserve})
;;=> [1 2 #?@(:cljs [3 4])]
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

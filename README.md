# Edamame

Configurable EDN/Clojure parser with location metadata.

[![CircleCI](https://circleci.com/gh/borkdude/edamame/tree/master.svg?style=shield)](https://circleci.com/gh/borkdude/edamame/tree/master)
[![Clojars Project](https://img.shields.io/clojars/v/borkdude/edamame.svg)](https://clojars.org/borkdude/edamame)
<!--[![cljdoc badge](https://cljdoc.org/badge/borkdude/edamame)](https://cljdoc.org/d/borkdude/edamame/CURRENT)-->

## Rationale

This library can be useful when:

- You want to include locations in feedback about EDN files.
- You want to parse Clojure-like expressions without any evalution.

This library came out of [sci](https://github.com/borkdude/sci), a small Clojure interpreter.

## Features

- Parse EDN values with location as metadata.
- Parse Clojure code without evaluation
- Configurable

This library works with:

- Clojure on the JVM
- GraalVM compiled binaries
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
({:row 2, :col 2, :end-row 2, :end-col 8}
 {:row 3, :col 2, :end-row 3, :end-col 8})

(->> "{:a {:b {:c [a b c]}}}"
     parse-string
     (tree-seq coll? #(if (map? %) (vals %) %))
     (map meta))
;;=>
({:row 1, :col 1, :end-row 1, :end-col 23}
 {:row 1, :col 5, :end-row 1, :end-col 22}
 {:row 1, :col 9, :end-row 1, :end-col 21}
 {:row 1, :col 13, :end-row 1, :end-col 20}
 {:row 1, :col 14, :end-row 1, :end-col 15}
 {:row 1, :col 16, :end-row 1, :end-col 17}
 {:row 1, :col 18, :end-row 1, :end-col 19})
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

(parse-string "'bar" {:quote true})
;;=> (quote bar)

(parse-string "#(* % %1 %2)" {:fn true})
;;=> (fn [%1 %2] (* %1 %1 %2))

(parse-string "#=(+ 1 2 3)" {:read-eval true})
;;=> (read-eval (+ 1 2 3))

(parse-string "#\"foo\"" {:regex true})
;;=> #"foo"

(parse-string "#'foo" {:var true})
;;=> (var foo)

(parse-string "#(alter-var-root #'foo %)" {:all true})
;;=> (fn [%1] (alter-var-root (var foo) %1))
```

Syntax quoting can be enabled using the `:syntax-quote` option. Symbols are
resolved to fully qualified symbols using `:resolve-symbol` which is set to
`identity` by default:

``` clojure
(parse-string "`(+ 1 2 3 ~x ~@y)" {:syntax-quote true})
;;=> (clojure.core/sequence (clojure.core/seq (clojure.core/concat (clojure.core/list (quote +)) (clojure.core/list 1) (clojure.core/list 2) (clojure.core/list 3) (clojure.core/list x) y)))

(parse-string "`(+ 1 2 3 ~x ~@y)" {:syntax-quote {:resolve-symbol #(symbol "user" (name %))}})
;;=> (clojure.core/sequence (clojure.core/seq (clojure.core/concat (clojure.core/list (quote user/+)) (clojure.core/list 1) (clojure.core/list 2) (clojure.core/list 3) (clojure.core/list x) y)))
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

(let [res (parse-string "#?@(:bb 1 :clj 2)" {:read-cond identity})]
  (prn res) (prn (meta res)))
;;=> (:bb 1 :clj 2)
;;=> {:row 1, :col 1, :end-row 1, :end-col 18, :edamame/read-cond-splicing true}
```

Auto-resolve keywords:

``` clojure
(parse-string "[::foo ::str/foo]" {:auto-resolve '{:current user str clojure.string}})
;;=> [:user/foo :clojure.string/foo]
```

To create options from a namespace in the process where edamame is called from:

``` clojure
(defn auto-resolves [ns]
  (as-> (ns-aliases ns) $
    (assoc $ :current (ns-name *ns*))
    (zipmap (keys $)
            (map ns-name (vals $)))))

(require '[clojure.string :as str]) ;; create example alias

(auto-resolves *ns*) ;;=> {str clojure.string, :current user}

(parse-string "[::foo ::str/foo]" {:auto-resolve (auto-resolves *ns*)})
;;=> [:user/foo :clojure.string/foo]
```

Passing data readers:

``` clojure
(parse-string "#js [1 2 3]" {:readers {'js (fn [v] (list 'js v))}})
(js [1 2 3])
```

<!--
Postprocess read values:

``` clojure
(defrecord Wrapper [obj loc])

(defn iobj? [x]
  #?(:clj (instance? clojure.lang.IObj x)
     :cljs (satisfies? IWithMeta x)))

(parse-string "[1]" {:postprocess
                       (fn [{:keys [:obj :loc]}]
                         (if (iobj? obj)
                           (vary-meta obj merge loc)
                           (->Wrapper obj loc)))})

[#user.Wrapper{:obj 1, :loc {:row 1, :col 2, :end-row 1, :end-col 3}}]
```

This allows you to preserve metadata for objects that do not support carrying
metadata. When you use a `:postprocess` function, it is your responsibility to
attach location metadata.
-->
## Test

For the node tests, ensure clojure is installed as a command line tool as shown [here](https://clojure.org/guides/getting_started#_installation_on_mac_via_homebrew). For the JVM tests you will require [leiningen](https://leiningen.org/) to be installed. Then run the following:

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

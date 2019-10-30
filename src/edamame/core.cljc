(ns edamame.core
  (:require
   [edamame.impl.parser :as p]))

(defn parse-string
  "Parses first EDN value from string.

  Supported parsing options:

  `:deref`: parse forms starting with `@`. If `true`, the resulting
  expression will be parsed as `(deref expr)`.

  `:fn`: parse function literals (`#(inc %)`). If `true`, ... TODO

  `:regex`: parse regex literals (`#\"foo\"`). If `true`, defaults to
  `re-pattern`.

  `:var`: parse var literals (`#'foo`). If `true`, the resulting
  expression will be parsed as `(var foo)`.

  `:read-eval`: parse read-eval (`=(+ 1 2 3)`). If `true`, the
  resulting expression will be parsed as `(read-eval (+ 1 2 3))`.

  `:syntax-quote`: parse syntax-quote (`(+ 1 2 3)`). If `true`, the
  resulting expression will be parsed as `(syntax-quote (+ 1 2 3))`.

  `:syntax-unquote`: parse syntax-unquote (`~(+ 1 2 3)`). If `true`,
  the resulting expression will be parsed as `(syntax-unquote (+ 1 2
  3))`.

  `:syntax-unquote`: parse syntax-unquote-splice (`~@[1 2 3]`). If `true`,
  the resulting expression will be parsed as `(syntax-unquote-splice [1 2 3])`.

  Supported options for processing reader conditionals:

  `:read-cond`: - `:allow` to process reader conditionals, or
                  `:preserve` to keep all branches
  `:features`: - persistent set of feature keywords for reader conditionals (e.g. `#{:clj}`).

  `:dispatch`: DEPRECATED by parser option.

  Additional arguments to tools.reader may be passed with
  `:tools.reader/opts`, like `:readers` for passing reader tag functions."
  ([s]
   (p/parse-string s nil))
  ([s opts]
   (p/parse-string s opts)))

(defn parse-string-all
  "Like parse-string but parses all values from string and returns them
  in a vector."
  ([s]
   (p/parse-string-all s nil))
  ([s opts]
   (p/parse-string-all s opts)))

;;;; Scratch

(comment
  (parse-string "(1 2 3 #_4)"))

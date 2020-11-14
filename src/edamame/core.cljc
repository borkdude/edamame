(ns edamame.core
  (:require
   [edamame.impl.parser :as p]))

;; undocumented, still alpha, only used by @kwrooijen at the moment:
;; `:postprocess`: a function that will be called with a map containing
;; `:obj`, the read value, and `:loc`, the location metadata. This can
;; be used to handle objects that cannot carry metadata differently. If
;; this option is provided, attaching location metadata is not
;; automatically added to the object.

(defn parse-string
  "Parses first EDN value from string.

  Supported parsing options:

  `:deref`: parse forms starting with `@`. If `true`, the resulting
  expression will be parsed as `(deref expr)`.

  `:fn`: parse function literals (`#(inc %)`). If `true`, will be parsed as `(fn [%1] (inc %))`.

  `:quote`: parse quoted expression `'foo`. If `true`, will be parsed as `(quote foo)`.

  `:read-eval`: parse read-eval (`=(+ 1 2 3)`). If `true`, the
  resulting expression will be parsed as `(read-eval (+ 1 2 3))`.

  `:regex`: parse regex literals (`#\"foo\"`). If `true`, defaults to
  `re-pattern`.

  `:syntax-quote`: parse syntax-quote (`(+ 1 2 3)`). Symbols get
  qualified using `:resolve-symbol` which defaults to `identity`:
  `(parse-string \"`x\" {:syntax-quote {:resolve-symbol #(symbol \"user\" (str %))}})
  ;;=> (quote user/x)`.

  `:var`: parse var literals (`#'foo`). If `true`, the resulting
  expression will be parsed as `(var foo)`.

  `:all`: when `true`, the above options will be set to `true` unless
  explicitly provided.

  Supported options for processing reader conditionals:

  `:read-cond`: - `:allow` to process reader conditionals, or
                  `:preserve` to keep all branches
  `:features`: - persistent set of feature keywords for reader conditionals (e.g. `#{:clj}`).

  `:auto-resolve`: map of alias to namespace symbols for
  auto-resolving keywords. Use `:current` as the alias for the current
  namespace.

  `:readers`: data readers.

  Additional arguments to tools.reader may be passed with
  `:tools.reader/opts`, like `:readers` for passing reader tag functions.
  "
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

(defn reader
  "Coerces x into indexing pushback-reader to be used with
  parse-next. Accepts: string or java.io.Reader."
  [x]
  (p/reader x))

(defn get-line-number [reader]
  (p/get-line-number reader))

(defn get-column-number [reader]
  (p/get-column-number reader))

(defn normalize-opts [opts]
  (p/normalize-opts opts))

(defn parse-next
  "Parses next form from reader. Accepts same opts as parse-string, must
  be normalized with normalize-opts first."
  ([reader] (parse-next reader {}))
  ([reader opts]
   (let [v (p/parse-next opts reader)]
     (if (#?(:clj identical? :cljs keyword-identical?) :edamame.impl.parser/eof v)
       (or (get opts :eof)
           ::eof)
       v))))

;;;; Scratch

(comment
  (parse-string "(1 2 3 #_4)"))

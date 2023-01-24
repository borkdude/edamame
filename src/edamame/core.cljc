(ns edamame.core
  (:require
   [clojure.tools.reader.reader-types :as rt]
   [edamame.impl.parser :as p]
   [clojure.string :as str]))

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

  `:postprocess`: a function that is called with a map containing
  `:obj`, the read value, and `:loc`, the location metadata. This can
  be used to handle objects that cannot carry metadata differently. If
  this option is provided, attaching location metadata is not
  automatically added to the object.

  `:location?`: a predicate that is called with the parsed
  object. Should return a truthy value to determine if location
  information will be added.

  Additional arguments to tools.reader may be passed with
  `:tools.reader/opts`, like `:readers` for passing reader tag functions.
  "
  ([s]
   (p/parse-string s nil))
  ([s opts]
   (p/parse-string s opts)))

(defn parse-string-all
  "Like `parse-string` but parses all values from string and returns them
  in a vector."
  ([s]
   (p/parse-string-all s nil))
  ([s opts]
   (p/parse-string-all s opts)))

(defn reader
  "Coerces x into indexing pushback-reader to be used with
  parse-next. Accepts string or `java.io.Reader`"
  [x]
  (p/reader x))

(defn source-reader
  "Coerces x into source-logging-reader to be used with
  parse-next. Accepts string or `java.io.Reader`"
  [x]
  (p/source-logging-reader x))

(defn get-line-number [reader]
  (p/get-line-number reader))

(defn get-column-number [reader]
  (p/get-column-number reader))

(defn normalize-opts
  "Expands `opts` into normalized opts, e.g. `:all true` is expanded
  into explicit options."
  [opts]
  (p/normalize-opts opts))

(defn parse-next
  "Parses next form from reader. Accepts same opts as `parse-string`,
  but must be normalized with `normalize-opts` first."
  ([reader] (parse-next reader (p/normalize-opts {})))
  ([reader normalized-opts]
   (when (rt/source-logging-reader? reader)
     (let [^StringBuilder buf (p/buf reader)]
       #?(:clj (.setLength buf 0)
          :cljs (.clear buf))))
   (let [v (p/parse-next normalized-opts reader)]
     (if (identical? p/eof v)
       (or (get normalized-opts :eof)
           ::eof)
       v))))

(defn parse-next+string
  "Parses next form from reader. Accepts same opts as `parse-string`,
  but must be normalized with `normalize-opts` first.
  Returns read value + string read (whitespace-trimmed)."
  ([reader] (parse-next+string reader (p/normalize-opts {})))
  ([reader normalized-opts]
   (if (rt/source-logging-reader? reader)
     (let [v (parse-next reader normalized-opts)
           s (str/trim (str (p/buf reader)))]
       [v s])
     (throw (ex-info "parse-next+string must be called with source-reader" {})))))

(defn iobj?
  "Returns true if obj can carry metadata."
  [obj]
  #?(:clj
     (instance? clojure.lang.IObj obj)
     :cljs (satisfies? IWithMeta obj)))

;;;; Scratch

(comment
  (parse-string "(1 2 3 #_4)"))

(ns edamame.impl.parser
  "This code is largely inspired by rewrite-clj(sc), so thanks to all
  who contribured to those projects."
  {:no-doc true}
  (:require
   #?(:clj  [clojure.tools.reader.edn :as edn]
      :cljs [cljs.tools.reader.edn :as edn])
   #?(:clj  [clojure.tools.reader.reader-types :as r]
      :cljs [cljs.tools.reader.reader-types :as r])
   #?(:clj  [clojure.tools.reader.impl.inspect :as i]
      :cljs [cljs.tools.reader.impl.inspect :as i])
   #?(:clj [clojure.tools.reader.impl.utils :refer [namespace-keys]]
      :cljs [cljs.tools.reader.impl.utils :refer [reader-conditional namespace-keys]])
   #?(:clj [clojure.tools.reader.impl.commons :as commons]
      :cljs [cljs.tools.reader.impl.commons :as commons])
   #?(:cljs [cljs.tagged-literals :as cljs-tags])
   #?(:clj [clojure.edn :as cedn])
   [clojure.string :as str]
   [edamame.impl.read-fn :refer [read-fn]]
   [edamame.impl.syntax-quote :refer [syntax-quote]])
  #?(:clj (:import [java.io Closeable]))
  #?(:cljs (:import [goog.string StringBuffer])))

#?(:clj (set! *warn-on-reflection* true))

;;;; tools.reader

;; This is used for reading tokens (numbers, strings and symbols). We might inline this
;; later, but for now we're falling back on the EDN reader.
(defn edn-read [ctx #?(:cljs ^not-native reader :default reader)]
  (let [tools-reader-opts (:tools.reader/opts ctx)]
    #?(:clj (if (instance? java.io.PushbackReader reader)
              ;; workaround for TRDR-63
              (cedn/read (or tools-reader-opts {}) reader)
              (edn/read tools-reader-opts reader)))
    #?(:cljs (edn/read tools-reader-opts reader))))

(defn dispatch-macro? [ch]
  (contains? #{\^  ;; deprecated
               \'
               \(
               \{
               \"
               \!
               \_
               \?
               \:
               \#} ch))

(def read-token #'edn/read-token)
(def parse-symbol #'commons/parse-symbol)

;;;; end tools.reader

(defn location [#?(:cljs ^not-native reader :default reader)]
  {:row (r/get-line-number reader)
   :col (r/get-column-number reader)})

(defn kw-identical? [kw v]
  (#?(:clj identical? :cljs keyword-identical?) kw v))

(declare parse-next)

(defn parse-comment
  [#?(:cljs ^not-native reader :default reader)]
  (r/read-line reader)
  reader)

(defn whitespace?
  [#?(:clj ^java.lang.Character c :default c)]
  #?(:clj (and c (or (= c \,) (Character/isWhitespace c)))
     :cljs (and c (< -1 (.indexOf #js [\return \newline \tab \space ","] c)))))

(defn skip-whitespace
  "Skips whitespace. Returns reader. If end of stream is reached, returns nil."
  [_ctx #?(:cljs ^not-native reader :default reader)]
  (loop []
    (when-let [c (r/read-char reader)]
      (if (whitespace? c)
        (recur)
        (do (r/unread reader c)
            reader)))))

(defn throw-reader
  "Throw reader exception, including line line/column. line/column is
  read from the reader but it can be overriden by passing loc
  optional parameter."
  ([ctx #?(:cljs ^:not-native reader :default reader) msg]
   (throw-reader ctx reader msg nil))
  ([ctx #?(:cljs ^:not-native reader :default reader) msg data]
   (throw-reader ctx reader msg data nil))
  ([ctx #?(:cljs ^:not-native reader :default reader) msg data loc]
   (let [c (:col loc (r/get-column-number reader))
         l (:row loc (r/get-line-number reader))]
     (throw
      (ex-info msg
               (merge {:type :edamame/error
                       (:row-key ctx) l
                       (:col-key ctx) c} data))))))

(def non-match ::nil)

(defn non-match? [v]
  (kw-identical? v non-match))

(defn throw-eof-while-reading [ctx reader]
  (throw-reader ctx reader "EOF while reading"))

(defn parse-to-delimiter
  ([ctx #?(:cljs ^not-native reader :default reader) delimiter]
   (parse-to-delimiter ctx reader delimiter []))
  ([ctx #?(:cljs ^not-native reader :default reader) delimiter into]
   (let [row (r/get-line-number reader)
         col (r/get-column-number reader)
         opened (r/read-char reader)
         ctx (assoc ctx
                    ::expected-delimiter delimiter
                    ::opened-delimiter {:char opened :row row :col col})]
     (loop [vals (transient into)]
       (let [;; if next-val is uneval, we get back the expected delimiter...
             next-val (parse-next ctx reader)
             cond-splice? (some-> next-val meta ::cond-splice)]
         (cond
           (kw-identical? ::eof next-val)
           (throw-reader ctx
            reader
            (str "EOF while reading, expected " delimiter " to match " opened " at [" row "," col "]"))
           (kw-identical? ::expected-delimiter next-val)
           (persistent! vals)
           cond-splice? (do (doseq [v next-val]
                              (conj! vals v))
                            (recur vals))
           (non-match? next-val) (recur vals)
           :else
           (recur (conj! vals next-val))))))))

(defn parse-list [ctx #?(:cljs ^not-native reader :default reader)]
  (apply list (parse-to-delimiter ctx reader \))))

(defn read-regex-pattern
  "Modeled after tools.reader/read-regex."
  [ctx #?(:cljs ^not-native reader :default reader)]
  (r/read-char reader) ;; ignore leading double-quote
  (let [sb #?(:clj (StringBuilder.)
              :cljs (StringBuffer.))]
    (loop [ch (r/read-char reader)]
      (if (identical? \" ch)
        #?(:clj (str sb)
           :cljs (str sb))
        (if (nil? ch)
          (throw-reader ctx reader "Error while parsing regex")
          (do
            (.append sb ch )
            (when (identical? \\ ch)
              (let [ch (r/read-char reader)]
                (when (nil? ch)
                  (throw-reader ctx reader "Error while parsing regex"))
                (.append sb ch)))
            (recur (r/read-char reader))))))))

(defn- duplicate-keys-error [msg coll]
  ;; https://github.com/clojure/tools.reader/blob/97d5dac9f5e7c04d8fe6c4a52cd77d6ced560d76/src/main/cljs/cljs/tools/reader/impl/errors.cljs#L233
  (letfn [(duplicates [seq]
            (for [[id freq] (frequencies seq)
                  :when (> freq 1)]
              id))]
    (let [dups (duplicates coll)]
      (apply str msg
             (when (> (count dups) 1) "s")
             ": " (interpose ", " dups)))))

(defn throw-dup-keys
  [ctx #?(:cljs ^not-native reader :default reader) loc kind ks]
  (throw-reader
   ctx reader
   (duplicate-keys-error
    (str (str/capitalize (name kind)) " literal contains duplicate key")
    ks)
   nil
   loc))

(defn parse-set
  [ctx #?(:cljs ^not-native reader :default reader)]
  (let [start-loc (location reader)
        coll (parse-to-delimiter ctx reader \})
        the-set (set coll)]
    (when-not (= (count coll) (count the-set))
      (throw-dup-keys ctx reader start-loc :set coll))
    the-set))

(defn parse-first-matching-condition [ctx #?(:cljs ^not-native reader :default reader)]
  (let [features (:features ctx)]
    (loop [match non-match]
      (skip-whitespace ctx reader)
      (let [end? (= \) (r/peek-char reader))]
        (if end?
          (do (r/read-char reader) ;; ignore closing \)
              match)
          (let [k (parse-next ctx reader)
                match? (and (non-match? match)
                            (or (contains? features k)
                                (kw-identical? k :default)))]
            (if match? (recur (parse-next ctx reader))
                (do
                  (parse-next (assoc ctx ::suppress true)
                              reader)
                  (recur match)))))))))

(defn parse-reader-conditional [ctx #?(:cljs ^not-native reader :default reader)]
  (skip-whitespace ctx reader)
  (let [opt (:read-cond ctx)
        splice? (= \@ (r/peek-char reader))]
    (when splice? (r/read-char reader))
    (cond (kw-identical? :preserve opt)
          (reader-conditional (parse-next ctx reader) splice?)
          (fn? opt)
          (opt (vary-meta
                (parse-next ctx reader)
                assoc :edamame/read-cond-splicing splice?))
          :else
          (do
            (r/read-char reader) ;; skip \(
            (let [match (parse-first-matching-condition ctx reader)]
              (cond (non-match? match) reader
                    splice? (vary-meta match
                                       #(assoc % ::cond-splice true))
                    :else match))))))

(defn get-auto-resolve
  ([ctx reader next-val]
   (get-auto-resolve ctx reader next-val nil))
  ([ctx reader next-val msg]
   (if-let [v (:auto-resolve ctx)]
     v
     (throw-reader ctx reader
                   (or msg "Use `:auto-resolve` to resolve aliases.")
                   {:expr (str ":" next-val)}))))

(defn auto-resolve
  "Returns namespace for given alias."
  ([ctx m kns reader next-val] (auto-resolve ctx m kns reader next-val nil))
  ([ctx m kns reader next-val msg]
   (if-let [kns (m kns)]
     kns
     (throw-reader ctx reader
                   (or msg (str "Alias `" (symbol kns) "` not found in `:auto-resolve`"))
                   {:expr (str ":" next-val)}))))

(defn parse-namespaced-map [ctx reader]
  (let [prefix (edn-read ctx reader)
        the-map (parse-next ctx reader)]
    (if (keyword? prefix)
      ;; autoresolved
      (let [ns (symbol (name prefix))
            f (get-auto-resolve ctx reader ns)
            resolved-ns (auto-resolve ctx f ns reader prefix)]
        (zipmap (namespace-keys (str resolved-ns) (keys the-map))
                (vals the-map)))
      (let [resolved-ns (name prefix)]
        (zipmap (namespace-keys resolved-ns (keys the-map))
                (vals the-map))))))

(defn parse-sharp
  [ctx #?(:cljs ^not-native reader :default reader)]
  (let [c (r/peek-char reader)]
    (case c
      nil (throw-reader ctx reader (str "Unexpected EOF."))
      \" (if-let [v (:regex ctx)]
           (let [pat (read-regex-pattern ctx reader)]
             (if (ifn? v)
               (v pat)
               (re-pattern pat)))
           (throw-reader
            ctx reader
            (str "Regex not allowed. Use the `:regex` option")))
      \( (if-let [v (:fn ctx)]
           (let [fn-expr (parse-next ctx reader)]
             (if (ifn? v)
               (v fn-expr)
               (read-fn fn-expr)))
           (throw-reader
            ctx reader
            (str "Function literal not allowed. Use the `:fn` option")))
      \' (if-let [v (:var ctx)]
           (do
             (r/read-char reader) ;; ignore quote
             (let [next-val (parse-next ctx reader)]
               (when (kw-identical? ::eof next-val)
                 (throw-eof-while-reading ctx reader))
               (if (ifn? v)
                 (v next-val)
                 (list 'var next-val))))
           (throw-reader
            ctx reader
            (str "Var literal not allowed. Use the `:var` option")))
      \= (if-let [v (:read-eval ctx)]
           (do
             (r/read-char reader) ;; ignore =
             (let [next-val (parse-next ctx reader)]
               (if (ifn? v)
                 (v next-val)
                 (list 'read-eval next-val))))
           (throw-reader
            ctx reader
            (str "Read-eval not allowed. Use the `:read-eval` option")))
      \{ (parse-set ctx reader)
      \_ (do
           (r/read-char reader) ;; read _
           (parse-next ctx reader) ;; ignore next form
           reader)
      \? (do
           (when-not (:read-cond ctx)
             (throw-reader
              ctx reader
              (str "Conditional read not allowed.")))
           (r/read-char reader) ;; ignore ?
           (parse-reader-conditional ctx reader))
      \: (do
           (r/read-char reader) ;; ignore :
           (parse-namespaced-map ctx reader))
      \! (do
           (parse-comment reader)
           reader)
      ;; catch-all
      (if (dispatch-macro? c)
        (do (r/unread reader \#)
            (edn-read ctx reader))
        ;; reader tag
        (let [suppress? (::suppress ctx)]
          (if suppress?
            (do
              ;; read symbol
              (parse-next ctx reader)
              ;; read form
              (parse-next ctx reader))
            (let [sym (parse-next ctx reader)
                  data (parse-next ctx reader)
                  f (or (when-let [readers (:readers ctx)]
                          (readers sym))
                        #?(:clj (default-data-readers sym)
                           :cljs (cljs-tags/*cljs-data-readers* sym)))]
              (if f (f data)
                  (throw (new #?(:clj Exception :cljs js/Error)
                              (str "No reader function for tag " sym)))))
            #_(do (r/unread reader \#)
                  (edn-read ctx reader))))))))

(defn throw-odd-map
  [ctx #?(:cljs ^not-native reader :default reader) loc elements]
  (throw-reader ctx reader
   (str
    "The map literal starting with "
    (i/inspect (first elements))
    " contains "
    (count elements)
    " form(s). Map literals must contain an even number of forms.")
   nil
   loc))

(defn parse-map
  [ctx #?(:cljs ^not-native reader :default reader)]
  (let [start-loc (location reader)
        elements (parse-to-delimiter ctx reader \})
        c (count elements)]
    (when (pos? c)
      (when (odd? c)
        (throw-odd-map ctx reader start-loc elements))
      (let [ks (take-nth 2 elements)]
        (when-not (apply distinct? ks)
          (throw-dup-keys ctx reader start-loc :map ks))))
    (apply hash-map elements)))

(defn parse-keyword [ctx #?(:cljs ^not-native reader :default reader)]
  (r/read-char reader) ;; ignore :
  (let [init-c (r/read-char reader)]
    (when (whitespace? init-c)
      (throw-reader ctx reader (str "Invalid token: :")))
    (let [^String token (read-token reader :keyword init-c)
          auto-resolve? (identical? \: (.charAt token 0))]
      (if auto-resolve?
        (let [token (if auto-resolve? (subs token 1) token)
              [token-ns token-name] (parse-symbol token)]
          (if token-ns
            (let [f (get-auto-resolve ctx reader token)
                  kns (auto-resolve ctx f (symbol token-ns) reader token-ns)]
              (keyword (str kns) token-name))
            ;; resolve current ns
            (let [f (get-auto-resolve ctx reader token "Use `:auto-resolve` + `:current` to resolve current namespace.")
                  kns (auto-resolve ctx f :current reader token "Use `:auto-resolve` + `:current` to resolve current namespace.")]
              (keyword (str kns) token-name))))
        (keyword token)))))

(defn desugar-meta
  "Resolves syntactical sugar in metadata" ;; could be combined with some other desugar?
  ([f]
   (cond
     (keyword? f) {f true}
     (symbol? f)  {:tag f}
     (string? f)  {:tag f}
     :else        f))
  ([f postprocess]
   (cond
     (keyword? f) {(postprocess f) (postprocess true)}
     (symbol? f)  {(postprocess :tag) (postprocess f)}
     (string? f)  {(postprocess :tag) (postprocess f)}
     :else        f)))

(defn dispatch
  [ctx #?(:cljs ^not-native reader :default reader) c]
  (let [sharp? (= \# c)]
    (if sharp? (do
                 (r/read-char reader) ;; ignore sharp
                 (parse-sharp ctx reader))
        (case c
          nil ::eof
          \@ (if-let [v (:deref ctx)]
               (do
                 (r/read-char reader) ;; skip @
                 (let [next-val (parse-next ctx reader)]
                   (if (ifn? v)
                     (v next-val)
                     (list 'clojure.core/deref next-val))))
               (throw-reader
                ctx reader
                (str "Deref not allowed. Use the `:deref` option")))
          \' (if-let [v (:quote ctx)]
               (do
                 (r/read-char reader) ;; skip '
                 (let [next-val (parse-next ctx reader)]
                   (when (kw-identical? ::eof next-val)
                     (throw-eof-while-reading ctx reader))
                   (if (ifn? v)
                     (v next-val)
                     (list 'quote next-val))))
               ;; quote is allowed in normal EDN
               (edn-read ctx reader))
          \` (if-let [v (:syntax-quote ctx)]
               (do
                 (r/read-char reader) ;; skip `
                 (let [next-val (parse-next ctx reader)]
                   (if (fn? v)
                     (v next-val)
                     (let [gensyms (atom {})
                           ctx (assoc ctx :gensyms gensyms)
                           ret (syntax-quote ctx reader next-val)]
                       ret))))
               (throw-reader
                ctx reader
                (str "Syntax quote not allowed. Use the `:syntax-quote` option")))
          \~
          (if-let [v (and (:syntax-quote ctx)
                          (or (:unquote ctx)
                              true))]
            (do
              (r/read-char reader) ;; skip `
              (let [nc (r/peek-char reader)]
                (if (identical? nc \@)
                  (if-let [v (and
                              (:syntax-quote ctx)
                              (or (:unquote-splicing ctx)
                                  true))]
                    (do
                      (r/read-char reader) ;; ignore @
                      (let [next-val (parse-next ctx reader)]
                        (if (ifn? v)
                          (v next-val)
                          (list 'clojure.core/unquote-splicing next-val))))
                    (throw-reader
                     ctx reader
                     (str "Syntax unquote splice not allowed. Use the `:syntax-quote` option")))
                  (let [next-val (parse-next ctx reader)]
                    (if (ifn? v)
                      (v next-val)
                      (list 'clojure.core/unquote next-val))))))
            (throw-reader
             ctx reader
             (str "Syntax unquote not allowed. Use the `:syntax-unquote` option")))
          \( (parse-list ctx reader)
          \[ (parse-to-delimiter ctx reader \])
          \{ (parse-map ctx reader)
          (\} \] \)) (let [expected (::expected-delimiter ctx)]
                       (if (not= expected c)
                         (let [loc (location reader)]
                           (r/read-char reader) ;; ignore unexpected
                           ;; delimiter to be able to
                           ;; continue reading, fix for
                           ;; babashka socket REPL
                           (throw-reader ctx reader
                                         (str "Unmatched delimiter: " c
                                              (when expected
                                                (str ", expected: " expected
                                                     (when-let [{:keys [:row :col :char]} (::opened-delimiter ctx)]
                                                       (str " to match " char " at " [row col])))))
                                         ctx
                                         loc))
                         (do
                           ;; read delimiter
                           (r/read-char reader)
                           ::expected-delimiter)))
          \; (parse-comment reader)
          \^ (do
               (r/read-char reader) ;; ignore ^
               (let [meta-val (parse-next ctx reader true)
                     val-val (vary-meta (parse-next ctx reader)
                                        merge meta-val)]
                 val-val))
          \: (parse-keyword ctx reader)
          (edn-read ctx reader)))))

(defn parse-next
  ([ctx reader] (parse-next ctx reader nil))
  ([ctx reader desugar]
   (if-let [c (and (skip-whitespace ctx reader)
                   (r/peek-char reader))]
     (let [loc (location reader)
           obj (dispatch ctx reader c)]
       (if (identical? reader obj)
         (recur ctx reader desugar)
         (if (kw-identical? ::expected-delimiter obj)
           obj
           (let [postprocess (:postprocess ctx)
                 iobj? #?(:clj
                          (instance? clojure.lang.IObj obj)
                          :cljs (satisfies? IWithMeta obj))
                 end-loc (when (or iobj? postprocess)
                           (location reader))
                 postprocess-fn (when postprocess
                                  #(postprocess {:obj % :loc {(:row-key ctx) (:row loc)
                                                              (:col-key ctx) (:col loc)
                                                              (:end-row-key ctx) (:row end-loc)
                                                              (:end-col-key ctx) (:col end-loc)}}))
                 obj (if desugar
                       (if postprocess-fn
                         (desugar-meta obj postprocess-fn)
                         (desugar-meta obj)) obj)
                 obj (cond postprocess (postprocess-fn obj)
                           iobj? (vary-meta obj #(assoc %
                                                        (:row-key ctx) (:row loc)
                                                        (:col-key ctx) (:col loc)
                                                        (:end-row-key ctx) (:row end-loc)
                                                        (:end-col-key ctx) (:col end-loc)))
                           :else obj)]
             obj))))
     ::eof)))

(defn string-reader
  "Create reader for strings."
  [s]
  (r/indexing-push-back-reader
   (r/string-push-back-reader s)))

(defrecord Options [dispatch deref syntax-quote unquote
                    unquote-splicing quote fn var
                    read-eval regex
                    row-key col-key
                    end-row-key end-col-key])

(defn normalize-opts [opts]
  (let [opts (if-let [dispatch (:dispatch opts)]
               (into (dissoc opts :dispatch)
                     [(when-let [v (get-in dispatch [\@])]
                        [:deref v])
                      (when-let [v (get-in dispatch [\`])]
                        [:syntax-quote v])
                      (when-let [v (get-in dispatch [\~])]
                        (if (fn? v)
                          [:unquote v]
                          (when-let [v (:default v)]
                            [:unquote v])))
                      (when-let [v (get-in dispatch [\~ \@])]
                        [:unquote-splicing v])
                      (when-let [v (get-in dispatch [\'])]
                        [:quote v])
                      (when-let [v (get-in dispatch [\# \(])]
                        [:fn v])
                      (when-let [v (get-in dispatch [\# \'])]
                        [:var v])
                      (when-let [v (get-in dispatch [\# \=])]
                        [:read-eval v])
                      (when-let [v (get-in dispatch [\# \"])]
                        [:regex v])])
               opts)
        opts (if (:all opts)
               (merge {:deref true
                       :fn true
                       :quote true
                       :read-eval true
                       :regex true
                       :syntax-quote true
                       :var true} opts)
               opts)
        opts (cond-> opts
               (not (:row-key opts)) (assoc :row-key :row)
               (not (:end-row-key opts)) (assoc :end-row-key :end-row)
               (not (:col-key opts)) (assoc :col-key :col)
               (not (:end-col-key opts)) (assoc :end-col-key :end-col))]
    (map->Options opts)))

(defn parse-string [s opts]
  (let [opts (normalize-opts opts)
        ^Closeable r (string-reader s)
        ctx (assoc opts ::expected-delimiter nil)
        v (parse-next ctx r)]
    (if (kw-identical? ::eof v) nil v)))

(defn parse-string-all [s opts]
  (let [opts (normalize-opts opts)
        ^Closeable r (string-reader s)
        ctx (assoc opts ::expected-delimiter nil)]
    (loop [ret (transient [])]
      (let [next-val (parse-next ctx r)]
        (if (kw-identical? ::eof next-val)
          (persistent! ret)
          (recur (conj! ret next-val)))))))

(defn reader
  [x]
  #?(:clj (r/indexing-push-back-reader (r/push-back-reader x))
     :cljs (let [string-reader (r/string-reader x)
                 buf-len 1
                 pushback-reader (r/PushbackReader. string-reader
                                                    (object-array buf-len)
                                                    buf-len buf-len)]
             (r/indexing-push-back-reader pushback-reader))))

(defn get-line-number [reader]
  (r/get-line-number reader))

(defn get-column-number [reader]
  (r/get-column-number reader))

;;;; Scratch

(comment
  )

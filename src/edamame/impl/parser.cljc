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
   #?(:cljs [cljs.tagged-literals :refer [*cljs-data-readers*]])
   [clojure.string :as str]
   [edamame.impl.macros :as macros]
   [edamame.impl.read-fn :refer [read-fn]]
   [edamame.impl.syntax-quote :refer [syntax-quote]])
  #?(:clj (:import [java.io Closeable]
                   [clojure.tools.reader.reader_types SourceLoggingPushbackReader]))
  #?(:cljs (:import [goog.string StringBuffer]))
  #?(:cljs (:require-macros [edamame.impl.parser :refer [kw-identical?]])))

#?(:clj (set! *warn-on-reflection* true))

(def eof #?(:clj (Object.) :cljs (js/Object.)))
(def expected-delimiter #?(:clj (Object.) :cljs (js/Object.)))

(defn throw-reader
  "Throw reader exception, including line line/column. line/column is
  read from the reader but it can be overriden by passing loc
  optional parameter."
  ([ctx #?(:cljs ^:not-native reader :default reader) msg]
   (throw-reader ctx reader msg nil))
  ([ctx #?(:cljs ^:not-native reader :default reader) msg data]
   (throw-reader ctx reader msg data nil))
  ([ctx #?(:cljs ^:not-native reader :default reader) msg data loc]
   (let [ir? (r/indexing-reader? reader)
         c (when ir? (:col loc (r/get-column-number reader)))
         l (when ir? (:row loc (r/get-line-number reader)))]
     (throw
      (ex-info msg
               (merge (assoc {:type :edamame/error}
                             (:row-key ctx) l
                             (:col-key ctx) c) data))))))

;;;; tools.reader

(defn dispatch-macro? [ch]
  (case ch (\^  ;; deprecated
            \'
            \(
            \{
            \"
            \!
            \_
            \?
            \:
            \#) true
        false))

(defn macro? [ch]
  (case ch
    (\: \; \' \@ \^ \` \~ \( \) \[ \] \{ \} \\ \% \# \") true
    false))

(defn- macro-terminating? [ch]
  (case ch
    (\" \; \@ \^ \` \~ \( \) \[ \] \{ \} \\) true
    false))

#?(:cljs
   (defn whitespace?
     [c]
     (and c (< -1 (.indexOf #js [\return \newline \tab \space ","] c)))))

#?(:clj
   (defmacro whitespace? [c]
     `(and ~c (or (identical? ~c \,)
                  (Character/isWhitespace ~(with-meta c
                                             {:tag 'java.lang.Character}))))))

(defn- ^String read-token
  "Read in a single logical token from the reader"
  [#?(:clj rdr :cljs ^not-native rdr) _kind initch]
  (loop [sb #?(:clj (StringBuilder.)
               :cljs (StringBuffer.)) ch initch]
    (if (or (whitespace? ch)
            (macro-terminating? ch)
            (nil? ch))
      (do (when ch
            (r/unread rdr ch))
          (str sb))
      (recur (.append sb ch) (r/read-char rdr)))))

(def parse-symbol @#'commons/parse-symbol)
(def number-literal? @#'commons/number-literal?)
(def escape-char @#'edn/escape-char)
(def read-char* @#'edn/read-char*)
(def read-symbolic-value  @#'edn/read-symbolic-value)

(defn- read-number
  [ctx #?(:clj rdr :cljs ^not-native rdr) initch]
  (loop [sb (doto #?(:clj (StringBuilder.)
                     :cljs (StringBuffer.)) (.append initch))
         ch (r/read-char rdr)]
    (if (or (whitespace? ch)
            ;; why isn't this macro-terminating in tools.reader?
            ;; the diff is #{\# \% \' \:}
            ;; answer: foo%bar is a valid symbol, whereas 1%2 is not a valid number
            ;; similar for x'y vs 1'2 (which is 1 followed by a quoted 2)
            (macro? ch)
            (nil? ch))
      (let [s (str sb)]
        (r/unread rdr ch)
        (or (commons/match-number s)
            (throw-reader ctx rdr (str "Invalid number: " s))))
      (recur (doto sb (.append ch)) (r/read-char rdr)))))

(defn edn-read [ctx #?(:cljs ^not-native reader :default reader)]
  (let [tools-reader-opts (:tools.reader/opts ctx)]
    (edn/read tools-reader-opts reader)))

(defn- parse-string*
  [ctx #?(:cljs ^not-native reader :default reader)]
  (let [ir? (r/indexing-reader? reader)
        row (when ir? (r/get-line-number reader))
        col (when ir? (r/get-column-number reader))
        opened (r/read-char reader)]
    (loop [sb #?(:clj (StringBuilder.)
                 :cljs (StringBuffer.))
           ch (r/read-char reader)]
      (case ch
        nil (throw-reader ctx
                          reader
                          (str "EOF while reading, expected " opened " to match " opened " at [" row "," col "]")
                          {:edamame/expected-delimiter (str opened)
                           :edamame/opened-delimiter (str opened)
                           :edamame/opened-delimiter-loc {:row row
                                                          :col col}})
        \\ (recur (doto sb (.append (escape-char sb reader)))
                  (r/read-char reader))
        \" (str sb)
        (recur (doto sb (.append ch)) (r/read-char reader))))))

;;;; end tools.reader

(defrecord Loc [row col])

(defn location [#?(:cljs ^not-native reader :default reader)]
  (->Loc
   (r/get-line-number reader)
   (r/get-column-number reader)))

(defmacro kw-identical? [k v]
  (macros/?
   :clj `(identical? ~k ~v)
   :cljs `(cljs.core/keyword-identical? ~k ~v)))

(declare parse-next)

(defn parse-comment
  [#?(:cljs ^not-native reader :default reader)]
  (r/read-line reader)
  reader)

(defn skip-whitespace
  "Skips whitespace. Returns reader. If end of stream is reached, returns nil."
  [_ctx #?(:cljs ^not-native reader :default reader)]
  (loop []
    (when-let [c (r/read-char reader)]
      (if (whitespace? c)
        (recur)
        (do (r/unread reader c)
            reader)))))

(def non-match (symbol "non-match"))

(defn non-match? [v]
  (identical? v non-match))

(defn throw-eof-while-reading [ctx reader]
  (throw-reader ctx reader "EOF while reading"))

(defn parse-to-delimiter
  ([ctx #?(:cljs ^not-native reader :default reader) delimiter]
   (parse-to-delimiter ctx reader delimiter []))
  ([ctx #?(:cljs ^not-native reader :default reader) delimiter into]
   (let [ir? (r/indexing-reader? reader)
         row (when ir? (r/get-line-number reader))
         col (when ir? (r/get-column-number reader))
         opened (r/read-char reader)
         ctx (-> ctx
                 (assoc ::expected-delimiter delimiter)
                 (assoc ::opened-delimiter {:char opened :row row :col col}))]
     (loop [vals (transient into)]
       (let [;; if next-val is uneval, we get back the expected delimiter...
             next-val (parse-next ctx reader)
             cond-splice? (some-> next-val meta ::cond-splice)]
         (cond
           (identical? eof next-val)
           (throw-reader ctx
                         reader
                         (str "EOF while reading, expected " delimiter " to match " opened " at [" row "," col "]")
                         {:edamame/expected-delimiter (str delimiter)
                          :edamame/opened-delimiter (str opened)
                          :edamame/opened-delimiter-loc {:row row
                                                         :col col}})
           (identical? expected-delimiter next-val)
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
  (let [start-loc (when (r/indexing-reader? reader)
                    (location reader))
        coll (parse-to-delimiter ctx reader \})
        the-set (set coll)]
    (when-not (= (count coll) (count the-set))
      (throw-dup-keys ctx reader start-loc :set coll))
    the-set))

(defn parse-first-matching-condition [ctx #?(:cljs ^not-native reader :default reader)]
  (let [features (:features ctx)]
    (loop [match non-match]
      (let [k (parse-next ctx reader)]
        (if (identical? expected-delimiter k)
          match
          (let [next-is-match? (and (non-match? match)
                                    (or (contains? features k)
                                        (kw-identical? k :default)))]
            (if next-is-match?
              (let [match (parse-next ctx reader)
                    ctx (assoc ctx ::suppress true)]
                (loop []
                  (let [next-val (parse-next ctx reader)]
                    (when-not (identical? expected-delimiter
                                          next-val)
                      (if (identical? eof next-val)
                        (let [delimiter (::expected-delimiter ctx)
                              {:keys [:row :col :char]} (::opened-delimiter ctx)]
                          (throw-reader ctx
                                        reader
                                        (str "EOF while reading, expected " delimiter " to match " char " at [" row "," col "]")
                                        {:edamame/expected-delimiter (str delimiter)
                                         :edamame/opened-delimiter (str char)}))
                        (recur)))))
                match)
              (do
                ;; skip over next val and try next key
                (parse-next (assoc ctx ::suppress true)
                            reader)
                (recur match)))))))))

(defn parse-reader-conditional [ctx #?(:cljs ^not-native reader :default reader)]
  (skip-whitespace ctx reader)
  (let [opt (:read-cond ctx)
        splice? (= \@ (r/peek-char reader))]
    (when splice? (r/read-char reader))
    (skip-whitespace ctx reader)
    (cond (kw-identical? :preserve opt)
          (reader-conditional (parse-next ctx reader) splice?)
          (fn? opt)
          (opt (vary-meta
                (parse-next ctx reader)
                assoc :edamame/read-cond-splicing splice?))
          :else
          (let [row (r/get-line-number reader)
                col (r/get-column-number reader)
                opened (r/read-char reader)
                ctx (-> ctx
                        (assoc ::expected-delimiter \))
                        (assoc ::opened-delimiter {:char opened :row row :col col}))
                match (parse-first-matching-condition ctx reader)]
            (cond (non-match? match) reader
                  splice? (vary-meta match
                                     #(assoc % ::cond-splice true))
                  :else match)))))

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

(defn- read-symbol
  ([ctx #?(:cljs ^not-native reader :default reader)]
   (read-symbol ctx reader (r/read-char reader)))
  ([ctx #?(:cljs ^not-native reader :default reader) initch]
   (when-let [token (read-token reader :symbol initch)]
     (case token

       ;; special symbols
       "nil" nil
       "true" true
       "false" false
       "/" '/

       (or (when-let [p (parse-symbol token)]
             (symbol (p 0) (p 1)))
           (throw-reader ctx reader (str "Invalid symbol: " token) (update (location reader)
                                                                           :col - (count token))))))))

(defn parse-namespaced-map [ctx #?(:cljs ^not-native reader :default reader)]
  (let [auto-resolved? (when (identical? \: (r/peek-char reader))
                         (r/read-char reader)
                         true)
        current-ns? (when auto-resolved?
                      (identical? \{ (r/peek-char reader)))
        prefix (if auto-resolved?
                 (when-not current-ns?
                   (read-symbol ctx reader))
                 (read-symbol ctx reader))
        the-map (parse-next ctx reader)]
    (if auto-resolved?
      (let [ns (if current-ns? :current (symbol (name prefix)))
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
               (when (identical? eof next-val)
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
      \# (do
           (r/read-char reader)
           (read-symbolic-value reader nil nil))
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
                           :cljs (*cljs-data-readers* sym)))]
              (if f (f data)
                  (throw (new #?(:clj Exception :cljs js/Error)
                              (str "No reader function for tag " sym)))))))))))

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
  (let [ir? (r/indexing-reader? reader)
        start-loc (when ir? (location reader))
        elements (parse-to-delimiter ctx reader \})
        c (count elements)]
    (when (pos? c)
      (when (odd? c)
        (throw-odd-map ctx reader start-loc elements))
      (let [ks (take-nth 2 elements)]
        (when-not (apply distinct? ks)
          (throw-dup-keys ctx reader start-loc :map ks))))
    (if (<= c 16)
      (apply array-map elements)
      (apply hash-map elements))))

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

;; NOTE: I tried optimizing for the :all option by dispatching to a function
;; that doesn't do any checking, but saw no significant speedup.
(defn dispatch
  [ctx #?(:cljs ^not-native reader :default reader) c]
  (let [ir? (r/indexing-reader? reader)
        sharp? (= \# c)]
    (if sharp? (do
                 (r/read-char reader) ;; ignore sharp
                 (parse-sharp ctx reader))
        (case c
          nil eof
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
                   (when (identical? eof next-val)
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
                         (let [loc (when ir? (location reader))]
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
                                         (let [{:keys [:char :row :col]} (::opened-delimiter ctx)]
                                           {:edamame/opened-delimiter (str char)
                                            :edamame/opened-delimiter-loc {:row row :col col}
                                            :edamame/expected-delimiter (str expected)})
                                         loc))
                         (do
                           ;; read delimiter
                           (r/read-char reader)
                           expected-delimiter)))
          \; (parse-comment reader)
          \^ (do
               (r/read-char reader) ;; ignore ^
               (let [meta-val (parse-next ctx reader true)
                     val-val (vary-meta (parse-next ctx reader)
                                        merge meta-val)]
                 val-val))
          \: (parse-keyword ctx reader)
          \" (parse-string* ctx reader)
          \\ (read-char* reader (r/read-char reader) nil)
          (let [;; we're reading c here because number-literal? does a peek
                c (r/read-char reader)]
            (cond
              ;; NOTE: clojure/edn first checks number-literal before matching on
              ;; macro chars, is this better for perf?
              (number-literal? reader c)
              (read-number ctx reader c)
              :else (read-symbol ctx reader c)))))))

(defn iobj? [obj]
  #?(:clj
     (instance? clojure.lang.IObj obj)
     :cljs (satisfies? IWithMeta obj)))

(defn parse-next
  ([ctx reader] (parse-next ctx reader nil))
  ([ctx reader desugar]
   (let [ir? (r/indexing-reader? reader)]
     (if-let [c (and (skip-whitespace ctx reader)
                     (r/peek-char reader))]
       (let [loc (when ir? (location reader))
             log? (:source ctx)
             buf (fn [] (str (:buffer @#?(:clj (.source-log-frames ^SourceLoggingPushbackReader reader)
                                          :cljs (.-frames reader)))))
             offset (when log? (count (buf)))
             obj (if log?
                   #?(:clj (r/log-source reader (dispatch ctx reader c))
                      :cljs (r/log-source* reader #(dispatch ctx reader c)))
                   (dispatch ctx reader c))]
         (if (identical? reader obj)
           (recur ctx reader desugar)
           (if (identical? expected-delimiter obj)
             obj
             (let [postprocess (:postprocess ctx)
                   location? (:location? ctx)
                   end-loc? (:end-location ctx)
                   iobj?? (iobj? obj)
                   src (when log?
                         (.trim (subs (buf) offset)))
                   loc? (and ir? (or (and iobj??
                                          (or (not location?)
                                              (location? obj)))
                                     postprocess))
                   end-loc (when (and ir? loc? end-loc?)
                             (location reader))
                   row (when loc? (:row loc))
                   end-row (when end-loc? (:row end-loc))
                   col (when loc? (:col loc))
                   end-col (when end-loc? (:col end-loc))
                   postprocess-fn (when postprocess
                                    #(postprocess
                                      (cond->
                                          {:obj %}
                                        loc? (assoc :loc (cond-> {(:row-key ctx) row
                                                                  (:col-key ctx) col}
                                                           end-loc? (-> (assoc (:end-row-key ctx) end-row
                                                                               (:end-col-key ctx) end-col))))
                                        src (assoc (or (:source-key ctx)
                                                       :source)
                                                   src))))
                   obj (if desugar
                         (if postprocess-fn
                           (desugar-meta obj postprocess-fn)
                           (desugar-meta obj)) obj)
                   obj (cond postprocess (postprocess-fn obj)
                             loc? (vary-meta obj
                                             #(cond-> (-> %
                                                          (assoc (:row-key ctx) row)
                                                          (assoc (:col-key ctx) col))
                                                end-loc? (-> (assoc (:end-row-key ctx) end-row)
                                                             (assoc (:end-col-key ctx) end-col))
                                                src (assoc (:source-key ctx) src)))
                             :else obj)]
               obj))))
       eof))))

(defn string-reader
  "Create reader for strings."
  [s]
  (r/indexing-push-back-reader
   (r/string-push-back-reader s)))

(defrecord Options [dispatch deref syntax-quote unquote
                    unquote-splicing quote fn var
                    read-eval regex
                    row-key col-key
                    end-row-key end-col-key
                    source source-key
                    postprocess location?
                    end-location])

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
               (not (:col-key opts)) (assoc :col-key :col)
               (not (:end-row-key opts)) (assoc :end-row-key :end-row)
               (not (:end-col-key opts)) (assoc :end-col-key :end-col)
               (not (:source-key opts)) (assoc :source-key :source)
               (not (contains? opts :end-location)) (assoc :end-location true))]
    (map->Options opts)))

(defn parse-string [s opts]
  (let [opts (normalize-opts opts)
        src? (:source opts)
        r (if src? (r/source-logging-push-back-reader s)
              (string-reader s))
        ctx (assoc opts ::expected-delimiter nil)
        v (parse-next ctx r)]
    (if (identical? eof v) nil v)))

(defn parse-string-all [s opts]
  (let [opts (normalize-opts opts)
        ^Closeable r (string-reader s)
        ctx (assoc opts
                   ::expected-delimiter nil)]
    (loop [ret (transient [])]
      (let [next-val (parse-next ctx r)]
        (if (identical? eof next-val)
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

(defn source-logging-reader
  [x]
  #?(:clj (r/source-logging-push-back-reader (r/push-back-reader x))
     :cljs (let [string-reader (r/string-reader x)
                 buf-len 1
                 pushback-reader (r/PushbackReader. string-reader
                                                    (object-array buf-len)
                                                    buf-len buf-len)]
             (r/source-logging-push-back-reader pushback-reader))))

;;;; Scratch

(comment
  )

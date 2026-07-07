(ns edamame.impl.parser
  "This code is largely inspired by rewrite-clj(sc), so thanks to all
  who contribured to those projects."
  {:no-doc true}
  (:require
   #?(:cljd [edamame.impl.cljd-shim :as edn]
      :cljs [cljs.tools.reader.edn :as edn]
      :cljr [clojure.tools.reader.edn :as edn]
      :clj  [clojure.tools.reader.edn :as edn])
   #?(:cljd [edamame.impl.cljd-reader-types :as r]
      :cljs [cljs.tools.reader.reader-types :as r]
      :cljr [clojure.tools.reader.reader-types :as r]
      :clj  [clojure.tools.reader.reader-types :as r])
   #?(:cljd [edamame.impl.cljd-shim :as i]
      :cljs [cljs.tools.reader.impl.inspect :as i]
      :cljr [clojure.tools.reader.impl.inspect :as i]
      :clj  [clojure.tools.reader.impl.inspect :as i])
   #?(:cljd [edamame.impl.cljd-shim :as utils :refer [namespace-keys reader-conditional]]
      :cljs [cljs.tools.reader.impl.utils :refer [namespace-keys reader-conditional]]
      :cljr [clojure.tools.reader.impl.utils :as utils :refer [namespace-keys whitespace?]]
      :clj [clojure.tools.reader.impl.utils :as utils :refer [namespace-keys]])
   #?(:cljd [edamame.impl.cljd-shim :as commons]
      :cljs [cljs.tools.reader.impl.commons :as commons]
      :cljr [clojure.tools.reader.impl.commons :as commons]
      :clj [clojure.tools.reader.impl.commons :as commons])
   #?(:cljs [cljs.tagged-literals :refer [*cljs-data-readers*]])
   [clojure.string :as str]
   [edamame.impl.macros :as macros]
   [edamame.impl.ns-parser :as ns-parser]
   [edamame.impl.read-fn :refer [read-fn]]
   [edamame.impl.syntax-quote :refer [syntax-quote]])
  #?(:cljs (:import
            [goog.string StringBuffer]))
  #?(:cljs (:require-macros [edamame.impl.parser :refer [kw-identical?]])))

#?(:clj (set! *warn-on-reflection* true))

;; cljd const-folds (Object.) into one shared instance, ^:unique opts out
(def eof #?(:clj (Object.) :cljs (js/Object.) :cljd ^:unique (Object.) :cljr (Object.)))
(def continue #?(:clj (Object.) :cljs (js/Object.) :cljd ^:unique (Object.) :cljr (Object.)))
(def expected-delimiter #?(:clj (Object.) :cljs (js/Object.) :cljd ^:unique (Object.) :cljr (Object.)))
#?(:cljs (def Exception js/Error))

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

#?(:cljd
   (def whitespace? r/whitespace?)
   :cljs
   (defn whitespace?
     [c]
     (and c (< -1 (.indexOf #js [\return \newline \tab \space ","] c))))
   :clj
   (defmacro whitespace? [c]
     `(and ~c (or (identical? ~c \,)
                  (Character/isWhitespace ~(with-meta c
                                             {:tag 'java.lang.Character}))))))

#?(:cljd nil
   :clj
   (definterface IFastOps
     (readToken [initch])
     (finishToken [^long start ^long end])
     (readString [])
     (skipWhitespace [])))

(defn- read-token
  "Read in a single logical token from the reader"
  ^String [#?(:clj rdr :cljs ^not-native rdr :cljd rdr :cljr rdr) _kind initch]
  (or #?(:cljd nil
         :clj (when (instance? IFastOps rdr)
                (.readToken ^IFastOps rdr initch))
         :default nil)
      (loop [#?(:cljd ^StringBuffer sb :default sb)
             #?(:clj (StringBuilder.)
                :cljs (StringBuffer.)
                :cljd (StringBuffer)
                :cljr (StringBuilder.))
             ch initch]
        (if (or (whitespace? ch)
                (macro-terminating? ch)
                (nil? ch))
          (do (when ch
                (r/unread rdr ch))
              (str sb))
          (recur #?(:clj (.append sb ch) :cljs (.append sb ch) :cljd (doto sb (.write ch)) :cljr (.Append sb (str ch))) (r/read-char rdr))))))

(defn str-len [^String s]
  #?(:clj (.length s)
     :cljs (.-length s)
     :cljd (.-length s)
     :cljr (.Length s)))

(defn- parse-long*
  "Parses char to num"
  [#?(:clj ^Character c :default c)]
  #?(:cljs (let [x (js/parseInt c)]
             (when-not (NaN? x)
               x))
     :default (let [i (int c)
                    i (- i 48)]
                (when (<= 0 i 9)
                  i))))

(defn- array-dim [^String sym]
  (when (== 1 (str-len sym))
    (when-let [i (parse-long* #?(:clj (.charAt sym 0) :cljs (.charAt sym 0) :cljd (nth sym 0) :cljr (.get_Chars sym 0)))]
      (when (pos? i)
        i))))

(defn parse-symbol
  "Parses a string into a vector of the namespace and symbol"
  [^String token]
  (when-not (or (= "" token)
                (#?(:clj .endsWith :cljs .endsWith :cljd .endsWith :cljr .EndsWith) token ":")
                (#?(:clj .startsWith :cljs .startsWith :cljd .startsWith :cljr .StartsWith) token "::"))
    (let [ns-idx #?(:clj (.indexOf token "/") :cljs (.indexOf token "/") :cljd (.indexOf token "/") :cljr (.IndexOf token "/"))]
      (if-let [^String ns (and (pos? ns-idx)
                               (subs token 0 ns-idx))]
        (let [ns-idx (inc ns-idx)]
          (when-not (== ns-idx (str-len token))
            (when-not (#?(:clj .endsWith :cljs .endsWith :cljd .endsWith :cljr .EndsWith) ns ":")
              (let [^String sym (subs token ns-idx)]
                (if (array-dim sym)
                  [ns sym]
                  (when (and (not (= "" sym))
                             (not (parse-long* #?(:clj (.charAt sym 0) :cljs (.charAt sym 0) :cljd (nth sym 0) :cljr (.get_Chars sym 0))))
                             (or (= "/" sym )
                                 (== -1 #?(:clj (.indexOf sym "/") :cljs (.indexOf sym "/") :cljd (.indexOf sym "/") :cljr (.IndexOf sym "/")))))
                    [ns sym]))))))
        (when (or (= "/" token)
                  (== -1 #?(:clj (.indexOf token "/") :cljs (.indexOf token "/") :cljd (.indexOf token "/") :cljr (.IndexOf token "/"))))
          [nil token])))))

#?(:cljd
   (do
     (def number-literal? commons/number-literal?)
     (def escape-char edn/escape-char)
     (def read-char* edn/read-char*)
     (def read-symbolic-value edn/read-symbolic-value))
   :default
   (do
     (def number-literal? @#'commons/number-literal?)
     (def escape-char @#'edn/escape-char)
     (def read-char* @#'edn/read-char*)
     (def read-symbolic-value  @#'edn/read-symbolic-value)))

(defn- read-number
  [ctx #?(:clj rdr :cljs ^not-native rdr :cljd rdr :cljr rdr) initch]
  (loop [#?(:cljd ^StringBuffer sb :default sb)
         (doto #?(:clj (StringBuilder.)
                  :cljs (StringBuffer.)
                  :cljd (StringBuffer)
                  :cljr (StringBuilder.)) #?(:clj (.append initch) :cljs (.append initch) :cljd (.write initch) :cljr (.Append (str initch))))
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
      (recur (doto sb #?(:clj (.append ch) :cljs (.append ch) :cljd (.write ch) :cljr (.Append (str ch)))) (r/read-char rdr)))))

(defn edn-read [ctx #?(:cljs ^not-native reader :default reader)]
  (let [tools-reader-opts (:tools.reader/opts ctx)]
    (edn/read tools-reader-opts reader)))

(defn- parse-string-generic
  [ctx #?(:cljs ^not-native reader :default reader)]
  (let [ir? (r/indexing-reader? reader)
        row (when ir? (r/get-line-number reader))
        col (when ir? (r/get-column-number reader))
        opened (r/read-char reader)]
    (loop [#?(:cljd ^StringBuffer sb :default sb)
           #?(:clj (StringBuilder.)
              :cljs (StringBuffer.)
              :cljd (StringBuffer)
              :cljr (StringBuilder.))
           ch (r/read-char reader)]
      (case ch
        nil (throw-reader ctx
                          reader
                          (str "EOF while reading, expected " opened " to match " opened " at [" row "," col "]")
                          {:edamame/expected-delimiter (str opened)
                           :edamame/opened-delimiter (str opened)
                           :edamame/opened-delimiter-loc {:row row
                                                          :col col}})
        \\ (recur (doto sb #?(:clj (.append (escape-char sb reader)) :cljs (.append (escape-char sb reader)) :cljd (.write (escape-char sb reader)) :cljr (.Append (str (escape-char sb reader)))))
                  (r/read-char reader))
        \" (str sb)
        (recur (doto sb #?(:clj (.append ch) :cljs (.append ch) :cljd (.write ch) :cljr (.Append (str ch)))) (r/read-char reader))))))

(defn- parse-string*
  [ctx #?(:cljs ^not-native reader :default reader)]
  (or #?(:cljd nil
         :clj (when (instance? IFastOps reader)
                (.readString ^IFastOps reader))
         :default nil)
      (parse-string-generic ctx reader)))

;;;; end tools.reader

(defrecord Loc [row col])

;; expected: the expected closing delimiter char of the innermost
;; collection being parsed; char/row/col: its opening delimiter and
;; location. One ctx field so entering a collection costs a single
;; record copy.
(defrecord Delims [expected char row col])

(defn location [#?(:cljs ^not-native reader :default reader)]
  (->Loc
   (r/get-line-number reader)
   (r/get-column-number reader)))

(defmacro kw-identical? [k v]
  ;; cljd keywords are not reference-equal
  #?(:cljd `(= ~k ~v)
     :default (macros/?
               :clj `(identical? ~k ~v)
               :cljs `(cljs.core/keyword-identical? ~k ~v))))

(declare parse-next)

(defn parse-comment
  [#?(:cljs ^not-native reader :default reader)]
  (r/read-line reader)
  continue)

(defn skip-whitespace
  "Skips whitespace. Returns :none or :some depending on whitespace
  read. If end of stream is reached, returns nil."
  [_ctx #?(:cljs ^not-native reader :default reader)]
  #?(:cljd
     (loop [read :none]
       (when-let [c (r/read-char reader)]
         (if (whitespace? c)
           (recur :some)
           (do (r/unread reader c)
               read))))
     :clj
     (if (instance? IFastOps reader)
       (.skipWhitespace ^IFastOps reader)
       (loop [read :none]
         (when-let [c (r/read-char reader)]
           (if (whitespace? c)
             (recur :some)
             (do (r/unread reader c)
                 read)))))
     :default
     (loop [read :none]
       (when-let [c (r/read-char reader)]
         (if (whitespace? c)
           (recur :some)
           (do (r/unread reader c)
               read))))))

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
         ctx (assoc ctx :delims (->Delims delimiter opened row col))
         read-cond? (:read-cond ctx)]
     (loop [vals (transient into)]
       (let [;; if next-val is uneval, we get back the expected delimiter...
             next-val (parse-next ctx reader)
             cond-splice? (and read-cond?
                               (some-> next-val meta :edamame/read-cond-splicing))]
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
           cond-splice? (let [vals
                              (reduce conj! vals next-val)]
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
              :cljs (StringBuffer.)
              :cljd (StringBuffer)
              :cljr (StringBuilder.))]
    (loop [ch (r/read-char reader)]
      (if (identical? \" ch)
        (str sb)
        (if (nil? ch)
          (throw-reader ctx reader "Error while parsing regex")
          (do
            #?(:clj (.append sb ch) :cljs (.append sb ch) :cljd (doto sb (.write ch)) :cljr (.Append sb (str ch)))
            (when (identical? \\ ch)
              (let [ch (r/read-char reader)]
                (when (nil? ch)
                  (throw-reader ctx reader "Error while parsing regex"))
                #?(:clj (.append sb ch) :cljs (.append sb ch) :cljd (doto sb (.write ch)) :cljr (.Append sb (str ch)))))
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
        coll (parse-to-delimiter ctx reader \})]
    (if-let [sf (:set ctx)]
      (apply sf coll)
      (let [the-set (set coll)]
        (when-not (= (count coll) (count the-set))
          (throw-dup-keys ctx reader start-loc :set coll))
        the-set))))

(defn parse-first-matching-condition [ctx #?(:cljs ^not-native reader :default reader)]
  (let [features (:features ctx)
        features? (some? features)]
    (loop [match non-match]
      (let [k (parse-next ctx reader)]
        (if (identical? expected-delimiter k)
          match
          (do
            (when-not (keyword? k)
              (throw-reader ctx
                            reader
                            (str "Feature should be a keyword: " k)))
            (let [next-is-match? (and (non-match? match)
                                      (or (when features? (features k))
                                          (kw-identical? k :default)))]
              (if next-is-match?
                (let [match (parse-next ctx reader)
                      ctx (assoc ctx :suppress-read true)]
                  (loop []
                    (let [next-val (parse-next ctx reader)]
                      (when-not (identical? expected-delimiter
                                            next-val)
                        (if (identical? eof next-val)
                          (let [{:keys [:expected :row :col :char]} (:delims ctx)
                                delimiter expected]
                            (throw-reader ctx
                                          reader
                                          (str "EOF while reading, expected " delimiter " to match " char " at [" row "," col "]")
                                          {:edamame/expected-delimiter (str delimiter)
                                           :edamame/opened-delimiter (str char)}))
                          (recur)))))
                  match)
                (do
                  ;; skip over next val and try next key
                  (parse-next (assoc ctx :suppress-read true)
                              reader)
                  (recur match))))))))))

(defn iobj? [obj]
  #?(:clj
     (instance? clojure.lang.IObj obj)
     :cljs (satisfies? IWithMeta obj)
     :cljd (satisfies? IWithMeta obj)
     :cljr (instance? clojure.lang.IObj obj)))

(defn attach-splice [v splice? override?]
  (if (and splice? (iobj? v))
    (vary-meta v (fn [m]
                   (if (or override? (not (contains? m :edamame/read-cond-splicing)))
                     (assoc m :edamame/read-cond-splicing splice?)
                     m)))
    v))

(defn parse-reader-conditional [ctx #?(:cljs ^not-native reader :default reader)]
  (skip-whitespace ctx reader)
  (let [opt (:read-cond ctx)
        splice? (= \@ (r/peek-char reader))]
    (when splice? (r/read-char reader))
    (skip-whitespace ctx reader)
    (cond (kw-identical? :preserve opt)
          (reader-conditional (parse-next ctx reader) splice?)
          (fn? opt)
          (let [ret (opt (attach-splice (parse-next ctx reader) splice? true))]
            (attach-splice ret splice? false))
          :else
          (let [ir? (r/indexing-reader? reader)
                row (when ir? (r/get-line-number reader))
                col (when ir? (r/get-column-number reader))
                opened (r/read-char reader)
                ctx (assoc ctx :delims (->Delims \) opened row col))
                match (parse-first-matching-condition ctx reader)]
            (if (non-match? match) continue
                (attach-splice match splice? true))))))

(defn get-auto-resolve
  ([ctx reader next-val]
   (get-auto-resolve ctx reader next-val nil))
  ([ctx reader next-val msg]
   (if-let [v (let [ar (:auto-resolve ctx)]
                (if-let [ns-state (some-> ctx :ns-state deref)]
                  (fn [alias]
                    (or (ns-state alias)
                        (when ar (ar alias))))
                  ar))]
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
  (let [peeked-char (r/peek-char reader)
        whitespace-before? (whitespace? peeked-char)
        auto-resolved? (when (identical? \: peeked-char)
                         (r/read-char reader)
                         true)
        whitespace-after? (kw-identical? :some (skip-whitespace ctx reader))
        current-ns? (when auto-resolved?
                      (identical? \{ (r/peek-char reader)))
        _ (when (and (not current-ns?)
                     (or whitespace-before?
                         whitespace-after?))
            (throw-reader ctx reader "Namespaced map must specify a namespace"))
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
      nil (throw-reader ctx reader "Unexpected EOF.")
      \" (if-let [v (:regex ctx)]
           (let [pat (read-regex-pattern ctx reader)]
             (if (true? v)
               (re-pattern pat)
               (v pat)))
           (throw-reader
            ctx reader
            "Regex not allowed. Use the `:regex` option"))
      \( (if-let [v (:fn ctx)]
           (if (:fn-literal ctx)
             (throw-reader
              ctx reader
              "Nested fn literals not allowed.")
             (let [fn-expr (parse-next (assoc ctx :fn-literal true) reader)]
               (if (true? v)
                 (read-fn fn-expr)
                 (v fn-expr))))
           (throw-reader
            ctx reader
            "Function literal not allowed. Use the `:fn` option"))
      \' (if-let [v (:var ctx)]
           (do
             (r/read-char reader) ;; ignore quote
             (let [next-val (parse-next ctx reader)]
               (when (identical? eof next-val)
                 (throw-eof-while-reading ctx reader))
               (if (true? v)
                 (list 'var next-val)
                 (v next-val))))
           (throw-reader
            ctx reader
            "Var literal not allowed. Use the `:var` option"))
      \= (if-let [v (:read-eval ctx)]
           (do
             (r/read-char reader) ;; ignore =
             (let [next-val (parse-next ctx reader)]
               (if (true? v)
                 (list 'edamame.core/read-eval next-val)
                 (v next-val))))
           (throw-reader
            ctx reader
            "Read-eval not allowed. Use the `:read-eval` option"))
      \{ (parse-set ctx reader)
      \_ (do
           (r/read-char reader) ;; read _
           (let [uneval-fn (:uneval ctx)
                 uneval (parse-next ctx reader)]
             (if uneval-fn
               (let [val-val (parse-next ctx reader)]
                 (if (identical? eof val-val)
                   eof
                   (uneval-fn {:uneval uneval :next val-val})))
               continue)))
      \? (do
           (when-not (:read-cond ctx)
             (throw-reader
              ctx reader
              "Conditional read not allowed."))
           (r/read-char reader) ;; ignore ?
           (parse-reader-conditional ctx reader))
      \: (do
           (r/read-char reader) ;; ignore :
           (parse-namespaced-map ctx reader))
      \! (do
           (parse-comment reader)
           continue)
      \# (do
           (r/read-char reader)
           (read-symbolic-value reader nil nil))
      \^ (do
           (r/read-char reader) ;; ignore ^
           (let [meta-val (parse-next ctx reader true)
                 val-val (vary-meta (parse-next ctx reader)
                                    merge meta-val)]
             val-val))
      ;; catch-all
      (if (dispatch-macro? c)
        (do (r/unread reader \#)
            (edn-read ctx reader))
        ;; reader tag
        (let [suppress? (:suppress-read ctx)]
          (if suppress?
            (tagged-literal (parse-next ctx reader)
                            ;; read form
                            (parse-next ctx reader))
            (let [sym (parse-next ctx reader)
                  data (parse-next ctx reader)
                  f (or (when-let [readers (:readers ctx)]
                          (readers sym))
                        #?(:cljs (*cljs-data-readers* sym)
                           :default (default-data-readers sym)))]
              (if f (f data)
                  (throw (new #?(:clj Exception :cljs js/Error :cljd Exception :cljr Exception)
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
    (if-let [mf (:map ctx)]
      (apply mf elements)
      (do (when (pos? c)
            (when (odd? c)
              (throw-odd-map ctx reader start-loc elements))
            (let [ks (take-nth 2 elements)]
              (when-not (apply distinct? ks)
                (throw-dup-keys ctx reader start-loc :map ks))))
          (if (<= c 16)
            (apply #?(:cljd hash-map :default array-map) elements)
            (apply hash-map elements))))))

(defn parse-keyword [ctx #?(:cljs ^not-native reader :default reader)]
  (r/read-char reader) ;; ignore :
  (let [init-c (r/read-char reader)]
    (when (whitespace? init-c)
      (throw-reader ctx reader "Invalid token: :"))
    (let [^String token (read-token reader :keyword init-c)]
      (if (str/blank? token)
        (throw-reader ctx reader "Invalid keyword: :")
        (let [s (parse-symbol token)]
          (if s
            (let [auto-resolve? (identical? \: #?(:clj (.charAt token 0) :cljs (.charAt token 0) :cljd (nth token 0) :cljr (.get_Chars token 0)))]
              (if auto-resolve?
                (let [token (if auto-resolve? (subs token 1) token)
                      [token-ns token-name] s]
                  (if token-ns
                    (let [f (get-auto-resolve ctx reader token)
                          kns (auto-resolve ctx f (symbol (subs token-ns 1)) reader token-ns)]
                      (keyword (str kns) token-name))
                    ;; resolve current ns
                    (let [f (get-auto-resolve ctx reader token "Use `:auto-resolve` + `:current` to resolve current namespace.")
                          kns (auto-resolve ctx f :current reader token "Use `:auto-resolve` + `:current` to resolve current namespace.")]
                      (keyword (str kns) (subs token-name 1)))))
                (keyword token)))
            (throw-reader ctx reader (str "Invalid keyword: :"  token "."))))))))

(defn desugar-meta
  "Resolves syntactical sugar in metadata" ;; could be combined with some other desugar?
  ([f]
   (cond
     (keyword? f) {f true}
     (symbol? f)  {:tag f}
     (string? f)  {:tag f}
     (vector? f)  {:param-tags f}
     :else        f))
  ([f postprocess]
   (cond
     (keyword? f) {(postprocess f) (postprocess true)}
     (symbol? f)  {(postprocess :tag) (postprocess f)}
     (string? f)  {(postprocess :tag) (postprocess f)}
     (vector? f)  {(postprocess :param-tags) (postprocess f)}
     :else        f)))

;; NOTE: I tried optimizing for the :all option by dispatching to a function
;; that doesn't do any checking, but saw no significant speedup.
(defn dispatch
  [ctx #?(:cljs ^not-native reader :default reader) c]
  (let [sharp? (#?(:cljd = :default identical?) \# c)]
    (if sharp? (do
                 (r/read-char reader) ;; ignore sharp
                 (parse-sharp ctx reader))
        (case c
          nil eof
          \@ (if-let [v (:deref ctx)]
               (do
                 (r/read-char reader) ;; skip @
                 (let [next-val (parse-next ctx reader)]
                   (if (true? v)
                     (list 'clojure.core/deref next-val)
                     (v next-val))))
               (throw-reader
                ctx reader
                "Deref not allowed. Use the `:deref` option"))
          \' (if-let [v (:quote ctx)]
               (do
                 (r/read-char reader) ;; skip '
                 (let [next-val (parse-next ctx reader)]
                   (when (identical? eof next-val)
                     (throw-eof-while-reading ctx reader))
                   (if (true? v)
                     (list 'quote next-val)
                     (v next-val))))
               ;; quote is allowed in normal EDN
               (edn-read ctx reader))
          \` (if-let [v (:syntax-quote ctx)]
               (do
                 (r/read-char reader) ;; skip `
                 (let [next-val (parse-next ctx reader)]
                   (if (or (true? v) (map? v))
                     (let [gensyms (atom {})
                           ctx (assoc ctx :gensyms gensyms)
                           ret (syntax-quote ctx reader next-val)]
                       ret)
                     (v next-val))))
               (throw-reader
                ctx reader
                "Syntax quote not allowed. Use the `:syntax-quote` option"))
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
                        (if (true? v)
                          (list 'clojure.core/unquote-splicing next-val)
                          (v next-val))))
                    (throw-reader
                     ctx reader
                     "Syntax unquote splice not allowed. Use the `:syntax-quote` option"))
                  (let [next-val (parse-next ctx reader)]
                    (if (true? v)
                      (list 'clojure.core/unquote next-val)
                      (v next-val))))))
            (throw-reader
             ctx reader
             "Syntax unquote not allowed. Use the `:syntax-quote` option"))
          \( (parse-list ctx reader)
          \[ (parse-to-delimiter ctx reader \])
          \{ (parse-map ctx reader)
          (\} \] \)) (let [delims (:delims ctx)
                           expected (:expected delims)]
                       (if (not= expected c)
                         (let [loc (when (r/indexing-reader? reader)
                                     (location reader))]
                           (r/read-char reader) ;; ignore unexpected
                           ;; delimiter to be able to
                           ;; continue reading, fix for
                           ;; babashka socket REPL
                           (throw-reader ctx reader
                                         (str "Unmatched delimiter: " c
                                              (when expected
                                                (str ", expected: " expected
                                                     (when-let [{:keys [:row :col :char]} delims]
                                                       (str " to match " char " at " [row col])))))
                                         (let [{:keys [:char :row :col]} delims]
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

(defn buf [reader]
  #?(:cljd (r/log-string reader)
     :clj (:buffer @(.source-log-frames ^clojure.tools.reader.reader_types.SourceLoggingPushbackReader reader))
     :cljs (:buffer @(.-frames reader))
     :cljr (:buffer @(.source-log-frames ^clojure.tools.reader.reader_types.SourceLoggingPushbackReader reader))))

(defn parse-next
  ([ctx reader] (parse-next ctx reader nil))
  ([ctx reader desugar]
   (let [ir? (r/indexing-reader? reader)]
     (if-let [c (and (skip-whitespace ctx reader)
                     (r/peek-char reader))]
       (let [start-row (when ir? (r/get-line-number reader))
             start-col (when ir? (r/get-column-number reader))
             log? (:source ctx)
             #?(:cljd buf :default ^StringBuilder buf) (when log? #?(:cljd nil :default (buf reader)))
             offset (when log? #?(:clj (.length buf)
                                  :cljs (.getLength buf)
                                  :cljd (r/current-index reader)
                                  :cljr (.Length buf)))
             obj (if log?
                   #?(:clj (r/log-source reader (dispatch ctx reader c))
                      :cljs (r/log-source* reader #(dispatch ctx reader c))
                      :cljd (dispatch ctx reader c)
                      :cljr (r/log-source reader (dispatch ctx reader c)))
                   (dispatch ctx reader c))]
         (if (identical? continue obj)
           (recur ctx reader desugar)
           (if (identical? expected-delimiter obj)
             obj
             (let [auto-resolve-ns (:auto-resolve-ns ctx)
                   _ (when auto-resolve-ns
                       (when-let [ns-parsed (when (and (seq? obj)
                                                       (= 'ns (first obj)))
                                              (try (ns-parser/parse-ns-form obj)
                                                   (catch Exception _ nil)))]
                         (when-let [ns-state (:ns-state ctx)]
                           (reset! ns-state (assoc (:aliases ns-parsed) :current (:current ns-parsed))))))
                   postprocess (:postprocess ctx)
                   location? (:location? ctx)
                   end-loc? (:end-location ctx)
                   iobj?? (iobj? obj)
                   src (when log?
                         #?(:clj (.trim (subs (str buf) offset))
                            :cljs (.trim (subs (str buf) offset))
                            :cljd (.trim (r/source-subs reader offset (r/current-index reader)))
                            :cljr (.Trim (subs (str buf) offset))))
                   loc? (and ir? (or (and iobj??
                                          (location? obj))
                                     postprocess))
                   end? (and ir? loc? end-loc?)
                   row (when loc? start-row)
                   end-row (when end? (r/get-line-number reader))
                   col (when loc? start-col)
                   end-col (when end? (r/get-column-number reader))
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
                   obj (cond postprocess-fn (postprocess-fn obj)
                             loc? (let [m (meta obj)
                                        m (-> m
                                              (assoc (:row-key ctx) row)
                                              (assoc (:col-key ctx) col))
                                        m (if end-loc?
                                            (-> m
                                                (assoc (:end-row-key ctx) end-row)
                                                (assoc (:end-col-key ctx) end-col))
                                            m)
                                        m (if src
                                            (assoc m (:source-key ctx) src)
                                            m)]
                                    (with-meta obj m))
                             :else obj)]
               obj))))
       eof))))

#?(:cljd nil
   :clj
   ;; Single deftype replacing the IndexingPushbackReader ->
   ;; PushbackReader -> StringReader stack of tools.reader: reads with
   ;; .charAt and keeps line/column bookkeeping in unboxed mutable
   ;; fields. Semantics (incl. \return\newline normalization and unread
   ;; behavior) mirror tools.reader's readers.
   (deftype IndexingStringReader
       [^String s
        ^long s-len
        ^:unsynchronized-mutable ^long s-pos
        ^:unsynchronized-mutable pushback
        ^:unsynchronized-mutable ^long line
        ^:unsynchronized-mutable ^long column
        ^:unsynchronized-mutable line-start?
        ^:unsynchronized-mutable prev
        ^:unsynchronized-mutable ^long prev-column
        ^:unsynchronized-mutable normalize?]
     r/Reader
     (read-char [_]
       (let [ch (if-some [pb pushback]
                  (do (set! pushback nil) pb)
                  (when (< s-pos s-len)
                    (let [c (.charAt s (unchecked-int s-pos))]
                      (set! s-pos (unchecked-inc s-pos))
                      c)))]
         (when ch
           (let [ch (if normalize?
                      (do (set! normalize? false)
                          (if (or (identical? \newline ch)
                                  (identical? \formfeed ch))
                            ;; second char of \r\n / \r\f, skip it
                            (if-some [pb pushback]
                              (do (set! pushback nil) pb)
                              (when (< s-pos s-len)
                                (let [c (.charAt s (unchecked-int s-pos))]
                                  (set! s-pos (unchecked-inc s-pos))
                                  c)))
                            ch))
                      ch)
                 ch (if (identical? \return ch)
                      (do (set! normalize? true)
                          \newline)
                      ch)]
             (set! prev line-start?)
             (set! line-start? (or (nil? ch) (identical? \newline ch)))
             (when line-start?
               (set! prev-column column)
               (set! column 0)
               (set! line (unchecked-inc line)))
             (set! column (unchecked-inc column))
             ch))))
     (peek-char [_]
       (if-some [pb pushback]
         pb
         (when (< s-pos s-len)
           (.charAt s (unchecked-int s-pos)))))
     r/IPushbackReader
     (unread [_ ch]
       (if line-start?
         (do (set! line (unchecked-dec line))
             (set! column prev-column))
         (set! column (unchecked-dec column)))
       (set! line-start? prev)
       (let [ch (if normalize?
                  (do (set! normalize? false)
                      (if (identical? \newline ch)
                        \return
                        ch))
                  ch)]
         (when ch
           (when pushback
             (throw (RuntimeException. "Pushback buffer is full")))
           (set! pushback ch))))
     r/IndexingReader
     (get-line-number [_] (int line))
     (get-column-number [_] (int column))
     (get-file-name [_] nil)
     IFastOps
     ;; Token fast path: a token never contains newlines, so it is a
     ;; substring of s and bookkeeping is a column increment. Returns
     ;; nil when the fast path doesn't apply (caller falls back to the
     ;; generic char-by-char loop).
     (readToken [this initch]
       (if (or (nil? initch)
               (whitespace? initch)
               (macro-terminating? initch))
         (do (when initch (r/unread this initch))
             "")
         (when (and (nil? pushback)
                    (pos? s-pos)
                    (.equals ^Object initch
                             (Character/valueOf (.charAt s (unchecked-int (unchecked-dec s-pos))))))
           (let [start (unchecked-dec s-pos)]
             (loop [pos s-pos]
               (if (< pos s-len)
                 (let [ci (unchecked-int (.charAt s (unchecked-int pos)))]
                   (if (or (case ci
                             ;; macro-terminating? and \,
                             (34 59 64 94 96 126 40 41 91 93 123 125 92 44) true
                             false)
                           (Character/isWhitespace (char ci)))
                     (.finishToken this start pos)
                     (recur (unchecked-inc pos))))
                 (.finishToken this start pos)))))))
     (finishToken [_ start end]
       (let [n (unchecked-subtract end s-pos)]
         (when (pos? n)
           (set! prev false)
           (set! line-start? false)
           (set! column (unchecked-add column n))
           (set! s-pos end))
         (.substring s (unchecked-int start) (unchecked-int end))))
     ;; String fast path, entered with the reader positioned at the
     ;; opening double quote: strings without escapes and without
     ;; \return are substrings of s. One scan to the closing quote,
     ;; tracking newlines for the line/column bookkeeping. Returns nil
     ;; when the fast path doesn't apply (nothing consumed, caller
     ;; falls back to the generic loop).
     (readString [_]
       (when (and (nil? pushback)
                  (not normalize?))
         (let [start s-pos]
           (loop [pos (unchecked-inc start)
                  ln line
                  last-nl -1]
             (if (< pos s-len)
               (let [ci (unchecked-int (.charAt s (unchecked-int pos)))]
                 (cond
                   (== ci 34) ;; closing quote: commit
                   (do (set! line ln)
                       (if (neg? last-nl)
                         (set! column (unchecked-add column (unchecked-subtract (unchecked-inc pos) start)))
                         (set! column (unchecked-inc (unchecked-subtract pos last-nl))))
                       (set! prev (== last-nl (unchecked-dec pos)))
                       (set! line-start? false)
                       (set! s-pos (unchecked-inc pos))
                       (.substring s (unchecked-int (unchecked-inc start)) (unchecked-int pos)))
                   (== ci 92) nil ;; backslash: escapes
                   (== ci 13) nil ;; \return: normalization
                   (== ci 10) ;; \newline
                   (recur (unchecked-inc pos) (unchecked-inc ln) pos)
                   :else
                   (recur (unchecked-inc pos) ln last-nl)))
               nil)))))
     ;; Whitespace fast path with the same line/column bookkeeping as
     ;; read-char, including treating \return[\newline|\formfeed] as a
     ;; single logical newline.
     (skipWhitespace [this]
       (loop [read :none]
         (cond
           (some? pushback)
           (if (whitespace? pushback)
             (do (r/read-char this) (recur :some))
             read)
           normalize?
           (if-some [c (r/read-char this)]
             (if (whitespace? c)
               (recur :some)
               (do (r/unread this c) read))
             nil)
           :else
           (loop [pos s-pos
                  ln line
                  col column
                  pcol prev-column
                  ls line-start?
                  pv prev
                  read read]
             (if (< pos s-len)
               (let [ci (unchecked-int (.charAt s (unchecked-int pos)))]
                 (cond
                   (== ci 13) ;; \return: logical newline, skip paired \newline/\formfeed
                   (let [pos1 (unchecked-inc pos)
                         pos2 (if (and (< pos1 s-len)
                                       (let [c2 (unchecked-int (.charAt s (unchecked-int pos1)))]
                                         (or (== c2 10) (== c2 12))))
                                (unchecked-inc pos1)
                                pos1)]
                     (recur pos2 (unchecked-inc ln) 1 col true ls :some))
                   (== ci 10) ;; \newline
                   (recur (unchecked-inc pos) (unchecked-inc ln) 1 col true ls :some)
                   (or (== ci 44) ;; \,
                       (Character/isWhitespace (char ci)))
                   (recur (unchecked-inc pos) ln (unchecked-inc col) pcol false ls :some)
                   :else
                   (do (set! s-pos pos)
                       (set! line ln)
                       (set! column col)
                       (set! prev-column pcol)
                       (set! line-start? ls)
                       (set! prev pv)
                       read)))
               (do (set! s-pos pos)
                   (set! line ln)
                   (set! column col)
                   (set! prev-column pcol)
                   (set! line-start? ls)
                   (set! prev pv)
                   nil))))))
     java.io.Closeable
     (close [_])))

(defn string-reader
  "Create reader for strings."
  [s]
  #?(:cljd (r/indexing-push-back-reader
            (r/string-push-back-reader s))
     :clj (IndexingStringReader. s (.length ^String s) 0 nil 1 1 true nil 0 false)
     :default (r/indexing-push-back-reader
               (r/string-push-back-reader s))))

(defrecord Options [dispatch deref syntax-quote unquote
                    unquote-splicing quote fn var
                    read-eval regex
                    row-key col-key
                    end-row-key end-col-key
                    source source-key
                    postprocess location?
                    end-location
                    ns-state suppress-read
                    ;; :map is deliberately not a field: on ClojureDart
                    ;; a record field named map conflicts with Dart's
                    ;; Map.map method
                    read-cond features auto-resolve auto-resolve-ns
                    readers uneval set
                    delims fn-literal gensyms])

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
               (not (:location? opts)) (assoc :location? (fn [_] true))
               (not (:row-key opts)) (assoc :row-key :row)
               (not (:col-key opts)) (assoc :col-key :col)
               (not (:end-row-key opts)) (assoc :end-row-key :end-row)
               (not (:end-col-key opts)) (assoc :end-col-key :end-col)
               (not (:source-key opts)) (assoc :source-key :source)
               (not (contains? opts :end-location)) (assoc :end-location true))
        opts (assoc opts :ns-state (atom nil))]
    (map->Options opts)))

(defn parse-string [s opts]
  (let [opts (normalize-opts opts)
        src? (:source opts)
        r (if src? (r/source-logging-push-back-reader s)
              (string-reader s))
        ctx (assoc opts :delims nil)
        v (parse-next ctx r)]
    (if (identical? eof v) nil v)))

(defn parse-string-all [s opts]
  (let [opts (normalize-opts opts)
        src? (:source opts)
        r (if src? (r/source-logging-push-back-reader s)
              (string-reader s))
        ctx (assoc opts :delims nil)]
    (loop [ret (transient [])]
      (let [next-val (parse-next ctx r)]
        (if (identical? eof next-val)
          (persistent! ret)
          (recur (conj! ret next-val)))))))

(defn reader
  [x]
  #?(:clj (if (string? x)
            (string-reader x)
            (r/indexing-push-back-reader (r/push-back-reader x)))
     :cljs (let [string-reader (r/string-reader x)
                 buf-len 1
                 pushback-reader (r/PushbackReader. string-reader
                                                    (object-array buf-len)
                                                    buf-len buf-len)]
             (r/indexing-push-back-reader pushback-reader))
     :cljd (r/indexing-push-back-reader (r/push-back-reader x))
     :cljr (r/indexing-push-back-reader (r/push-back-reader x))))

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
             (r/source-logging-push-back-reader pushback-reader))
     :cljd (r/source-logging-push-back-reader x)
     :cljr (r/source-logging-push-back-reader (r/push-back-reader x))))

;;;; Scratch

(comment
  )

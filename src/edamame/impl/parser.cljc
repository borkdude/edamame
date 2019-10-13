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
   [clojure.string :as s])
  #?(:clj (:import [java.io Closeable]))
  #?(:cljs (:import [goog.string StringBuffer])))

#?(:clj (set! *warn-on-reflection* true))

(declare parse-next)

(defn parse-comment
  [#?(:cljs ^not-native reader :default reader)]
  (r/read-line reader)
  reader)

(defn throw-reader
  "Throw reader exception, including line line/column. line/column is
  read from the reader but it can be overriden by passing loc
  optional parameter."
  ([#?(:cljs ^:not-native reader :default reader) msg]
   (throw-reader reader msg nil))
  ([#?(:cljs ^:not-native reader :default reader) msg data]
   (throw-reader reader msg data nil))
  ([#?(:cljs ^:not-native reader :default reader) msg data loc]
   (let [c (:col loc (r/get-column-number reader))
         l (:row loc (r/get-line-number reader))]
     (throw
      (ex-info
       (str msg
            " [at line " l ", column " c "]")
       (merge {:row l, :col c} data))))))

(defn parse-to-delimiter
  ([ctx #?(:cljs ^not-native reader :default reader) delimiter]
   (parse-to-delimiter ctx reader delimiter []))
  ([ctx #?(:cljs ^not-native reader :default reader) delimiter into]
   (let [row (r/get-line-number reader)
         col (r/get-column-number reader)
         opened (r/read-char reader)]
     (let [ctx (assoc ctx ::expected-delimiter delimiter)]
       (loop [vals (transient into)]
         (let [;; if next-val is uneval, we get back the expected delimiter...
               next-val (parse-next ctx reader)]
           (cond
             (#?(:clj identical? :cljs keyword-identical?) ::eof next-val)
             (throw-reader
              reader
              (str "EOF while reading, expected " delimiter " to match " opened " at [" row "," col "]"))
             (#?(:clj identical? :cljs keyword-identical?) ::expected-delimiter next-val)
             (persistent! vals)
             :else
             (recur (conj! vals next-val)))))))))

(defn parse-list [ctx #?(:cljs ^not-native reader :default reader)]
  (apply list (parse-to-delimiter ctx reader \))))

(defn read-regex-pattern
  "Modeled after tools.reader/read-regex."
  [_ctx #?(:cljs ^not-native reader :default reader)]
  (r/read-char reader) ;; ignore leading double-quote
  (let [sb #?(:clj (StringBuilder.)
              :cljs (goog.string.StringBuffer.))]
    (loop [ch (r/read-char reader)]
      (if (identical? \" ch)
        #?(:clj (str sb)
           :cljs (str sb))
        (if (nil? ch)
          (throw-reader reader "Error while parsing regex")
          (do
            (.append sb ch )
            (when (identical? \\ ch)
              (let [ch (r/read-char reader)]
                (when (nil? ch)
                  (throw-reader reader "Error while parsing regex"))
                (.append sb ch)))
            (recur (r/read-char reader))))))))

(defn handle-dispatch
  [ctx #?(:cljs ^not-native reader :default reader) c sharp? f]
  (let [regex? (and sharp? (identical? \" c))
        next-val (if regex?
                   (read-regex-pattern ctx reader)
                   (parse-next ctx reader))]
    (f next-val)))

(defn delimiter? [c]
  (case c
    (\{ \( \[ \") true
    false))

(defn location [#?(:cljs ^not-native reader :default reader)]
  {:row (r/get-line-number reader)
   :col (r/get-column-number reader)})

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
  [#?(:cljs ^not-native reader :default reader) loc kind ks]
  (throw-reader
   reader
   (duplicate-keys-error
    (str (s/capitalize (name kind)) " literal contains duplicate key")
    ks)
   nil
   loc))

(defn parse-set
  [ctx #?(:cljs ^not-native reader :default reader)]
  (let [start-loc (location reader)
        coll (parse-to-delimiter ctx reader \})
        the-set (set coll)]
    (when-not (= (count coll) (count the-set))
      (throw-dup-keys reader start-loc :set coll))
    the-set))

(defn parse-sharp
  [ctx #?(:cljs ^not-native reader :default reader)]
  (r/read-char reader) ;; ignore sharp
  (let [c (r/peek-char reader)]
    (if-let [f (or (get-in ctx [:dispatch \# c]))]
      (do
        (when-not (delimiter? c)
          (r/read-char reader))
        (handle-dispatch ctx reader c true f))
      (case c
        nil (throw-reader reader (str "Unexpected EOF."))
        \{ (parse-set ctx reader)
        \( (parse-list ctx reader)
        \_ (do
             ;; ignore next form and return the one after it
             (edn/read ctx reader)
             (parse-next ctx reader))
        (do
          (r/unread reader \#)
          (edn/read ctx reader))))))

(defn throw-odd-map
  [#?(:cljs ^not-native reader :default reader) loc elements]
  (throw-reader
   reader
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
        (throw-odd-map reader start-loc elements))
      (let [ks (take-nth 2 elements)]
        (when-not (apply distinct? ks)
          (throw-dup-keys reader start-loc :map ks))))
    (apply hash-map elements)))

(defn parse-unquote-splice [])

(defn dispatch
  [{:keys [:dispatch] :as ctx} #?(:cljs ^not-native reader :default reader) path]
  (let [sharp? (= [\#] path)]
    (if sharp? (parse-sharp ctx reader)
        (if-let [[c f]
                 (or (when-let [v (get-in dispatch path)]
                       [(last path) v])
                     (when-let [v (get-in dispatch (conj (pop path) :default))]
                       [nil v]))]
          (cond
            (map? f) (do (r/read-char reader)
                         (recur ctx reader
                                (conj path (r/peek-char reader))))
            :else
            (do
              (when c
                (r/read-char reader))
              (handle-dispatch ctx reader c false f)))
          (let [c (last path)]
            (case c
              nil ::eof
              \( (parse-list ctx reader)
              \[ (parse-to-delimiter ctx reader \])
              \{ (parse-map ctx reader)
              (\} \] \)) (let [expected (::expected-delimiter ctx)]
                           (if (not= expected c)
                             (throw-reader reader
                                           (str "Unmatched delimiter: " c ", expected: " expected ".")
                                           ctx)
                             (do
                               (r/read-char reader) ;; read delimiter
                               ::expected-delimiter)))
              \; (parse-comment reader)
              (edn/read ctx reader)))))))

(defn whitespace?
  [#?(:clj ^java.lang.Character c :default c)]
  #?(:clj (and c (or (= c \,) (Character/isWhitespace c)))
     :cljs (and c (< -1 (.indexOf #js [\return \newline \tab \space ","] c)))))

(defn parse-whitespace
  [_ctx #?(:cljs ^not-native reader :default reader)]
  (loop []
    (let [c (r/read-char reader)]
      (if (whitespace? c)
        (recur)
        (do (r/unread reader c)
            reader)))))

(defn parse-next [ctx reader]
  (parse-whitespace ctx reader) ;; skip leading whitespace
  (if-let [c (r/peek-char reader)]
    (let [loc (location reader)
          obj (dispatch ctx reader [c])]
      (if (identical? reader obj)
        (parse-next ctx reader)
        (if #?(:clj
               (instance? clojure.lang.IObj obj)
               :cljs (satisfies? IWithMeta obj))
          (vary-meta obj merge loc)
          obj)))
    ::eof))

(defn string-reader
  "Create reader for strings."
  [s]
  (r/indexing-push-back-reader
   (r/string-push-back-reader s)))

(defn parse-string [s opts]
  (let [^Closeable r (string-reader s)
        ctx (assoc opts ::expected-delimiter nil)
        v (parse-next ctx r)]
    (if (identical? ::eof v) nil v)))

(defn parse-string-all [s opts]
  (let [^Closeable r (string-reader s)
        ctx (assoc opts ::expected-delimiter nil)]
    (loop [ret (transient [])]
      (let [next-val (parse-next ctx r)]
        (if (#?(:clj identical? :cljs keyword-identical?) ::eof next-val)
          (persistent! ret)
          (recur (conj! ret next-val)))))))

;;;; Scratch

(comment
  )

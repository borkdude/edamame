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

(defn parse-to-delimiter
  ([ctx #?(:cljs ^not-native reader :default reader) delimiter]
   (parse-to-delimiter ctx reader delimiter []))
  ([ctx #?(:cljs ^not-native reader :default reader) delimiter into]
   (r/read-char reader) ;; ignore delimiter
   (let [ctx (assoc ctx ::expected-delimiter delimiter)]
     (loop [vals (transient into)]
       (let [next-val (parse-next ctx reader)]
         (if (#?(:clj identical? :cljs keyword-identical?) ::expected-delimiter next-val)
           (persistent! vals)
           (recur (conj! vals next-val))))))))

(defn parse-list [ctx #?(:cljs ^not-native reader :default reader)]
  (apply list (parse-to-delimiter ctx reader \))))

(defn throw-reader
  "Throw reader exception, including line line/column."
  ([#?(:cljs ^:not-native reader :default reader) msg]
   (throw-reader reader msg nil))
  ([#?(:cljs ^:not-native reader :default reader) msg data]
   (let [c (r/get-column-number reader)
         l (r/get-line-number reader)]
     (throw
      (ex-info
       (str msg
            " [at line " l ", column " c "]")
       (merge {:row l, :col c} data))))))

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
  [#?(:cljs ^not-native reader :default reader) kind ks]
  (throw-reader
   reader
   (duplicate-keys-error
    (str (s/capitalize (name kind)) " literal contains duplicate key")
    ks)))

(defn parse-set
  [ctx #?(:cljs ^not-native reader :default reader)]
  (let [coll (parse-to-delimiter ctx reader \})
        the-set (set coll)]
    (when-not (= (count coll) (count the-set))
      (throw-dup-keys reader :set coll))
    the-set))

(defn parse-sharp
  [ctx #?(:cljs ^not-native reader :default reader)]
  (r/read-char reader) ;; ignore sharp
  (let [c (r/peek-char reader)]
    (if-let [f (get-in ctx [:dispatch \# c])]
      (do
        (when-not (delimiter? c)
          (r/read-char reader))
        (handle-dispatch ctx reader c true f))
      (case c
        nil (throw-reader reader "Unexpected EOF.")
        \{ (parse-set ctx reader)
        \( (parse-list ctx reader)
        (do
          (r/unread reader \#)
          (edn/read ctx reader))))))

(defn throw-odd-map
  [#?(:cljs ^not-native reader :default reader) elements]
  (throw-reader
   reader
   (str
    "The map literal starting with "
    (i/inspect (first elements))
    " contains "
    (count elements)
    " form(s). Map literals must contain an even number of forms.")))

(defn parse-map
  [ctx #?(:cljs ^not-native reader :default reader)]
  (let [elements (parse-to-delimiter ctx reader \})
        ks (take-nth 2 elements)]
    (when (odd? (count elements))
      (throw-odd-map reader elements))
    (when-not (= (count (set ks)) (count ks))
      (throw-dup-keys reader :map ks))
    (apply hash-map elements)))

(defn dispatch
  [ctx #?(:cljs ^not-native reader :default reader) c]
  (let [f (get-in ctx [:dispatch c])]
    (if (fn? f)
      (do
        (r/read-char reader)
        (handle-dispatch ctx reader c false f))
      (case c
        nil ::eof
        \( (parse-list ctx reader)
        \[ (parse-to-delimiter ctx reader \])
        \{ (parse-map ctx reader)
        (\} \] \)) (let [expected (::expected-delimiter ctx)]
                     (if (not= expected c)
                       (throw-reader reader
                                     (str "Unmatched delimiter: " c)
                                     ctx)
                       (do
                         (r/read-char reader) ;; read delimiter
                         ::expected-delimiter)))
        \; (parse-comment reader)
        \# (parse-sharp ctx reader)
        (edn/read ctx reader)))))

(defn location [#?(:cljs ^not-native reader :default reader)]
  {:row (r/get-line-number reader)
   :col (r/get-column-number reader)})

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
  (let [c (r/peek-char reader)
        loc (location reader)
        obj (dispatch ctx reader c)]
    (if (identical? reader obj)
      (parse-next ctx reader)
      (if #?(:clj
             (instance? clojure.lang.IObj obj)
             :cljs (satisfies? IWithMeta obj))
        (vary-meta obj merge loc)
        obj))))

(defn string-reader
  "Create reader for strings."
  [s]
  (r/indexing-push-back-reader
   (r/string-push-back-reader s)))

(defn parse-string [s opts]
  (let [^Closeable r (string-reader s)
        ctx (assoc opts ::expected-delimiter nil)]
    (parse-next ctx r)))

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

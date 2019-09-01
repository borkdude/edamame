(ns edamame.impl.parser
  "This code is largely inspired by rewrite-clj(sc), so thanks to all
  who contribured to those projects."
  {:no-doc true}
  (:require
   #?(:clj  [edamame.impl.toolsreader.v1v3v2.clojure.tools.reader.edn :as edn]
      :cljs [edamame.impl.toolsreader.v1v3v2.cljs.tools.reader.edn :as edn])
   #?(:clj  [edamame.impl.toolsreader.v1v3v2.clojure.tools.reader.reader-types :as r]
      :cljs [edamame.impl.toolsreader.v1v3v2.cljs.tools.reader.reader-types :as r]))
  #?(:clj (:import [java.io Closeable])))

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

(defn handle-dispatch
  [ctx #?(:cljs ^not-native reader :default reader) f]
  (let [next-val (parse-next ctx reader)]
    (f next-val)))

(defn delimiter? [c]
  (case c
    (\{ \( \[ \") true
    false))

(defn parse-sharp
  [ctx #?(:cljs ^not-native reader :default reader)]
  (r/read-char reader) ;; ignore sharp
  (let [c (r/peek-char reader)]
    (if-let [f (get-in ctx [:dispatch \# c])]
      (do
        (when-not (delimiter? c)
          (r/read-char reader))
        (handle-dispatch ctx reader f))
      (case c
        nil (throw-reader reader "Unexpected EOF.")
        \{ (parse-to-delimiter ctx reader \} #{})
        \( (parse-list ctx reader)
        (do
          (r/unread reader \#)
          (edn/read ctx reader))))))

(defn dispatch
  [ctx #?(:cljs ^not-native reader :default reader) c]
  (let [f (get-in ctx [:dispatch c])]
    (if (fn? f)
      (do
        (r/read-char reader)
        (handle-dispatch ctx reader f))
      (case c
        nil ::eof
        \( (parse-list ctx reader)
        \[ (parse-to-delimiter ctx reader \])
        \{ (apply hash-map (parse-to-delimiter ctx reader \}))
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

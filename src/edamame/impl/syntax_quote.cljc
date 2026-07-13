(ns edamame.impl.syntax-quote
  "Taken and adapted from
  https://github.com/clojure/tools.reader/blob/master/src/main/clojure/clojure/tools/reader.clj"
  {:no-doc true}
  (:require
   [clojure.string :as str]))

;; cljd special forms per cljd.reader, plus catch finally &
#?(:cljd
   (defn- special-symbol? [s]
     (contains? '#{def if do let* quote var fn* loop* recur throw try
                   catch finally . new set! & ns letfn letfn* case*
                   deftype* reify* defprotocol* defmethod* defmulti*
                   extend-type-protocol*}
                s)))

(defn- record?* [x]
  #?(:clj (instance? clojure.lang.IRecord x)
     :cljs (record? x)
     :cljd (record? x)
     :cljr (instance? clojure.lang.IRecord x)))

(defn- regex?* [x]
  #?(:clj (instance? java.util.regex.Pattern x)
     :cljs (regexp? x)
     :cljd (dart/is? x RegExp)
     :cljr (instance? System.Text.RegularExpressions.Regex x)))

(defn- with-meta-able? [x]
  #?(:clj (instance? clojure.lang.IObj x)
     :cljs (implements? IWithMeta x)
     :cljd (satisfies? IWithMeta x)
     :cljr (instance? clojure.lang.IObj x)))

(defn unquote? [form]
  (and (seq? form)
       (= (first form) 'clojure.core/unquote)))

(defn- unquote-splicing? [form]
  (and (seq? form)
       (= (first form) 'clojure.core/unquote-splicing)))

(declare syntax-quote)

(defn- expand-list
  "Expand a list by resolving its syntax quotes and unquotes"
  [ctx #?(:cljs ^not-native reader :default reader) s]
  (loop [s (seq s) r (transient [])]
    (if s
      (let [item (first s)
            ret (conj! r
                       (cond
                         (unquote? item)          (list 'clojure.core/list (second item))
                         (unquote-splicing? item) (second item)
                         :else                    (list 'clojure.core/list (syntax-quote ctx reader item))))]
        (recur (next s) ret))
      (seq (persistent! r)))))

(defn- syntax-quote-coll [ctx #?(:cljs ^not-native reader :default reader) type coll]
  ;; We use sequence rather than seq here to fix http://dev.clojure.org/jira/browse/CLJ-1444
  ;; But because of http://dev.clojure.org/jira/browse/CLJ-1586 we still need to call seq on the form
  (let [res (list 'clojure.core/sequence
                  (list 'clojure.core/seq
                        (cons 'clojure.core/concat
                              (expand-list ctx reader coll))))]
    (if type
      (list 'clojure.core/apply type res)
      res)))

(defn map-func
  "Decide which map type to use, array-map if less than 16 elements"
  [coll]
  (if (>= (count coll) 16)
    'clojure.core/hash-map
    'clojure.core/array-map))

(defn- flatten-map
  "Flatten a map into a seq of alternate keys and values"
  [form]
  (loop [s (seq form) key-vals (transient [])]
    (if s
      (let [e (first s)]
        (recur (next s) (-> key-vals
                            (conj! (key e))
                            (conj! (val e)))))
      (seq (persistent! key-vals)))))

(defn- syntax-quote* [{:keys [:gensyms] :as ctx}
                     #?(:cljs ^not-native reader :default reader) form]
  (cond
    (special-symbol? form) (list 'quote form)
    (symbol? form)
    (list 'quote
          (let [sym-name (name form)]
            (cond (special-symbol? form) form
                  (str/ends-with? sym-name "#")
                  (if-let [generated (get @gensyms form)]
                    generated
                    (let [n (subs sym-name 0 (dec (count sym-name)))
                          generated (gensym (str n "__"))
                          generated (symbol (str (name generated) "__auto__"))]
                      (swap! gensyms assoc form generated)
                      generated))
                  :else
                  (let [f (-> ctx :syntax-quote :resolve-symbol)
                        f (or f
                              (if-let [ns-state (some-> ctx :ns-state deref)]
                                (fn [sym]
                                  (if-let [alias (some-> (namespace sym)
                                                      symbol)]
                                    (if-let [expanded-alias (ns-state alias)]
                                      (symbol (str expanded-alias) sym-name)
                                      sym)
                                    ;; bare symbol, matching Clojure's syntax-quote:
                                    ;; method, constructor and dotted symbols are
                                    ;; left as-is, except a trailing dot on an
                                    ;; imported class which resolves the class part.
                                    ;; Then refers, imports and the current ns.
                                    (cond
                                      (str/starts-with? sym-name ".") sym
                                      (str/ends-with? sym-name ".")
                                      (if-let [c (get (:imports ns-state)
                                                      (symbol (subs sym-name 0 (dec (count sym-name)))))]
                                        (symbol (str c "."))
                                        sym)
                                      (str/includes? sym-name ".") sym
                                      :else
                                      (or (get (:refers ns-state) sym)
                                          (some-> (get (:imports ns-state) sym) str symbol)
                                          (if-let [current (:current ns-state)]
                                            (symbol (str current) sym-name)
                                            sym)))))
                                identity))]
                    (f form)))))
    (unquote? form) (second form)
    (unquote-splicing? form) (throw #?(:clj (IllegalStateException. "unquote-splice not in list")
                                       :cljs (js/Error. "unquote-splice not in list")
                                       :cljd (Exception. "unquote-splice not in list")
                                       :cljr (InvalidOperationException. "unquote-splice not in list")))

    (coll? form)
    (cond
      (record?* form) form
      (map? form) (syntax-quote-coll ctx reader (map-func form) (flatten-map form))
      (vector? form) (list 'clojure.core/vec (syntax-quote-coll ctx reader nil form))
      (set? form) (syntax-quote-coll ctx reader 'clojure.core/hash-set form)
      (or (seq? form) (list? form))
      (let [seq (seq form)]
        (if seq
          (syntax-quote-coll ctx reader nil seq)
          '(clojure.core/list)))

      :else (throw #?(:clj (UnsupportedOperationException. "Unknown Collection type")
                      :cljs (js/Error. "Unknown Collection type")
                      :cljd (Exception. "Unknown Collection type")
                      :cljr (NotSupportedException. "Unknown Collection type"))))

    (or (keyword? form)
        (number? form)
        (char? form)
        (string? form)
        (nil? form)
        (boolean? form)
        (regex?* form))
    form
    :else (list 'quote form)))

(defn- add-meta [ctx reader form ret]
  (if (and (with-meta-able? form)
           (seq (dissoc (meta form) (:row-key ctx) (:col-key ctx) (:end-row-key ctx) (:end-col-key ctx))))
    (list #?(:cljs 'cljs.core/with-meta
             :default 'clojure.core/with-meta) ret (syntax-quote* ctx reader (meta form)))
    ret))

(defn syntax-quote [ctx reader form]
  (let [ret (syntax-quote* ctx reader form)]
    (add-meta ctx reader form ret)))

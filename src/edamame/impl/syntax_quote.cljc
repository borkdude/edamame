(ns edamame.impl.syntax-quote
  "Taken and adapted from
  https://github.com/clojure/tools.reader/blob/master/src/main/clojure/clojure/tools/reader.clj

  Optimized to avoid concat/sequence/seq scaffolding when no unquote-splicing
  is present. Based on https://github.com/frenchy64/backtick/pull/1"
  {:no-doc true}
  (:require [clojure.string :as str]))

(defn unquote? [form]
  (and (seq? form)
       (= (first form) 'clojure.core/unquote)))

(defn- unquote-splicing? [form]
  (and (seq? form)
       (= (first form) 'clojure.core/unquote-splicing)))

(defn- has-splice?
  "Check if any element in coll is an unquote-splicing form"
  [coll]
  (some unquote-splicing? coll))

(declare syntax-quote-inner)

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
                         :else                    (list 'clojure.core/list (syntax-quote-inner ctx reader item))))]
        (recur (next s) ret))
      (seq (persistent! r)))))

(defn- expand-list-simple
  "Expand a list without splices - just collect quoted elements"
  [ctx #?(:cljs ^not-native reader :default reader) s]
  (loop [s (seq s) r (transient [])]
    (if s
      (let [item (first s)
            ret (conj! r
                       (cond
                         (unquote? item) (second item)
                         :else           (syntax-quote-inner ctx reader item)))]
        (recur (next s) ret))
      (persistent! r))))

(defn- syntax-quote-coll [ctx #?(:cljs ^not-native reader :default reader) type coll]
  (if (has-splice? coll)
    ;; Has splices - use the full concat machinery
    (let [res (list 'clojure.core/sequence
                    (list 'clojure.core/seq
                          (cons 'clojure.core/concat
                                (expand-list ctx reader coll))))]
      (if type
        (list 'clojure.core/apply type res)
        res))
    ;; No splices - produce direct collection
    (let [elements (expand-list-simple ctx reader coll)]
      (cond
        (= type 'clojure.core/hash-set)
        (set elements)

        (= type 'clojure.core/hash-map)
        (apply hash-map elements)

        (= type 'clojure.core/array-map)
        (apply array-map elements)

        ;; For lists (type is nil), use list form
        (nil? type)
        (if (seq elements)
          (cons 'clojure.core/list elements)
          '(clojure.core/list))

        ;; Fallback for other types
        :else
        (list 'clojure.core/apply type (vec elements))))))

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
                                    sym))
                                identity))]
                    (f form)))))
    (unquote? form) (second form)
    (unquote-splicing? form) (throw (new #?(:clj IllegalStateException
                                         :cljs js/Error
                                         :cljr InvalidOperationException)
                                         "unquote-splice not in list"))

    (coll? form)
    (cond
      (instance? #?(:clj clojure.lang.IRecord :cljs IRecord :cljr clojure.lang.IRecord) form) form
      (map? form) (syntax-quote-coll ctx reader (map-func form) (flatten-map form))
      (vector? form)
      (if (has-splice? form)
        ;; Has splices - need vec + concat machinery
        (list 'clojure.core/vec (syntax-quote-coll ctx reader nil form))
        ;; No splices - direct vector literal
        (vec (expand-list-simple ctx reader form)))
      (set? form)
      (if (has-splice? form)
        ;; Has splices - need concat machinery
        (syntax-quote-coll ctx reader 'clojure.core/hash-set form)
        ;; No splices - direct set literal
        (set (expand-list-simple ctx reader form)))
      (or (seq? form) (list? form))
      (let [seq (seq form)]
        (if seq
          (syntax-quote-coll ctx reader nil seq)
          '(clojure.core/list)))

      :else (throw (new #?(:clj UnsupportedOperationException
                           :cljs js/Error
                           :cljr NotSupportedException) "Unknown Collection type")))

    (or (keyword? form)
        (number? form)
        (char? form)
        (string? form)
        (nil? form)
        (boolean? form)
        #?(:clj (instance? java.util.regex.Pattern form)
           :cljs (regexp? form)))
    form
    :else (list 'quote form)))

(defn- add-meta [ctx reader form ret]
  (if (and #?(:clj (instance? clojure.lang.IObj form)
              :cljs (implements? IWithMeta form))
           (seq (dissoc (meta form) (:row-key ctx) (:col-key ctx) (:end-row-key ctx) (:end-col-key ctx))))
    (list #?(:clj 'clojure.core/with-meta
             :cljs 'cljs.core/with-meta) ret (syntax-quote* ctx reader (meta form)))
    ret))

(defn syntax-quote-inner [ctx reader form]
  (let [ret (syntax-quote* ctx reader form)]
    (add-meta ctx reader form ret)))

(defn syntax-quote [ctx reader form]
  (let [ctx (assoc ctx :gensyms (atom {}))]
    (syntax-quote-inner ctx reader form)))

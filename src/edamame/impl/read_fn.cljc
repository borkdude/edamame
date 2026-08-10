(ns edamame.impl.read-fn
  {:no-doc true})

(defn walk*
  "Preserves metadata, unlike clojure.walk/walk."
  [inner outer form]
  (cond
    (list? form) (with-meta (outer (apply list (map inner form)))
                   (meta form))
    #?(:clj (instance? clojure.lang.IMapEntry form) :cljs (map-entry? form) :cljd (map-entry? form) :cljr (instance? clojure.lang.IMapEntry form))
    (outer #?(:clj (clojure.lang.MapEntry/create (inner (key form)) (inner (val form)))
              :cljs (MapEntry. (inner (key form)) (inner (val form)) nil)
              :cljd [(inner (key form)) (inner (val form))]
              :cljr (clojure.lang.MapEntry/create (inner (key form)) (inner (val form)))))
    (seq? form) (with-meta (outer (doall (map inner form)))
                  (meta form))
    #?(:clj (instance? clojure.lang.IRecord form)
       :cljs (record? form)
       :cljd (record? form)
       :cljr (instance? clojure.lang.IRecord form))
    (outer (reduce (fn [r x] (conj r (inner x))) form form))
    (coll? form) (outer (into (empty form) (map inner form)))
    :else (outer form)))

(defn postwalk*
  "Preserves metadata, unlike clojure.walk/postwalk."
  [f form]
  (walk* (partial postwalk* f) f form))

(defn read-fn
  "Expands a function literal to (fn* [%1# ...] ...).

  The params end in # so that a syntax quote gensyms them, like it does
  for x#, instead of resolving them as free symbols and namespace
  qualifying them. Their names are fixed, so reading stays deterministic."
  [expr]
  (let [state (volatile! {:max-fixed 0 :var-args? false})
        arg-sym (fn [n] (symbol (str "%" n "#")))
        var-args-sym '%&#
        expr (postwalk* (fn [elt]
                          (if (and (symbol? elt) (not (namespace elt)))
                            (if-let [[_ m] (re-matches #"^%(.*)" (name elt))]
                              (cond (empty? m)
                                    (do (vswap! state update :max-fixed max 1)
                                        (arg-sym 1))
                                    (= "&" m)
                                    (do (vswap! state assoc :var-args? true)
                                        var-args-sym)
                                    :else (let [n #?(:clj (Integer/parseInt m)
                                                     :cljs (js/parseInt m)
                                                     :cljd (int/parse m)
                                                     :cljr (Int32/Parse m))]
                                            (vswap! state update :max-fixed max n)
                                            (arg-sym n)))
                              elt)
                            elt))
                        expr)
        {:keys [:max-fixed :var-args?]} @state
        fixed-names (map arg-sym (range 1 (inc max-fixed)))
        arg-list (vec (concat fixed-names (when var-args?
                                            ['& var-args-sym])))]
    (list 'fn* arg-list expr)))

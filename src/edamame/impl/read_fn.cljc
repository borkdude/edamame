(ns edamame.impl.read-fn
  {:no-doc true})

(defn walk*
  "Preserves metadata, unlike clojure.walk/walk."
  [inner outer form]
  (cond
    (list? form) (with-meta (outer (apply list (map inner form)))
                   (meta form))
    #?(:clj (instance? clojure.lang.IMapEntry form) :cljs (map-entry? form))
    (outer #?(:clj (clojure.lang.MapEntry/create (inner (key form)) (inner (val form)))
              :cljs (MapEntry. (inner (key form)) (inner (val form)) nil)))
    (seq? form) (with-meta (outer (doall (map inner form)))
                  (meta form))
    #?(:clj (instance? clojure.lang.IRecord form)
       :cljs (record? form))
    (outer (reduce (fn [r x] (conj r (inner x))) form form))
    (coll? form) (outer (into (empty form) (map inner form)))
    :else (outer form)))

(defn postwalk*
  "Preserves metadata, unlike clojure.walk/postwalk."
  [f form]
  (walk* (partial postwalk* f) f form))

(defn read-fn [expr]
  (let [state (volatile! {:max-fixed 0 :var-args? false})
        expr (postwalk* (fn [elt]
                          (if (symbol? elt)
                            (if-let [[_ m] (re-matches #"^%(.*)" (name elt))]
                              (cond (empty? m)
                                    (do (vswap! state update :max-fixed max 1)
                                        '%1)
                                    (= "&" m)
                                    (do (vswap! state assoc :var-args? true)
                                        elt)
                                    :else (do (let [n #?(:clj (Integer/parseInt m)
                                                         :cljs (js/parseInt m))]
                                                (vswap! state update :max-fixed max n))
                                              elt))
                              elt)
                            elt))
                        expr)
        {:keys [:max-fixed :var-args?]} @state
        fixed-names (map #(symbol (str "%" %)) (range 1 (inc max-fixed)))
        var-args-sym '%&
        arg-list (vec (concat fixed-names (when var-args?
                                            ['& var-args-sym])))
        form (list 'fn* arg-list expr)]
    form))

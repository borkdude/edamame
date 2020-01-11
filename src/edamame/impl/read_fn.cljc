(ns edamame.impl.read-fn
  {:no-doc true}
  (:require [clojure.walk :refer [postwalk]]))

(defn read-fn [expr]
  (let [state (volatile! {:max-fixed 0 :var-args? false})
        expr (postwalk (fn [elt]
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

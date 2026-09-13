(require '[criterium.core :as crit]
         '[clojure.java.io :as io]
         '[edamame.core :as e])

(def edn-s
  (pr-str (vec (for [i (range 20000)]
                 {:id i
                  :name (str "name-" i)
                  :tags [:alpha :beta :gamma]
                  :score (double i)
                  :nested {:a [1 2 3] :b "text with spaces"}}))))

(def core-s (slurp "/Users/borkdude/dev/edamame/test-resources/clojure/core.clj"))

(def edn-no-loc (e/normalize-opts {:location? (fn [_] false)}))
(def edn-loc (e/normalize-opts {}))
(def sci-opts (e/normalize-opts {:all true :row-key :line :col-key :column :read-cond :allow
                                 :location? seq? :end-location false
                                 :auto-resolve '{:current user}}))
(def default-opts (e/normalize-opts {:all true :auto-resolve '{:current user}}))

(defn core-with [opts]
  (let [r (e/reader core-s)]
    (loop []
      (when-not (identical? :edamame.core/eof (e/parse-next r opts))
        (recur)))))

(doseq [[label f] [["edn, no location" #(e/parse-string edn-s edn-no-loc)]
                   ["edn, location" #(e/parse-string edn-s edn-loc)]
                   ["core.clj, sci opts" #(core-with sci-opts)]
                   ["core.clj, default opts" #(core-with default-opts)]]]
  (println "===" label)
  (crit/quick-bench (f)))

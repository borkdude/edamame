(require '[clj-async-profiler.core :as prof]
         '[clojure.java.io :as io]
         '[edamame.core :as e])

(def edn-s
  (pr-str (vec (for [i (range 20000)]
                 {:id i
                  :name (str "name-" i)
                  :tags [:alpha :beta :gamma]
                  :score (double i)
                  :nested {:a [1 2 3] :b "text with spaces"}}))))

(def core-s (slurp (io/file "test-resources" "clojure" "core.clj")))

(def edn-opts (e/normalize-opts {:location? (fn [_] false)}))

(def sci-opts
  (e/normalize-opts {:all true :row-key :line :col-key :column :read-cond :allow
                     :location? seq? :end-location false
                     :auto-resolve '{:current user}}))

(defn run-edn []
  (e/parse-string edn-s edn-opts))

(defn run-core []
  (let [r (e/reader core-s)]
    (loop []
      (when-not (identical? :edamame.core/eof (e/parse-next r sci-opts))
        (recur)))))

(dotimes [_ 20] (run-edn) (run-core))

(prof/start {:event :cpu})
(dotimes [_ 60] (run-edn))
(println "EDN:" (str (prof/stop {:generate-flamegraph? false})))

(prof/start {:event :cpu})
(dotimes [_ 400] (run-core))
(println "CORE:" (str (prof/stop {:generate-flamegraph? false})))

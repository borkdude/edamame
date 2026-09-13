(require '[criterium.core :as crit]
         '[clojure.java.io :as io]
         '[clojure.tools.reader.reader-types :as r]
         '[edamame.core :as e])

(def s (slurp (io/file "test-resources" "clojure" "core.clj")))

(def sci-opts
  (e/normalize-opts {:all true :row-key :line :col-key :column :read-cond :allow
                     :location? seq? :end-location false
                     :auto-resolve '{:current user}}))

(def default-opts
  (e/normalize-opts {:all true :auto-resolve-ns true
                     :auto-resolve '{:current user}}))

(defn tools-reader [s]
  (r/indexing-push-back-reader (r/string-push-back-reader s)))

(defn run [mk opts]
  (let [rdr (mk s)]
    (loop [n 0]
      (if (identical? ::e/eof (e/parse-next rdr opts))
        n
        (recur (inc n))))))

(println "forms:" (run tools-reader sci-opts) (run e/reader sci-opts)
         "reader class:" (class (e/reader s)))

(doseq [[label opts] [["sci opts" sci-opts] ["default opts" default-opts]]
        [reader-label mk] [["tools.reader stack" tools-reader] ["e/reader" e/reader]]]
  (println "===" label "/" reader-label)
  (crit/quick-bench (run mk opts)))

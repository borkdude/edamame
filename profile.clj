(require '[clj-async-profiler.core :as prof])
(require '[clojure.java.io :as io]
         '[edamame.core :as e])

(def clojure-core (slurp (io/file "test-resources" "clojure" "core.clj")))
(defn parse-clojure []
  (e/parse-string-all clojure-core {:all true
                                    :auto-resolve-ns true}))

(prof/profile (dotimes [_ 1000] (parse-clojure)))

(prof/serve-ui 8080)

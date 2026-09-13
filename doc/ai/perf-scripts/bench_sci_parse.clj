(require '[criterium.core :as crit]
         '[sci.core :as sci]
         '[sci.impl.parser :as p])

(def s (slurp "/Users/borkdude/dev/edamame/test-resources/clojure/core.clj"))

(def ctx (sci/init {}))

(defn per-form []
  (let [r (p/reader s)]
    (loop [n 0]
      (if (identical? p/eof (p/parse-next ctx r))
        n
        (recur (inc n))))))

(defn once []
  (let [r (p/reader s)
        opts (p/parse-opts ctx nil)]
    (loop [n 0]
      (if (identical? p/eof (p/parse-next* r opts))
        n
        (recur (inc n))))))

(println "forms:" (per-form) (once))

(println "=== options per form")
(crit/quick-bench (per-form))

(println "=== options once")
(crit/quick-bench (once))

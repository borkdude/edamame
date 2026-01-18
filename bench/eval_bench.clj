(ns eval-bench
  "Compare eval performance: optimized vs upstream syntax-quote."
  (:require [edamame.core :as e]
            [clojure.string :as str]
            [clojure.java.shell :as shell]
            [criterium.core :as crit]))

(def opts {:all true})

;; Key test forms - representative cases
(def test-cases
  [{:name "empty-vector"     :form "`[]"              :arg-syms [] :args []}
   {:name "literal-vector"   :form "`[1 2 3 4 5]"     :arg-syms [] :args []}
   {:name "nested-vectors"   :form "`[[1 2] [3 4]]"   :arg-syms [] :args []}
   {:name "literal-map"      :form "`{:a 1 :b 2}"     :arg-syms [] :args []}
   {:name "vector-of-maps"   :form "`[{:a 1} {:b 2}]" :arg-syms [] :args []}
   {:name "with-unquote"     :form "`[1 ~x 3]"        :arg-syms '[x] :args [42]}
   {:name "with-splice"      :form "`[1 ~@xs 2]"      :arg-syms '[xs] :args [[10 20]]}])

(defn make-test-fn [form-str arg-syms]
  (let [parsed (e/parse-string form-str opts)
        wrapper `(fn [~@arg-syms] ~parsed)]
    (eval wrapper)))

(defn run-benchmark []
  (println "\nRunning benchmarks...\n")

  (doseq [{:keys [name form arg-syms args]} test-cases]
    (let [f (make-test-fn form arg-syms)
          call-fn (case (count args)
                    0 #(f)
                    1 #(f (first args))
                    2 #(f (first args) (second args)))]
      (println (format "%-20s %s" name form))
      (println (format "  Parsed: %s" (pr-str (e/parse-string form opts))))
      (let [result (crit/quick-benchmark* call-fn {})]
        (println (format "  Mean: %.2f ns (±%.2f)\n"
                         (* 1e9 (first (:mean result)))
                         (* 1e9 (first (:variance result)))))))))

(defn -main [& args]
  (println "Eval Benchmark: Runtime Cost of Generated Code")
  (println "===============================================")
  (println "Branch:" (str/trim (:out (shell/sh "git" "rev-parse" "--abbrev-ref" "HEAD"))))
  (println "Commit:" (str/trim (:out (shell/sh "git" "rev-parse" "--short" "HEAD"))))

  (run-benchmark)

  (println "Done."))

(-main)

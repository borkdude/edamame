(ns syntax-quote-bench
  "Benchmark syntax-quote optimization with criterium."
  (:require [edamame.core :as e]
            [clojure.string :as str]
            [clojure.java.shell :as shell]
            [criterium.core :as crit]))

(def opts {:all true})

;; Test cases - mix of forms that benefit from optimization
(def test-forms
  [;; Empty collections (optimization: direct literals)
   "`[]" "`{}" "`#{}" "`()"
   ;; Literals without splice (optimization: direct literals)
   "`[1 2 3 4 5]" "`{:a 1 :b 2 :c 3}" "`#{:a :b :c :d}" "`(foo bar baz)"
   ;; Nested without splice
   "`[[1 2] [3 4] [5 6]]" "`{:a {:b {:c 1}}}" "`[{:a 1} {:b 2} {:c 3}]"
   ;; With unquote but no splice
   "`[1 ~x 3]" "`{:a ~v :b 2}" "`[~a ~b ~c]"
   ;; With splice (NO optimization - needs concat machinery)
   "`[1 ~@xs 2]" "`[~@a ~@b ~@c]" "`#{1 ~@more 2}"
   ;; Complex
   "`(let [x# ~v] (inc x#))"])

(defn bench-form-size []
  (println "\n=== FORM SIZE COMPARISON ===")
  (doseq [f test-forms]
    (let [parsed (e/parse-string f opts)
          size (count (pr-str parsed))]
      (println (format "%-30s -> %4d chars" f size)))))

(defn -main [& args]
  (println "Edamame Syntax-Quote Benchmark (criterium)")
  (println "==========================================")
  (println "Branch:" (str/trim (:out (shell/sh "git" "rev-parse" "--abbrev-ref" "HEAD"))))
  (println "Commit:" (str/trim (:out (shell/sh "git" "rev-parse" "--short" "HEAD"))))

  (bench-form-size)

  (println "\n=== PARSE BENCHMARK ===")
  (println "Parsing all" (count test-forms) "forms per iteration...\n")
  (crit/quick-bench
   (doseq [f test-forms]
     (e/parse-string f opts)))

  (println "\nDone."))

(-main)

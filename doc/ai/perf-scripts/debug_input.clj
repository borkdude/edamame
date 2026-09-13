;; usage: clojure -M debug_input.clj <input-id> <opts-index>
;; Prints the input and its normalized parse result (same normalization as snapshot.clj).
(require '[clojure.string :as str]
         '[clojure.pprint :as pp])

(def snapshot-src (slurp "/private/tmp/claude-501/-Users-borkdude-dev-edamame/6b2348e5-2ad1-44b5-935d-05ab86144387/scratchpad/snapshot.clj"))

;; load everything from snapshot.clj except the final top-level let that writes the file
(let [cut (.lastIndexOf ^String snapshot-src "(let [[list-file out-file]")]
  (load-string (subs snapshot-src 0 cut)))

(let [[id idx] *command-line-args*
      idx (Long/parseLong idx)
      s (some (fn [[i s]] (when (= i id) s)) (rand-inputs))]
  (println "input:" (pr-str s))
  (binding [*print-length* nil *print-level* nil *print-meta* false *print-namespace-maps* false]
    (pp/pprint (parse-all s (nth opts-variants idx)))))

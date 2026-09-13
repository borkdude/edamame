;; usage: clojure -M snapshot_dump.clj <corpus-file-list> <ids-file> <out.txt>
;; Runs every input like snapshot.clj (in parallel) but writes the full
;; normalized result for the listed id/opts pairs.
(require '[clojure.java.io :as io]
         '[clojure.string :as str])

(def snapshot-src (slurp "/private/tmp/claude-501/-Users-borkdude-dev-edamame/6b2348e5-2ad1-44b5-935d-05ab86144387/scratchpad/snapshot.clj"))

(let [cut (.lastIndexOf ^String snapshot-src "(let [[list-file out-file]")]
  (load-string (subs snapshot-src 0 cut)))

(let [[list-file ids-file out-file] *command-line-args*
      wanted (->> (str/split-lines (slurp ids-file))
                  (remove str/blank?)
                  (map #(let [[id i] (str/split % #"\t")] [id (Long/parseLong i)]))
                  set)
      files (->> (str/split-lines (slurp list-file)) (remove str/blank?))
      inputs (concat (rand-inputs)
                     (map (fn [f] [f (delay (slurp f))]) files))
      lines (pmap (fn [[id s]]
                    (let [s (force s)]
                      (str/join
                       (keep-indexed
                        (fn [i o]
                          (when (contains? wanted [id i])
                            (str "### " id " " i "\n"
                                 (binding [*print-length* nil
                                           *print-level* nil
                                           *print-meta* false
                                           *print-namespace-maps* false]
                                   (pr-str (parse-all s o)))
                                 "\n")))
                        opts-variants))))
                  inputs)]
  (with-open [w (io/writer out-file)]
    (doseq [l lines]
      (.write w ^String l)))
  (println "wrote" out-file)
  (shutdown-agents))

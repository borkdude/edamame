(require '[clojure.java.io :as io]
         '[clojure.string :as str]
         '[clojure.tools.reader.reader-types :as r]
         '[edamame.core :as e])

(defn tools-reader [s]
  (r/indexing-push-back-reader (r/string-push-back-reader s)))

(defn normalize [x]
  (let [m (meta x)
        x (cond
            (instance? java.util.regex.Pattern x) [::regex (str x)]
            (and (double? x) (Double/isNaN x)) ::nan
            (symbol? x) (symbol (str/replace (str x) #"__\d+" "__N"))
            (seq? x) (doall (map normalize x))
            (vector? x) (mapv normalize x)
            (set? x) (set (map normalize x))
            (map? x) (into {} (map (fn [[k v]] [(normalize k) (normalize v)])) x)
            :else x)]
    (if m [::meta (normalize m) x] x)))

(defn parse-all [rdr raw-opts]
  (let [opts (e/normalize-opts raw-opts)
        acc (volatile! [])]
    (try
      (loop []
        (let [v (e/parse-next rdr opts)]
          (when-not (identical? :edamame.core/eof v)
            (vswap! acc conj v)
            (recur))))
      (normalize @acc)
      (catch Throwable ex
        [(normalize @acc) (class ex) (.getMessage ex) (ex-data ex)]))))

(def opts-variants
  [{}
   {:all true :read-cond :allow :features #{:clj}
    :auto-resolve-ns true :auto-resolve '{:current user}}])

(defn mismatch [f]
  (let [s (slurp f)]
    (first (for [o opts-variants
                 :let [expected (parse-all (tools-reader s) o)
                       actual (parse-all (e/reader s) o)]
                 :when (not= expected actual)]
             {:file (str f) :opts o}))))

(def files
  (->> (str/split-lines (slurp (first *command-line-args*)))
       (remove str/blank?)
       (map io/file)
       vec))

(println "files:" (count files) "bytes:" (reduce + (map #(.length ^java.io.File %) files)))

(let [t0 (System/nanoTime)
      results (doall (pmap (fn [f]
                             (try (mismatch f)
                                  (catch Throwable t {:file (str f) :crash (str t)})))
                           files))
      bad (remove nil? results)]
  (println "mismatches:" (count bad) "in" (format "%.1f" (/ (- (System/nanoTime) t0) 1e9)) "s")
  (doseq [m (take 10 bad)] (prn m)))

(defn parse-count [s raw-opts]
  (let [opts (e/normalize-opts raw-opts)
        rdr (e/reader s)]
    (try
      (loop [n 0]
        (if (identical? :edamame.core/eof (e/parse-next rdr opts))
          {:ok true :forms n}
          (recur (inc n))))
      (catch Throwable _ {:ok false :forms 0}))))

(let [stats (doall (pmap #(parse-count (slurp %) (second opts-variants)) files))]
  (println "full opts: files parsed to the end:" (count (filter :ok stats))
           "of" (count stats)
           "forms:" (reduce + (map :forms stats))))

(shutdown-agents)

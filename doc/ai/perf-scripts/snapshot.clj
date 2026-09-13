;; usage: clojure -M snapshot.clj <corpus-file-list> <out.tsv>
;; Writes one line per input and option set: id, opts index, sha256 of the
;; normalized parse result (forms, metadata, number and map classes, errors).
(require '[clojure.java.io :as io]
         '[clojure.string :as str]
         '[edamame.core :as e])

(defn normalize [x]
  (let [m (meta x)
        x (cond
            (instance? java.util.regex.Pattern x) [::regex (str x)]
            (number? x) (if (and (instance? Double x) (Double/isNaN x))
                          ::nan
                          [::num (.getName (class x)) (str x)])
            (symbol? x) (symbol (str/replace (str x) #"__\d+" "__N"))
            (seq? x) (into [::seq] (map normalize x))
            (vector? x) (into [::vec] (map normalize x))
            (set? x) (into [::set (.getName (class x))] (sort-by pr-str (map normalize x)))
            ;; array maps keep insertion order, other maps are sorted because
            ;; keys with identity hashes (regexes) iterate in varying order
            (map? x) (into [::map (.getName (class x))]
                           (cond->> (map (fn [[k v]] [(normalize k) (normalize v)]) x)
                             (not (instance? clojure.lang.PersistentArrayMap x)) (sort-by pr-str)))
            (or (string? x) (keyword? x) (char? x) (nil? x) (boolean? x)) x
            :else [::obj (.getName (class x))])]
    (if m [::meta (normalize m) x] x)))

(defn parse-all [s raw-opts]
  (let [opts (e/normalize-opts raw-opts)
        rdr (e/reader s)
        acc (volatile! [])]
    (try
      (loop []
        (let [v (e/parse-next rdr opts)]
          (when-not (identical? :edamame.core/eof v)
            (vswap! acc conj v)
            (recur))))
      (normalize @acc)
      (catch Throwable ex
        [(normalize @acc) ::error (.getName (class ex))
         ;; edamame's internal sentinel objects can end up in messages
         (some-> (.getMessage ex) (str/replace #"java\.lang\.Object@[0-9a-f]+" "java.lang.Object@N"))
         (normalize (ex-data ex))]))))

(def opts-variants
  [{}
   {:all true :read-cond :allow :features #{:clj}
    :auto-resolve '{:current user foo bar}}
   {:all true :row-key :line :col-key :column :read-cond :allow
    :location? seq? :end-location false :auto-resolve-ns true}])

(def tokens
  ["(" ")" "[" "]" "{" "}" "#{" "#:foo{" "#?(" ":clj" ":default"
   "\"str\"" "\"a\\\"b\"" "\"line\nbreak\"" "\\a" "\\newline"
   "#\"re\\d\"" "#(" "%" "'" "`" "~" "~@" "@" "^:m" "^{:a 1}" ";c\n" "#_"
   "sym" "ns/sym" "foo." ":kw" "::kw" "::foo/kw" ":a/b" "/" "a/b/c" ":" "::"
   "0" "-0" "+0" "012" "08" "0x1F" "-0x10" "2r101" "36rZZ" "007" "1a" "1.2.3"
   "9223372036854775807" "9223372036854775808" "-9223372036854775808" "123N"
   "1." "1.5" "-1.5" "+1.5" "1e5" "1.5e-3" "1.5M" "3/4" "-3/4" "+3/4" "1/0"
   "##NaN" "##-Inf" "##Inf" "{:a 1 :a 2}" "{:a 1 :b 2}" "{1 1 1.0 2}" "{nil 1 nil 2}"
   ":a" "1" "nil" "true" " " "," "\n" "\r\n"])

(defn rand-inputs []
  (let [rnd (java.util.Random. 42)
        tok (vec (repeatedly 4000
                             #(apply str (repeatedly (.nextInt rnd 30)
                                                     (fn [] (nth tokens (.nextInt rnd (count tokens))))))))
        rnd2 (java.util.Random. 43)
        alphabet "(){}[]\"\\;#:^'`~@% a1/.,-+eEMNxr0\n"
        chars (vec (repeatedly 4000
                               #(let [sb (StringBuilder.)]
                                  (dotimes [_ (.nextInt rnd2 30)]
                                    (.append sb (.charAt alphabet (.nextInt rnd2 (count alphabet)))))
                                  (str sb))))]
    (concat (map-indexed (fn [i s] [(str "tok-" i) s]) tok)
            (map-indexed (fn [i s] [(str "chr-" i) s]) chars)
            (map (fn [s] [(str "edge-" s) s]) tokens))))

(defn sha256 [^String s]
  (let [d (.digest (java.security.MessageDigest/getInstance "SHA-256") (.getBytes s "UTF-8"))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) d))))

(let [[list-file out-file] *command-line-args*
      files (->> (str/split-lines (slurp list-file)) (remove str/blank?))
      inputs (concat (rand-inputs)
                     (map (fn [f] [f (delay (slurp f))]) files))
      lines (pmap (fn [[id s]]
                    (let [s (force s)]
                      (str/join "\n"
                                (map-indexed
                                 (fn [i o]
                                   (str id "\t" i "\t"
                                        (sha256 (binding [*print-length* nil
                                                          *print-level* nil
                                                          *print-meta* false
                                                          *print-namespace-maps* false]
                                                  (pr-str (parse-all s o))))))
                                 opts-variants))))
                  inputs)]
  (with-open [w (io/writer out-file)]
    (doseq [l lines]
      (.write w ^String l)
      (.write w "\n")))
  (println "wrote" out-file)
  (shutdown-agents))

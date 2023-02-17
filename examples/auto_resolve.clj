(ns auto-resolve
  (:require [edamame.core :as e]))

(defn parse-ns-require-alias [[libname & {:keys [as as-alias]}]]
  (let [alias (or as as-alias)]
    (when alias {alias libname})))

(defn parse-ns-aliases [ns-form]
  (let [clauses (filter seq? ns-form)
        requires (filter #(= :require (first %)) clauses)
        requires (mapcat rest requires)]
    (reduce into {} (map parse-ns-require-alias requires))))

(def example "(ns foo
  (:require [foobar :as bar]))

::foo ;;=> :foo/foo
::bar/hello ;;=> :foobar/hello
::baz/hello ;; non-existing, we fall back to :unknown/hello

#inst \"2006\"
#foo/bar [baz]
")

(let [rdr (e/reader example)]
  (loop [results []
         auto-resolve {}]
    (let [form (e/parse-next rdr {:auto-resolve #(get auto-resolve % 'unknown)
                                  :readers (fn [t]
                                             (fn [v]
                                               (tagged-literal t v)))})]
      (if (= ::e/eof form)
        results
        (if (and (seq? form)
                 (= 'ns (first form)))
          (let [the-ns-name (second form)
                aliases (assoc (parse-ns-aliases form) :current the-ns-name)]
            (recur (conj results form)
                   aliases))
          (recur (conj results form)
                 auto-resolve))))))

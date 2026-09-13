(require '[criterium.core :as crit]
         '[clojure.edn :as cedn]
         '[fast-edn.core :as fedn]
         '[edamame.core :as e])

(def s
  (pr-str (vec (for [i (range 20000)]
                 {:id i
                  :name (str "name-" i)
                  :tags [:alpha :beta :gamma]
                  :score (double i)
                  :nested {:a [1 2 3] :b "text with spaces"}}))))

(def no-loc (e/normalize-opts {:location? (fn [_] false)}))
(def with-loc (e/normalize-opts {}))

(println "chars:" (count s)
         "same result:" (= (cedn/read-string s)
                           (fedn/read-string s)
                           (e/parse-string s no-loc)))

(doseq [[label f] [["clojure.edn" #(cedn/read-string s)]
                   ["fast-edn" #(fedn/read-string s)]
                   ["edamame, no location meta" #(e/parse-string s no-loc)]
                   ["edamame, location meta" #(e/parse-string s with-loc)]]]
  (println "===" label)
  (crit/quick-bench (f)))

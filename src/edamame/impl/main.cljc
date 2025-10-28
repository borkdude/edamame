(ns edamame.impl.main
  "Only used for testing"
  {:no-doc true}
  (:require
   [edamame.core :as c]
   [#?(:cljs cljs.reader :default clojure.edn) :as edn]
   [clojure.string :as str])
  #?(:clj (:gen-class)))

;; for testing only
(defn -main [& [form opts]]
  (println (str/join " " (map pr-str (c/parse-string-all form (edn/read-string opts))))))

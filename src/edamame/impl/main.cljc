(ns edamame.impl.main
  "Only used for testing"
  {:no-doc true}
  (:require
   [edamame.core :as c]
   [#?(:clj clojure.edn :cljs cljs.reader) :as edn])
  #?(:clj (:gen-class)))

;; for testing only
(defn -main [& [form opts]]
  (prn (c/parse-string form (edn/read-string opts))))

(ns private.tmp.read-core)

(require '[edamame.impl.parser :as p] :reload)
(require '[clojure.java.io :as io])
(def core (slurp (io/resource "clojure/core.clj")))

(time (dotimes [_ 10]
        (prn
         (meta (first (p/parse-string-all
                       core
                       {:postprocess
                        (fn [{:keys [:obj :loc]}]
                          (if (p/iobj? obj)
                            (vary-meta obj assoc :loc loc) obj))
                        :all true :auto-resolve {:current 'clojure.core}}))))))

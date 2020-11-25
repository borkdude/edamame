(require '[edamame.impl.parser :as p] :reload)
(require '[clojure.java.io :as io])
(def core (slurp (io/resource "clojure/core.clj")))

(case (first *command-line-args*)
  "1"
  (time (dotimes [_ 10]
              (let [nodes (p/parse-string-all
                           core
                           {:postprocess
                            (fn [{:keys [:obj :loc]}]
                              (if (p/iobj? obj)
                                (vary-meta obj assoc :loc loc)
                                obj))
                            :all true :auto-resolve {:current 'clojure.core}})]
                ;;(prn (count nodes))
                (prn
                 (meta (first nodes))))))

  "2"
  (time (dotimes [_ 10]
              (let [nodes (p/parse-string-all
                           core
                           {:all true :auto-resolve {:current 'clojure.core}
                            :location-key :sci.impl/loc})]
                ;;(prn (count nodes))
                (prn
                 (meta (first nodes)))))))

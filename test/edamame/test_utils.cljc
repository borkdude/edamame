(ns edamame.test-utils
  (:require [edamame.test-utils.utils]
            #?(:clj [edamame.test-utils.macros])
            [clojure.test :as test])
  #?(:cljs (:require-macros [edamame.test-utils.macros])))

#?(:cljs
   (defn testing-vars-str
     "Returns a string representation of the current test.  Renders names
  in *testing-vars* as a list, then the source file and line of
  current assertion."
     [m]
     (let [{:keys [file line column]} m]
       (str
        (reverse (map #(:name (meta %)) (:testing-vars (test/get-current-env))))
        " (" file ":" line (when column (str ":" column)) ")"))))

#?(:clj
   (defmethod clojure.test/report :begin-test-var [m]
     (println "===" (-> m :var meta :name))
     (println))
   :cljs (defmethod cljs.test/report [:cljs.test/default :begin-test-var] [m]
           (println "===" (-> m testing-vars-str))
           (println)))

#?(:clj
   (defmethod clojure.test/report :end-test-var [_m]
     (let [{:keys [:fail :error]} @test/*report-counters*]
       (when (and (= "true" (System/getenv "FAIL_FAST"))
                  (or (pos? fail) (pos? error)))
         (println "=== Failing fast")
         (System/exit 1)))))

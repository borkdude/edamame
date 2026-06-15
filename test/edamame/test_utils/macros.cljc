(ns edamame.test-utils.macros
  (:require #?@(:cljs [] :cljd [] :default [[clojure.test :as test]])
            #?@(:cljr [] :cljd [] :default [[cljs.test :as cljs-test]])
            #?@(:cljd [[edamame.test-utils.utils]] :default [])))

#?(:cljs (set! *warn-on-reflection* true))

;; ClojureDart: cljd.test/assert-expr is a plain function, not an extensible
;; multimethod. So we provide thrown-with-data? as a boolean-returning macro;
;; cljd's `is` routes it through assert-any.
#?(:cljd
   (defmacro thrown-with-data? [& args]
     (let [[msg-re expected expr] (if (= 3 (count args))
                                    args
                                    [nil (first args) (second args)])]
       `(try
          ~expr
          false
          (catch cljd.core/ExceptionInfo e#
            (let [data# (ex-data e#)
                  msg# (ex-message e#)]
              (and (or (nil? ~msg-re) (re-find ~msg-re msg#))
                   (edamame.test-utils.utils/submap? ~expected data#))))))))

#?(:cljs nil :cljd nil :default
  (defmethod test/assert-expr 'thrown-with-data?
    [msg [_ msg-re data expr]]
    (let [[msg-re expected expr]
          (if expr [msg-re data expr]
              [nil msg-re data])]
      `(let [msg-re# ~msg-re
             expected# ~expected
             msg# ~msg]
         (try
           ~expr
           (test/do-report
            {:type :fail
             :message msg#
             :expected expected#
             :actual nil})
           (catch Exception ex#
             (let [data# (ex-data ex#)
                   ex-msg# #?(:clj (.getMessage ex#)
                             :cljs (.getMessage ex#)
                             :cljr (.-Message ex#))]
               (test/do-report
                (if msg-re#
                  (if (re-find msg-re# ex-msg#)
                    {:type (if (edamame.test-utils.utils/submap? expected# data#)
                             :pass
                             :fail)
                     :message msg#
                     :expected expected#
                     :actual data#}
                    {:type :fail
                     :message msg#
                     :expected msg-re#
                     :actual ex-msg#}))))))))))

(defmacro deftime
  "From macrovich"
  [& body]
  (when #?(:cljs (re-matches #".*\$macros" (name (ns-name *ns*)))
           :default (not (:ns &env)))
    `(do ~@body)))

#?(:cljr nil :cljd nil :default
(deftime
  (defmethod #?(:clj cljs.test/assert-expr
                :cljs cljs.test$macros/assert-expr)
    'thrown-with-data?
    [_menv msg [_ msg-re data expr]]
    (let [[msg-re expected expr]
          (if expr [msg-re data expr]
              [nil msg-re data])]
      `(let [msg-re# ~msg-re
             expected# ~expected
             msg# ~msg]
         (cljs.test/do-report
          (try
            ~expr
            {:type :fail
             :message msg#
             :expected expected#
             :actual nil}
            (catch cljs.core/ExceptionInfo ex#
              (let [data# (ex-data ex#)
                    ex-msg# (.-message ex#)]
                (if msg-re#
                  (if (re-find msg-re# ex-msg#)
                    {:type (if (edamame.test-utils.utils/submap? expected# data#)
                             :pass
                             :fail)
                     :message msg#
                     :expected expected#
                     :actual data#}
                    {:type :fail
                     :message msg#
                     :expected msg-re#
                     :actual ex-msg#}))))))))))
)

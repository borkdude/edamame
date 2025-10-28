(ns edamame.test-utils.macros
  (:require #?@(:cljs [] :default [[clojure.test :as test]])
            #?@(:cljr [] :default [[cljs.test :as cljs-test]])))

#?(:cljs (set! *warn-on-reflection* true))

#?(:cljs nil :default
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

#?(:cljr nil :default
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

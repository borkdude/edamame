(ns edamame.test-utils
  (:require  #?(:clj [clojure.test :as test])
             #?(:clj [cljs.test :as cljs-test]))
  #?(:cljs (:require-macros [edamame.test-utils])))

#?(:clj (set! *warn-on-reflection* true))

(defn submap?
  "Is m1 a subset of m2? Adapted from
  https://github.com/clojure/spec-alpha2, clojure.test-clojure.spec"
  [m1 m2]
  (cond
    (and (map? m1) (map? m2))
    (every? (fn [[k v]] (and (contains? m2 k)
                             (submap? v (get m2 k))))
            m1)
    #?(:clj (instance? java.util.regex.Pattern m1)
       :cljs (regexp? m1))
    (re-find m1 m2)
    :else (= m1 m2)))

#?(:clj
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
                    ex-msg# (.getMessage ex#)]
                (test/do-report
                 (if msg-re#
                   (if (re-find msg-re# ex-msg#)
                     {:type (if (submap? expected# data#)
                              :pass
                              :fail)
                      :message msg#
                      :expected expected#
                      :actual data#}
                     {:type :fail
                      :message msg#
                      :expected msg-re#
                      :actual ex-msg#})
                   {:type (if (submap? expected# data#)
                            :pass
                            :fail)
                    :message msg#
                    :expected expected#
                    :actual data#})))))))))

#?(:clj
   (defmethod cljs-test/assert-expr 'thrown-with-data?
     [_menv msg [_ msg-re data expr]]
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
            (catch cljs.core/ExceptionInfo ex#
              (let [data# (ex-data ex#)
                    ex-msg# (.-message ex#)]
                (test/do-report
                 (if msg-re#
                   (if (re-find msg-re# ex-msg#)
                     {:type (if (submap? expected# data#)
                              :pass
                              :fail)
                      :message msg#
                      :expected expected#
                      :actual data#}
                     {:type :fail
                      :message msg#
                      :expected msg-re#
                      :actual ex-msg#})
                   {:type (if (submap? expected# data#)
                            :pass
                            :fail)
                    :message msg#
                    :expected expected#
                    :actual data#})))))))))

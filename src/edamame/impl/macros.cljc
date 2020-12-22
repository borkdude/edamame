(ns ^:no-doc edamame.impl.macros
  #?(:cljs
     (:require-macros
      [edamame.impl.macros :refer [deftime ?]])))

(defmacro deftime
  "Private. deftime macro from https://github.com/cgrand/macrovich"
  [& body]
  (when #?(:clj (not (:ns &env))
           :cljs (when-let [n (and *ns* (ns-name *ns*))]
                   (re-matches #".*\$macros" (name n))))
    `(do ~@body)))

(deftime
  (defmacro ?
    "Private. case macro from https://github.com/cgrand/macrovich"
    [& {:keys [cljs clj]}]
    (if (contains? &env '&env)
      `(if (:ns ~'&env) ~cljs ~clj)
      (if #?(:clj (:ns &env) :cljs true)
        cljs
        clj))))

(ns ^:no-doc edamame.impl.macros
  #?(:cljs
     (:require-macros
      [edamame.impl.macros :refer [deftime ?]])))

(defmacro deftime
  "Private. deftime macro from https://github.com/cgrand/macrovich"
  [& body]
  (when #?(:cljs (when-let [n (and *ns* (ns-name *ns*))]
                   (re-matches #".*\$macros" (name n)))
           :default (not (:ns &env)))
    `(do ~@body)))

(deftime
  (defmacro ?
    "Private. case macro from https://github.com/cgrand/macrovich"
    [& {:keys [cljs clj]}]
    (if (contains? &env '&env)
      `(if (:ns ~'&env) ~cljs ~clj)
      (if #?(:cljs true :default (:ns &env))
        cljs
        clj))))

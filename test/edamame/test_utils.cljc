(ns edamame.test-utils
  (:require [edamame.test-utils.utils]
            #?@(:cljs [] :default [edamame.test-utils.macros]))
  #?(:cljs (:require-macros [edamame.test-utils.macros])))

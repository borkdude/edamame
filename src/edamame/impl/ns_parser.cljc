(ns edamame.impl.ns-parser)

(defn- libspec?
  "Returns true if x is a libspec"
  [x]
  (or (symbol? x)
      (and (vector? x)
           (or
            (nil? (second x))
            (keyword? (second x))))))

(defn- prependss
  "Prepends a symbol or a seq to coll"
  [x coll]
  (if (symbol? x)
    (cons x coll)
    (concat x coll)))

(defn- load-lib
  [prefix lib & options]
  (let [lib (if prefix (symbol (str prefix \. lib)) lib)
        opts (apply hash-map options)]
    (assoc opts :lib lib)))

(defn- load-libs
  [kw args]
  (let [args* (cons kw args)
        flags (filter keyword? args*)
        opts (interleave flags (repeat true))
        args* (filter (complement keyword?) args*)]
    (mapcat (fn [arg]
              (if (libspec? arg)
                [(apply load-lib nil (prependss arg opts))]
                (let [[prefix & args*] arg]
                  (when (nil? prefix)
                    (throw (ex-info "prefix cannot be nil"
                                    {:args args})))
                  (mapcat (fn [arg]
                            [(apply load-lib prefix (prependss arg opts))])
                          args*))))
            args*)))

(defn- -ns
  [[_ns name & references]]
  (let [docstring  (when (string? (first references)) (first references))
        references (if docstring (next references) references)
        name (if docstring
               (vary-meta name assoc :doc docstring)
               name)
        metadata   (when (map? (first references)) (first references))
        references (if metadata (next references) references)
        references (filter seq? references)
        references (group-by first references)
        requires (mapcat #(load-libs :require (rest %)) (:require references))]
    ;;(println exp)
    {:current name
     :meta metadata
     :requires requires
     :aliases (reduce (fn [acc require]
                        (if-let [alias (or (:as require)
                                           (:as-alias require))
                                 ]
                          (assoc acc alias (:lib require))
                          acc))
                      {}
                      requires)}))

(defn parse-ns-form [ns-form]
  (-ns ns-form))

;;;; Scratch

(comment
  (load-libs :require '[[foo.bar :as bar]])
  (load-libs :require '[[foo [bar :as bar]]])
  (parse-ns-form '(ns foo (:require [foo :as dude])))

  )


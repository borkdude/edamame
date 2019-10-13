(ns edamame.core
  (:require
   [edamame.impl.parser :as p]))

(defn parse-string
  "Parses first EDN value from string.

  Supported options:

  `:dispatch`: a map of characters to functions which will receive the
  parsed EDN value when encountering the char. Map may be nested for
  dispatch characters. See README.md for examples.

  Additional arguments to tools.reader like `:readers` for passing
  reader tag functions."
  ([s]
   (p/parse-string s nil))
  ([s opts]
   (p/parse-string s opts)))

(defn parse-string-all
  "Like parse-string but parses all values from string and returns them
  in a vector."
  ([s]
   (p/parse-string-all s nil))
  ([s opts]
   (p/parse-string-all s opts)))

;;;; Scratch

(comment
  (parse-string "(1 2 3 #_4)"))

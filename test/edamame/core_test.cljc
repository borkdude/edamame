(ns edamame.core-test
  (:require
   [edamame.core :as p]
   [clojure.test :as t :refer [deftest is]]))

(deftest parser-test
  (is (= "foo" (p/parse-string "\"foo\"")))
  (is (= 'foo (p/parse-string "foo" {:dispatch {\' (fn [val]
                                                     (list 'quote val))}})))
  (is (= :foo (p/parse-string ":foo")))
  (is (= :foo/bar (p/parse-string ":foo/bar")))
  (is (= '(1 2 3) (p/parse-string "(1 2 3)")))
  (is ((every-pred vector? #(= % [1 2 3])) (p/parse-string "[1 2 3]")))
  (is (= #{1 2 3} (p/parse-string "#{1 2 3}")))
  (is (= {:a 1 :b 2} (p/parse-string "{:a 1 :b 2}")))
  (is (= {:row 1 :col 2}  (meta (first (p/parse-string "[{:a 1 :b 2}]")))))
  (is (= {:foo true :row 1 :col 1} (meta (p/parse-string "^:foo {:a 1 :b 2}"))))
  (let [p (p/parse-string ";; foo\n{:a 1}")]
    (is (= {:a 1} p))
    (is (= {:row 2 :col 1} (meta p))))
  (is (= '(deref foo) (p/parse-string "@foo" {:dispatch {\@ (fn [val]
                                                              (list 'deref val))}})))
  (is (= '(defn foo []) (p/parse-string "(defn foo [])")))
  (let [foo-sym (second (p/parse-string "(defn foo [])"))]
    (is (= {:row 1 :col 7} (meta foo-sym))))
  #?(:clj (is (= (first (p/parse-string "#(inc 1 2 %)"
                                       {:dispatch
                                        {\# {\( (fn [expr]
                                                  (read-string (str "#" expr)))}}}))
                'fn*)))
  (is (re-find (p/parse-string "#\"foo\"" {:dispatch {\# {\" #(re-pattern %)}}}) "foo"))
  (is (= '(do (+ 1 2 3)) (p/parse-string "(do (+ 1 2 3)\n)")))
  (is (= "[1 2 3]" (p/parse-string "#foo/bar [1 2 3]" {:readers {'foo/bar (fn [v] (str v))}})))
  (is (= [1 2 3] (p/parse-string-all "1 2 3")))
  (is (= '({:row 1, :col 1}
           {:row 1, :col 5}
           {:row 1, :col 9}
           {:row 1, :col 13}
           {:row 1, :col 14}
           {:row 1, :col 16}
           {:row 1, :col 18})
         (->>
          "{:a {:b {:c [a b c]}}}"
          p/parse-string
          (tree-seq coll? #(if (map? %) (vals %) %))
          (map meta)))))

;;;; Scratch

(comment
  (t/run-tests)
  )

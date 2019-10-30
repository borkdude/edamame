(ns edamame.core-test
  (:require
   [edamame.core :as p]
   [clojure.test :as t :refer [deftest is testing]]
   #?(:clj [clojure.edn :as edn])))

(deftest parser-test
  (is (= "foo" (p/parse-string "\"foo\"")))
  (is (= 'foo (p/parse-string "foo")))

  (is (= :foo (p/parse-string ":foo")))
  (is (= :foo/bar (p/parse-string ":foo/bar")))
  (is (= '(1 2 3) (p/parse-string "(1 2 3)")))
  (is ((every-pred vector? #(= % [1 2 3])) (p/parse-string "[1 2 3]")))
  (is (= #{1 2 3} (p/parse-string "#{1 2 3}")))
  (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
                        #"Set literal contains duplicate key: 1 \[at line 1, column 2\]"
                        (p/parse-string "#{1 1}")))
  (is (= {:a 1 :b 2} (p/parse-string "{:a 1 :b 2}")))
  (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
                        #"The map literal starting with :a contains 3 form\(s\). Map literals must contain an even number of forms. \[at line 1, column 1\]"
                        (p/parse-string "{:a :b :c}")))
  (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
                        #"Map literal contains duplicate key: :a \[at line 1, column 1\]"
                        (p/parse-string "{:a :b :a :c}")))
  (testing "edamame can parse the empty map"
    (is (= {} (p/parse-string "{}"))))
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
  (is (= '(do (+ 1 2 3)) (p/parse-string "(do (+ 1 2 3)\n)")))
  (is (= "[1 2 3]" (p/parse-string "#foo/bar [1 2 3]" {:tools.reader/opts {:readers {'foo/bar (fn [v] (str v))}}})))
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
          (map meta))))
  (is (= '(slurp "foo") (p/parse-string "#=(slurp \"foo\")"
                                        {:dispatch
                                         {\# {\= identity}}})))
  (testing "EOF while reading"
    (doseq [s ["(" "{" "["]]
      (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
                            #"EOF while reading"
                            (p/parse-string s)))))
  (testing "read syntax-quote, unquote and unquote splicing"
    (let [opts {:dispatch
                {\` (fn [expr] (list 'syntax-quote expr))
                 \~ {:default (fn [expr] (list 'unquote expr))
                     \@ (fn [expr] (list 'unquote-splice expr))}
                 \@ (fn [expr] (list 'deref expr))}}]
      (is (= '(syntax-quote (list (unquote x) (unquote-splice [x x])))
             (p/parse-string "`(list ~x ~@[x x])"
                             opts)))
      (is (= '(syntax-quote (list (unquote x) (unquote (deref (atom nil)))))
             (p/parse-string "`(list ~x ~ @(atom nil))"
                             opts)))))
  (testing "uneval"
    (is (= '[1 2 3] (p/parse-string "(1 2 3 #_4)")))
    (is (= [1 2] (p/parse-string-all "#_(+ 1 2 3) 1 2"))))
  (testing "unmatched delimiter"
    (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) #"expected: ]"
                          (p/parse-string "[}")))
    (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) #"expected ]"
                          (p/parse-string "  [   ")))
    (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
                          #"Unmatched delimiter: \] \[at line 1, column 3\]"
                          (p/parse-string "  ]   "))))
  (testing "reader conditional processing"
    (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
                          #"allow"
                          (p/parse-string "#?(:clj 1)")))
    (is (= [1 2 3 5] (p/parse-string "[1 2 #?(:bb 3 :clj 4) 5]" {:features #{:bb}
                                                                 :read-cond :allow})))
    (is (= [1 2 4 5] (p/parse-string "[1 2 #?(:clj 3 :default 4) 5]" {:features #{:bb}
                                                                      :read-cond :allow})))
    (is (= [1 2 3 5] (p/parse-string "[1 2 #?(:bb 3 :default 4) 5]" {:features #{:bb}
                                                                     :read-cond :allow})))
    (is (= [1 2 4 5] (p/parse-string "[1 2 #?(:default 4 :bb 3) 5]" {:features #{:bb}
                                                                     :read-cond :allow})))
    (is (= [1 2 4 5] (p/parse-string "[1 2 #?(:bb 3 :default 4) 5]" {:features #{:clj}
                                                                     :read-cond :allow})))
    (is (= [1 2 4 5] (p/parse-string "[1 2 #?(:default 4 :bb 3) 5]" {:features #{:clj}
                                                                     :read-cond :allow})))
    (is (= "[1 2 #?@(:bb 3 :clj 4) 5]" (pr-str (p/parse-string "[1 2 #?@(:bb 3 :clj 4) 5]" {:features #{:bb}
                                                                                            :read-cond :preserve}))))
    (testing "don't crash on unknown reader tag in irrelevant branch"
      (is (= [1 2] (p/parse-string "[1 2 #?@(:cljs [1 2 3] :cljx #foo/bar 1)]"
                                   {:features #{:bb}
                                    :read-cond :allow}))))
    (is (= [1 2 3 4 5] (p/parse-string-all "1 2 #?(:clj 4 :bb 3) #?(:clj 5 :default 4) 5"
                                           {:features #{:bb}
                                            :read-cond :allow})))
    (is (= {:a :b} (p/parse-string "{#?@(:bb [:a :b])}"
                                   {:features #{:bb}
                                    :read-cond :allow})))
    (is (= {} (p/parse-string "{#?@(:bb [:a :b])}"
                              {:features #{:clj}
                               :read-cond :allow})))))

(deftest regex-test
  (is (re-find (p/parse-string "#\"foo\"" {:dispatch {\# {\" #(re-pattern %)}}}) "foo"))
  (is (= "1" (re-find (p/parse-string "#\"\\d\"" {:dispatch {\# {\" #(re-pattern %)}}}) "aaa1aaa"))))

(deftest var-test
  (is (= 'foo (p/parse-string "#'foo"
                              {:dispatch
                               {\# {\' identity}}}))))

(deftest quote-test
  (is (= '(quote foo) (p/parse-string "'foo" {:dispatch {\' (fn [val]
                                                              (list 'quote val))}}))))

;;;; Scratch

(comment
  (t/run-tests)
  #?(:clj
     (let [edn-string (slurp "deps.edn")]
       (time (dotimes [_ 10000]
               (p/parse-string edn-string)))))
  )

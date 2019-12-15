(ns edamame.core-test
  (:require
   [clojure.test :as t :refer [deftest is testing]]
   [edamame.core :as p]
   #?(:clj [clojure.java.io :as io])
   #?(:cljs [goog.object :as gobj])
   [cljs.tagged-literals :as cljs-tags]))

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
  (is (= {:row 1 :col 2, :end-row 1, :end-col 13}
         (meta (first (p/parse-string "[{:a 1 :b 2}]")))))
  (is (= {:foo true :row 1 :col 1, :end-row 1, :end-col 18}
         (meta (p/parse-string "^:foo {:a 1 :b 2}"))))
  (let [p (p/parse-string ";; foo\n{:a 1}")]
    (is (= {:a 1} p))
    (is (= {:row 2 :col 1 :end-row 2, :end-col 7} (meta p))))
  (is (= '(deref foo) (p/parse-string "@foo" {:dispatch {\@ (fn [val]
                                                              (list 'deref val))}})))
  (is (= '(defn foo []) (p/parse-string "(defn foo [])")))
  (let [foo-sym (second (p/parse-string "(defn foo [])"))]
    (is (= {:row 1 :col 7 :end-row 1, :end-col 10} (meta foo-sym))))
  (is (= '(do (+ 1 2 3)) (p/parse-string "(do (+ 1 2 3)\n)")))
  (is (= "[1 2 3]" (p/parse-string "#foo/bar [1 2 3]" {:tools.reader/opts {:readers {'foo/bar (fn [v] (str v))}}})))
  (is (= [1 2 3] (p/parse-string-all "1 2 3")))
  (is (= '({:row 1, :col 1, :end-row 1, :end-col 23}
           {:row 1, :col 5, :end-row 1, :end-col 22}
           {:row 1, :col 9, :end-row 1, :end-col 21}
           {:row 1, :col 13, :end-row 1, :end-col 20}
           {:row 1, :col 14, :end-row 1, :end-col 15}
           {:row 1, :col 16, :end-row 1, :end-col 17}
           {:row 1, :col 18, :end-row 1, :end-col 19})
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
                               :read-cond :allow})))
    (testing "detect end in presence of whitespace"
      (is (= 2 (p/parse-string "#?(:bb 1 :clj 2 \n )"
                               {:features #{:clj}
                                :read-cond :allow}))))))

(deftest regex-test
  (is (re-find (p/parse-string "#\"foo\"" {:dispatch {\# {\" #(re-pattern %)}}}) "foo"))
  (is (= "1" (re-find (p/parse-string "#\"\\d\"" {:dispatch {\# {\" #(re-pattern %)}}}) "aaa1aaa"))))

(deftest var-test
  (is (= 'foo (p/parse-string "#'foo"
                              {:dispatch
                               {\# {\' identity}}}))))

(deftest quote-test
  (is (= '(quote foo) (p/parse-string "'foo" {:dispatch {\' (fn [val]
                                                              (list 'quote val))}})))
  (is (= (symbol "'") (p/parse-string "'")))
  (is (= (symbol "'foo") (p/parse-string "'foo"))))

(deftest fn-test
  #?(:clj (is (= (first (p/parse-string "#(inc 1 2 %)"
                                        {:dispatch
                                         {\# {\( (fn [expr]
                                                   (read-string (str "#" expr)))}}}))
                 'fn*)))
  (is (= (p/parse-string "#(inc %1 %)"
                         {:fn true})
         '(fn [%1] (inc %1 %1))))
  (is (= (p/parse-string "#(inc %1 %)"
                         {:fn true})
         '(fn [%1] (inc %1 %1))))
  (is (= (p/parse-string "#(apply + % %1 %3 %&)"
                         {:fn true})
         '(fn [%1 %2 %3 & %&] (apply + %1 %1 %3 %&))))
  (is (= (p/parse-string "#(apply + % %1 %3 %&)"
                         {:all true})
         '(fn [%1 %2 %3 & %&] (apply + %1 %1 %3 %&)))))

(deftest location-test
  (is (= '({:row 1, :col 13, :end-row 1, :end-col 17})
         (map meta (p/parse-string "[#_#_ ar gu ment]")))))

(deftest meta-test
  (is (= '{:row 1, :col 1, :end-row 1, :end-col 34, :arglists (quote ([& items]))}
         (meta (p/parse-string "^{:arglists '([& items])} [1 2 3]" {:all true})))))

(deftest auto-resolve
  (is (= '[:user/foo :clojure.string/foo]
         (p/parse-string "[::foo ::str/foo]" {:auto-resolve '{:current user str clojure.string}}))))

#?(:cljs
   (do (def fs (js/require "fs"))
       (def readFileSync (gobj/get fs "readFileSync"))
       (def path (js/require "path"))
       (def join (gobj/get path "join"))))

(deftest parse-clojure-core
  (is (p/parse-string-all #?(:clj (slurp (io/file "test-resources" "clojure" "core.clj"))
                             :cljs (str (readFileSync (join "test-resources" "clojure" "core.clj"))))
                          {:all true
                           :auto-resolve '{:current clojure.core}}))
  (is (p/parse-string-all #?(:clj (slurp (io/file "test-resources" "clojure" "core.cljs"))
                             :cljs (str (readFileSync (join "test-resources" "clojure" "core.cljs"))))
                          {:all true
                           :auto-resolve '{:current cljs.core}
                           :tools.reader/opts {:readers cljs-tags/*cljs-data-readers*}})))

(deftest readers-test
  (is (= '(js [1 2 3]) (p/parse-string "#js [1 2 3]" {:readers {'js (fn [v] (list 'js v))}}))))

(deftest namespaced-map-test
  ;; TODO: fix locations of namespaced maps
  (is (= #:foo{:a 1} (p/parse-string "#:foo{:a 1}")))
  (is (= {:bar/dude 1, :foo.foo/a 1}
         (p/parse-string "#::foo{:a 1 :bar/dude 1}" '{:auto-resolve {foo foo.foo}}))) )

(deftest exception-test
  (is (= (try (p/parse-string "(")
              (catch #?(:clj clojure.lang.ExceptionInfo :cljs js/Error) e
                (ex-data e)))
         {:type :edamame/error, :row 1, :col 1})))

(deftest syntax-quote-test
  ;; NOTE: most of the syntax quote functionality is tested in sci
  (let [auto-gensyms (re-seq #"x__\d+__auto__"
                             (str (p/parse-string "`(let [x# 1] `~x#)" {:syntax-quote true})))]
    (is (= 2 (count auto-gensyms)))
    (is (= 1 (count (distinct auto-gensyms)))))
  (is (= '(quote user/x)
         (p/parse-string "`x" {:syntax-quote {:resolve-symbol
                                              (fn [sym]
                                                (symbol "user" (str sym)))}}))))

;;;; Scratch

(comment
  (t/run-tests)
  #?(:clj
     (let [edn-string (slurp "deps.edn")]
       (time (dotimes [_ 10000]
               (p/parse-string edn-string)))))
  )

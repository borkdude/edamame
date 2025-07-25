(ns edamame.core-test
  (:require
   #?(:clj [cljs.tagged-literals :as cljs-tags])
   #?(:clj [clojure.java.io :as io])
   #?(:clj [clojure.tools.reader :as tr])
   #?(:clj [flatland.ordered.map :as omap])
   #?(:clj [flatland.ordered.set :as oset])
   #?(:cljs [cljs.tagged-literals :refer [JSValue]])
   #?(:cljs [goog.object :as gobj])
   [borkdude.deflet :refer [deflet]]
   [clojure.string :as str]
   [clojure.test :as t :refer [deftest is testing]]
   [edamame.core :as p]
   [edamame.test-utils]))

#?(:cljs (def Exception js/Error))

(deftest foo
  (is (thrown-with-data?
       #"The map literal starting with :a contains 3 form\(s\). Map literals must contain an even number of forms."
       {:row 1
        :col 1}
       (p/parse-string "{:a :b :c}"))))

(deftest parser-test
  (is (= "foo" (p/parse-string "\"foo\"")))
  (is (= 'foo (p/parse-string "foo")))

  (is (= :foo (p/parse-string ":foo")))
  (is (= :foo/bar (p/parse-string ":foo/bar")))
  (is (= '(1 2 3) (p/parse-string "(1 2 3)")))
  (is ((every-pred vector? #(= [1 2 3] %)) (p/parse-string "[1 2 3]")))
  (is (= #{1 2 3} (p/parse-string "#{1 2 3}")))
  (is (thrown-with-data?
       #"Set literal contains duplicate key: 1"
       {:row 1
        :col 2}
       (p/parse-string "#{1 1}")))
  (is (thrown-with-data?
       #"Set literal contains duplicate key: 1"
       {:row 1
        :col 2}
       (p/parse-string "#{1 1}")))
  (is (= {:a 1 :b 2} (p/parse-string "{:a 1 :b 2}")))
  (is (thrown-with-data?
       #"The map literal starting with :a contains 3 form\(s\). Map literals must contain an even number of forms."
       {:row 1
        :col 1}
       (p/parse-string "{:a :b :c}")))
  (is (thrown-with-data?
       #"Map literal contains duplicate key: :a"
       {:row 1
        :col 1}
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
  (is (= "[1 2 3]" (p/parse-string "#foo/bar [1 2 3]" {:readers {'foo/bar str}})))
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
    (is (thrown-with-data?
         #"Unmatched delimiter: \]"
         {:row 1 :col 3}
         (p/parse-string "  ]   "))))
  (testing "many consecutive comments"
    (is (= [] (p/parse-string-all (str/join "\n" (repeat 10000 ";;")))))))

(deftest unmatched-delimiter-reading-test
  (doseq [s {" (" " )" " {" " }" " [" " ]"}]
    (is (thrown-with-data? #"EOF while reading"
                           {:edamame/expected-delimiter (str/trim (str (second s)))
                            :edamame/opened-delimiter (str/trim (str (first s)))
                            :edamame/opened-delimiter-loc {:row 1 :col 2}}
                           (p/parse-string (str (first s))))))
  (is (thrown-with-data? #"Unmatched delimiter"
                         {:edamame/expected-delimiter "}"
                          :edamame/opened-delimiter "{"}
                         (p/parse-string "
{
 :x (
     { ;; offending error
     {:a 1}
     )
 }")))
  (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
                        #"EOF while reading"
                        (p/parse-string "'" {:quote true})))
  (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
                        #"EOF while reading"
                        (p/parse-string "#'" {:var true}))))

(deftest fix-expression-test
  (let [incomplete "{:a (let [x 5"
        fix-expression (fn fix-expression [expr]
                         (try (when (p/parse-string expr)
                                expr)
                              (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e
                                (if-let [expected-delimiter (:edamame/expected-delimiter (ex-data e))]
                                  (fix-expression (str expr expected-delimiter))
                                  (throw e)))))]
    (is (= "{:a (let [x 5])}" (fix-expression incomplete)))))

(deftest reader-conditional-test
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
    (testing "whitespace issues"
      (is (= 2 (p/parse-string "#? (:bb 1 :clj 2 \n )"
                               {:features #{:clj}
                                :read-cond :allow})))
      (is (= 2 (p/parse-string "#?( :bb 1 :clj 2 \n )"
                               {:features #{:clj}
                                :read-cond :allow})))
      (is (= 2 (p/parse-string "#?(:bb 1 :clj 2 \n )"
                               {:features #{:clj}
                                :read-cond :allow}))))
    (testing "function opt"
      (let [res (p/parse-string "#?(:bb 1 :clj 2 \n )"
                                {:read-cond identity})]
        (is (= '(:bb 1 :clj 2) res))
        (is (= {:row 1, :col 1, :end-row 2, :end-col 3, :edamame/read-cond-splicing false}
               (meta res))))
      (let [res (p/parse-string "#?@(:bb 1 :clj 2 \n )"
                                {:read-cond identity})]
        (is (= '(:bb 1 :clj 2) res))
        (is (= {:row 1, :col 1, :end-row 2, :end-col 3, :edamame/read-cond-splicing true}
               (meta res)))))
    (testing "trailing uneval"
      (is (= 1 (p/parse-string "#?(#_(+ 1 2 3) :clj 2 #_112 :bb 1 #_112 )" {:read-cond true
                                                                            :features #{:bb}}))))
    (testing "EOF"
      (is (thrown-with-data? #"EOF while reading"
                             {:edamame/expected-delimiter ")"
                              :edamame/opened-delimiter "("}
                             (p/parse-string "#?(#_(+ 1 2 3) :clj 2 #_112 :bb 1 #_112 " {:read-cond true
                                                                                         :features #{:bb}}))))
    (testing "whitespace after splice"
      (is (= '(+ 1 2 3) (p/parse-string "(+ #?@
 (:clj
   [1 2 3]))" {:read-cond true
               :features #{:clj}}))))
    (is (thrown-with-msg? Exception #"keyword"
                          (p/parse-string "#?(:clj 1 2 :bb 3)"
                                          {:read-cond true
                                           :features #{:bb}})))
    (let [features #{:clj}]
      (is (= [1 3]
             (p/parse-string "[1 #?(:cljs 2) 3]"
                             {:features #{:clj}
                              :read-cond
                              (fn read-cond [obj]
                                (let [pairs (partition 2 obj)]
                                  (loop [pairs pairs]
                                    (if (seq pairs)
                                      (let [[k v] (first pairs)]
                                        (if (or (contains? features k)
                                                (= :default k))
                                          v
                                          (recur (next pairs))))
                                      p/continue))))}))))))

(deftest regex-test
  (is (re-find (p/parse-string "#\"foo\"" {:dispatch {\# {\" re-pattern}}}) "foo"))
  (is (= "1" (re-find (p/parse-string "#\"\\d\"" {:dispatch {\# {\" re-pattern}}}) "aaa1aaa"))))

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
  #?(:clj (is (= 'fn*
                 (first (p/parse-string "#(inc 1 2 %)"
                                        {:dispatch
                                         {\# {\( (fn [expr]
                                                   (read-string (str "#" expr)))}}})))))
  (is (= '(fn* [%1] (inc %1 %1))
         (p/parse-string "#(inc %1 %)"
                         {:fn true})))
  (is (= '(fn* [%1] (inc %1 %1))
         (p/parse-string "#(inc %1 %)"
                         {:fn true})))
  (is (= '(fn* [%1 %2 %3 & %&] (apply + %1 %1 %3 %&))
         (p/parse-string "#(apply + % %1 %3 %&)"
                         {:fn true})))
  (is (= '(fn* [%1 %2 %3 & %&] (apply + %1 %1 %3 %&))
         (p/parse-string "#(apply + % %1 %3 %&)"
                         {:all true})))
  (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                        #"Nested" (p/parse-string "(#(+ (#(inc %) 2)) 3)"
                                                  {:all true})))
  (let [[_fn _args expr] (p/parse-string "#(+ (/ 1 %))"
                                         {:all true})]
    (is (= {:row 1, :col 2, :end-row 1, :end-col 13} (meta expr)))
    (is (= {:row 1, :col 3, :end-row 1, :end-col 4} (meta (first expr))))
    (is (= {:row 1, :col 5, :end-row 1, :end-col 12} (meta (second expr))))))

(deftest location-test
  (is (= '({:row 1, :col 13, :end-row 1, :end-col 17})
         (map meta (p/parse-string "[#_#_ ar gu ment]")))))

(deftest meta-test
  (is (:foo (meta (p/parse-string "^:foo [1 2 3]" {:all true}))))
  (is (:foo (meta (p/parse-string "#^:foo [1 2 3]" {:all true}))))
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

(def core-expr-count (atom 0))

(defn core-read-test
  "Extracted so we can run this in the profiler"
  []
  (time
   (p/parse-string-all #?(:clj (str/join "\n"
                                         (repeat 10 (slurp (io/file "test-resources" "clojure" "core.clj"))))
                          :cljs (str (readFileSync (join "test-resources" "clojure" "core.clj"))))
                       {:all true
                        :row-key :line
                        :col-key :column
                        :end-location false
                        :location? seq?
                        :auto-resolve '{:current clojure.core}})))

#?(:clj (defn pbr-test
          "Extracted so we can run this in the profiler"
          [indexing?]
          (with-open [rdr (java.io.PushbackReader. (io/reader (io/file "test-resources" "clojure" "core.clj")))]
            (let [rdr (if indexing?
                        (clojure.lang.LineNumberingPushbackReader. rdr)
                        rdr)
                  opts (p/normalize-opts {:all true
                                          :row-key :line
                                          :col-key :column
                                          :location? seq?
                                          :end-location false
                                          :auto-resolve '{:current clojure.core}})]
              (is (= @core-expr-count (count (take-while #(not= :edamame.core/eof %)
                                                         (repeatedly #(p/parse-next rdr opts))))))))))

(deftest parse-clojure-core
  (is
   (nil?
    (dotimes [_ 1]
      (reset! core-expr-count
              (/ (count (core-read-test))
                 10)))))
  #?(:clj (testing "with pushback reader only"
            (println "PBR - Edamame reader:")
            (time (dotimes [_ 20]
                    (pbr-test false)))
            (println "PBR - LispReader:")
            (time (dotimes [_ 20]
                    (with-open [rdr (java.io.PushbackReader. (io/reader (io/file "test-resources" "clojure" "core.clj")))]
                      (is (= @core-expr-count (count (take-while #(not= :edamame.core/eof %)
                                                                 (repeatedly #(read {:eof :edamame.core/eof} rdr)))))))))
            (println "PBR - tools.reader:")
            (time (dotimes [_ 20]
                    (with-open [rdr (java.io.PushbackReader. (io/reader (io/file "test-resources" "clojure" "core.clj")))]
                      (is (= @core-expr-count (count (take-while #(not= :edamame.core/eof %)
                                                                 (repeatedly #(tr/read {:eof :edamame.core/eof} rdr)))))))))))

  #?(:clj (testing "With IndexingPushbackReader"
            (println "IPBR - Edamame reader:")
            (time (dotimes [_ 20]
                    (pbr-test true)))
            (println "IPBR - LispReader:")
            (time (dotimes [_ 20]
                    (with-open [rdr (clojure.lang.LineNumberingPushbackReader. (java.io.PushbackReader. (io/reader (io/file "test-resources" "clojure" "core.clj"))))]
                      (is (= @core-expr-count (count (take-while #(not= :edamame.core/eof %)
                                                                 (repeatedly #(read {:eof :edamame.core/eof} rdr)))))))))
            (println "IPBR - tools.reader:")
            (time (dotimes [_ 20]
                    (with-open [rdr (clojure.lang.LineNumberingPushbackReader. (java.io.PushbackReader. (io/reader (io/file "test-resources" "clojure" "core.clj"))))]
                      (is (= @core-expr-count (count (take-while #(not= :edamame.core/eof %)
                                                                 (repeatedly #(tr/read {:eof :edamame.core/eof} rdr)))))))))))
  (is (nil? (dotimes [_ 1]
              (reset! core-expr-count (count (p/parse-string-all #?(:clj (slurp (io/file "test-resources" "clojure" "core.cljs"))
                                                                    :cljs (str (readFileSync (join "test-resources" "clojure" "core.cljs"))))
                                                                 {:all true
                                                                  :row-key :line
                                                                  :col-key :column
                                                                  :auto-resolve '{:current cljs.core}
                                                                  :end-location false
                                                                  :location? seq?
                                                                  #?@(:clj [:readers cljs-tags/*cljs-data-readers*])}))))))
  #?(:clj (testing "with pushback reader only"
            (println "Edamame reader:")
            (time (dotimes [_ 10]
                    (with-open [rdr (java.io.PushbackReader. (io/reader (io/file "test-resources" "clojure" "core.cljs")))]
                      (let [opts (p/normalize-opts {:all true
                                                    :row-key :line
                                                    :col-key :column
                                                    :auto-resolve '{:current cljs.core}
                                                    :end-location false
                                                    :readers cljs-tags/*cljs-data-readers*})]
                        (is (= @core-expr-count (count (take-while #(not= :edamame.core/eof %)
                                                                   (repeatedly #(p/parse-next rdr opts)))))))))))))

(deftest readers-test
  (is (= '(foo [1 2 3]) (p/parse-string "#foo [1 2 3]" {:readers {'foo (fn [v] (list 'foo v))}})))
  (is (= '(foo @(atom 1)) (p/parse-string "#foo @(atom 1)" {:readers {'foo (fn [v] (list 'foo v))}
                                                            :all true})))
  (is (= [1 2 3] (p/parse-string "#foo [1 2 3]" {:readers (constantly identity)})))
  (is (= '(js [1 2 3]) (p/parse-string "#js [1 2 3]" {:readers {'js (fn [v] (list 'js v))}})))
  #?(:cljs (let [obj (p/parse-string "#js [1 2 3]")]
             (is (instance? JSValue obj))
             (is (= [1 2 3] (.-val obj))))))

(deftest namespaced-map-test
  (is (= #:foo{:a 1} (p/parse-string "#:foo{:a 1}")))
  (is (= {:bar/dude 1, :foo.foo/a 1}
         (p/parse-string "#::foo{:a 1 :bar/dude 1}" '{:auto-resolve {foo foo.foo}})
         (p/parse-string "#::foo {:a 1 :bar/dude 1}" '{:auto-resolve {foo foo.foo}})))
  (is (thrown-with-msg?
       Exception #"Namespaced map must specify a namespace"
       (p/parse-string "#:: foo{:a 1 :bar/dude 1}" '{:auto-resolve {foo foo.foo}})))
  (is (= #:foo{:a 1} (p/parse-string "#::{:a 1}" '{:auto-resolve {:current foo}})))
  (is (= #:foo{:a 1} (p/parse-string "#:: {:a 1}" '{:auto-resolve {:current foo}})))
  (is (thrown-with-msg?
       Exception #"Namespaced map must specify a namespace"
       (p/parse-string "#: :{:a 1}" '{:auto-resolve {:current foo}}))))

(deftest exception-test
  (is (let [d (try (p/parse-string-all "())")
                   (catch #?(:clj clojure.lang.ExceptionInfo :cljs js/Error) e
                     (ex-data e)))]
        (is (= :edamame/error (:type d)))
        (is (= 1 (:row d)))
        (is (= 3 (:col d))))))

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

(deftest unquote-test
  (is (= '(clojure.core/unquote x)
         (p/parse-string "~x" {:syntax-quote true})))
  (is (= '(clojure.core/unquote x)
         (p/parse-string "~x" {:syntax-quote true
                               :unquote true})))
  (is (= '(uq x)
         (p/parse-string "~x" {:syntax-quote true
                               :unquote #(list 'uq %)})))
  (is (= '(do (uq x))
         (p/parse-string "(do ~x)" {:syntax-quote true
                                    :unquote #(list 'uq %)}))))

(deftest unquote-splicing-test
  (is (= '(clojure.core/unquote-splicing x)
         (p/parse-string "~@x" {:syntax-quote true})))
  (is (= '(clojure.core/unquote-splicing x)
         (p/parse-string "~@x" {:syntax-quote true
                                :unquote-splicing true})))
  (is (= '(uqs x)
         (p/parse-string "~@x" {:syntax-quote true
                                :unquote-splicing #(list 'uqs %)})))
  (is (= '(do (uqs x))
         (p/parse-string "(do ~@x)" {:syntax-quote true
                                     :unquote-splicing #(list 'uqs %)}))))

(deftest edge-cases-test
  (is (= '(quote x) (p/parse-string "' x" {:quote true})))
  (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                        #"(?i)invalid token" (p/parse-string ": x")))
  (testing "#40"
    (is (= :nil (p/parse-string ":nil")))
    (is (= :123 (p/parse-string ":123")))
    (is (= :false (p/parse-string ":false"))))
  (testing "#43"
    (is (= :5K (p/parse-string ":5K")))))

(deftest preserve-meta-test
  (is (:foo (meta (p/parse-string "^:foo []"))))
  (let [with-meta-val (p/parse-string "`^:foo []" {:syntax-quote true})]
    #?(:clj (is (:foo (meta (eval with-meta-val))))
       :cljs (is (= `with-meta (first with-meta-val))))))

(deftest shebang-test
  (let [m (p/parse-string "#!/usr/bin/env bash\n{:a 1}")]
    (is (= {:a 1} m))
    (is (= 2 (:row (meta m))))))

(defrecord Wrapper [obj loc])

(defn iobj? [x]
  #?(:clj (instance? clojure.lang.IObj x)
     :cljs (satisfies? IWithMeta x)))

(deftest postprocess-test
  (is (= [(->Wrapper 1 {:row 1, :col 2, :end-row 1, :end-col 3})]
         (p/parse-string "[1]" {:postprocess
                                (fn [{:keys [:obj :loc]}]
                                  (if (iobj? obj)
                                    (vary-meta obj merge loc)
                                    (->Wrapper obj loc)))})))
  (let [p-fn (fn [{:keys [obj]}]
               (if (keyword? obj)
                 {:value obj}
                 obj))]
    (is (= {{:value :foo} true}
           (meta (p/parse-string "^:foo []" {:postprocess p-fn}))
           (meta (p/parse-string "^{:foo true} []" {:postprocess p-fn}))))))

#?(:clj
   (deftest pushback-reader-test
     (let [v (p/parse-next
              (java.io.PushbackReader. (java.io.StringReader. "(+ 1 2 3)")))]
       (is (= '(+ 1 2 3)
              v))
       (is (not (meta v))))
     (let [v (p/parse-next
              (clojure.lang.LineNumberingPushbackReader. (java.io.StringReader. "(+ 1 2 3)")))]
       (is (= '(+ 1 2 3)
              v))
       (is (:row (meta v))))
     (is (= ##Inf
            (p/parse-next
             (clojure.lang.LineNumberingPushbackReader. (java.io.StringReader. "##Inf")))))
     (is (= ##-Inf
            (p/parse-next
             (clojure.lang.LineNumberingPushbackReader. (java.io.StringReader. "##-Inf")))))))

#?(:cljs
   (deftest read-symbolic-test
     (is (= [##Inf ##-Inf] (p/parse-string "[##Inf ##-Inf]")))))

(deftest parse-next-test
  (is (= '(fn* [] (+ 1 2 3)) (p/parse-next (p/reader "#(+ 1 2 3)") (p/normalize-opts {:all true})))))

(deftest source-reader-test
  (is (= "#(+ 1 2 3)" (:source (meta (p/parse-next (p/source-reader "#(+ 1 2 3)")
                                                   (p/normalize-opts {:all true :source true}))))))
  (is (= "foo" (:source (meta (first (p/parse-next (p/source-reader "[foo bar]")
                                                   (p/normalize-opts {:all true :source true})))))))
  (is (= "[baz quux]"
         (let [reader (p/source-reader "[foo bar] [baz quux]")
               opts (p/normalize-opts {:all true :source true})]
           (p/parse-next reader opts)
           (:source (meta (p/parse-next
                           reader opts))))))

  (let [reader (p/source-reader "1 [1  2 3] {:a   1}")
        opts (p/normalize-opts {:all true})]
    (is (= [1 "1"] (p/parse-next+string reader opts)))
    (is (= [[1 2 3] "[1  2 3]"] (p/parse-next+string reader opts)))
    (is (= [{:a 1} "{:a   1}"] (p/parse-next+string reader opts))))

  (testing "parse-string"
    (is (= "#(+ 1 2 3)" (:source
                         (meta (p/parse-string "#(+ 1 2 3)"
                                               {:all true :source true}))))))
  (testing "parse-string-all"
    (is (= ["#(+ 1 2 3)"] (map (comp :source meta)
                               (p/parse-string-all "#(+ 1 2 3)"
                                                   {:all true :source true}))))))

(deftest location?-test
  (is (meta (p/parse-string "x")))
  (is (not (meta (p/parse-string "x" {:location? seq?}))))
  (is (meta (p/parse-string "(x)" {:location? seq?}))))

(deftest array-map-test
  (is (instance? #?(:clj
                    clojure.lang.PersistentArrayMap
                    :cljs
                    PersistentArrayMap)
                 (p/parse-string "{:a 1 :b 2}")))
  (is (instance? #?(:clj
                    clojure.lang.PersistentHashMap
                    :cljs
                    PersistentHashMap)
                 (p/parse-string "{:a 1 :b 2 :c 3 :d 4 :e 5 :f 6 :g 7 :h 8 :i 9}"))))

(deftest number-test
  (is (number? (p/parse-string "-100"))))

(deftest at-separator-test
  (is (= '[foo (clojure.core/deref bar)]
         (p/parse-string "[foo@bar]" {:deref true})))
  (is (= '[1 (clojure.core/deref 2)]
         (p/parse-string "[1@2]" {:deref true}))))

(deftest string-delimiter-test
  (try (p/parse-string "\"")
       (catch #?(:clj Exception :cljs :default) e
         (is (= {:type :edamame/error,
                 :row 1, :col 2,
                 :edamame/expected-delimiter "\"",
                 :edamame/opened-delimiter "\"",
                 :edamame/opened-delimiter-loc {:row 1, :col 1}}
                (ex-data e))))))

(defn source? [f]
  (re-matches #".*\.clj[cs]?$" (str f)))

#?(:clj
   (deftest self-parse-test
     (let [file-list (filter source? (map str (concat (file-seq (io/file "src"))
                                                      (file-seq (io/file "test")))))]
       (doseq [f file-list]
         (is (p/parse-string-all #?(:clj (slurp (io/file f))
                                    :cljs (str (readFileSync (join "src" "edamame" "impl" "parser.cljc"))))
                                 {:read-cond :allow
                                  :features #{:clj :cljs}
                                  :all true
                                  :auto-resolve '{:current edamame.core}
                                  :readers (fn [_t]
                                             identity)})
             (str "failed parsing file: " f))))))

(deftest invalid-symbol-test
  (is (thrown-with-data?
       #"Invalid symbol: foo/bar/baz"
       {:row 1
        :col 1}
       (p/parse-string "foo/bar/baz"))))

#?(:clj
   (deftest read-cond-with-plain-pushback-rdr-rest
     (with-open [rdr (java.io.PushbackReader. (java.io.StringReader. "#?(:cljs 2 :clj 1)"))]
       (let [opts (p/normalize-opts {:all true
                                     :read-cond :allow
                                     :features #{:clj}})]
         (is (= 1 (p/parse-next rdr opts)))))))

(deftest auto-resolve-ns-test
  (is (= "[(ns foo (:require [clojure.set :as set])) :clojure.set/foo]"
         (str (p/parse-string-all "(ns foo (:require [clojure.set :as set])) ::set/foo" {:auto-resolve-ns true}))))
  (is (= "[(ns foo) :foo/dude]"
         (str (p/parse-string-all "(ns foo) ::dude" {:auto-resolve-ns true}))))
  (deflet
    (def rdr (p/reader "(ns foo (:require [clojure.set :as set])) ::set/foo ::quux/dude"))
    (def opts (p/normalize-opts {:auto-resolve-ns true
                                 :auto-resolve name}))
    (is (= "(ns foo (:require [clojure.set :as set]))" (str (p/parse-next rdr opts))))
    (is (= :clojure.set/foo (p/parse-next rdr opts)))
    (is (= :quux/dude (p/parse-next rdr opts))))
  (is
   (= '[(ns foo (:require [clojure.set :as set]))
        (quote clojure.set/foo)
        (quote x/foo) :clojure.set/foo]
      (p/parse-string-all "(ns foo (:require [clojure.set :as set])) `set/foo `x/foo `::set/foo"
                          {:auto-resolve-ns true :all true}))))

(deftest uneval-test
  (deflet
    (def parsed
      (p/parse-string "#_:foo [1 2 3]"
                      {:uneval (fn [{:keys [uneval next]}]
                                 (with-meta next {uneval true}))}))
    (is (= [1 2 3] parsed))
    (is (true? (:foo (meta parsed))))
    (is (nil? (p/parse-string "#_:foo" {:uneval identity})))))

#?(:clj
   (deftest map-set-test
     (is (= "{:a #{1 2 3 4 5 6 7 8 9 10}, :b 2, :c 3, :d 4, :e 5, :f 6, :h 7, :i 8, :j 9, :k 10, :l 11}" (str (p/parse-string "{:a #{1 2 3 4 5 6 7 8 9 10} :b 2 :c 3 :d 4 :e 5 :f 6 :h 7 :i 8 :j 9 :k 10 :l 11}"
                                                                                                                              {:map omap/ordered-map
                                                                                                                               :set oset/ordered-set}))))))
(deftest param-tags-meta
  (is (= '[String]
         (-> (p/parse-string "^[String] x")
             meta
             :param-tags))))

(deftest array-notation-test
  (is (= (symbol "byte/1") (p/parse-string "byte/1")))
  (is (= (symbol "byte/9") (p/parse-string "byte/9")))
  (is (thrown? Exception (p/parse-string "byte/0")))
  (is (thrown? Exception (p/parse-string "byte/11")))
  (is (thrown? Exception (p/parse-string "byte:/1")))
  (is (thrown? Exception (p/parse-string "byte/")))
  (is (thrown? Exception (p/parse-string "byte/1a"))))

(deftest issue-115-test
  (is (= {:type :edamame/error, :row 1, :col 3} (try (p/parse-string "{:}") (catch Exception e (ex-data e))))))

(deftest parse-ns-test
  (is (= '{:current foo, :meta nil
           :requires
           ({:as set, :require true, :lib clojure.set} {:as edn, :require true, :lib clojure.edn}
            {:require true, :lib clojure.core.async}
            {:require true, :lib clojure.walk}), :aliases {set clojure.set, edn clojure.edn},
           :imports ({:full-classname NoPackage, :package nil :classname NoPackage}
                     {:full-classname java.lang.Foo, :package java.lang :classname Foo}
                     {:full-classname java.lang.Bar, :package java.lang, :classname Bar}
                     {:full-classname java.lang.Baz, :package java.lang, :classname Baz})}
         (p/parse-ns-form '(ns foo (:require [clojure [set :as set]]
                                             [clojure.edn :as edn]
                                             [clojure.core.async]
                                             clojure.walk)
                               (:import NoPackage
                                        java.lang.Foo
                                        [java.lang Bar Baz]))))))

;;;; Scratch

(comment
  (t/run-tests)
  #?(:clj
     (let [edn-string (slurp "deps.edn")]
       (time (dotimes [_ 10000]
               (p/parse-string edn-string))))))

(deftest issue-117-test
  (is (thrown-with-msg? Exception #"Invalid keyword: :::foo" (p/parse-string ":::foo" {:auto-resolve identity})))
  )

(deftest issue-132-suppress-read-test
  (is (tagged-literal? (p/parse-string "#dude 1" {:suppress-read true}))))

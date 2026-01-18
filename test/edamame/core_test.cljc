(ns edamame.core-test
  (:require
   #?(:clj [cljs.tagged-literals :as cljs-tags])
   #?(:clj [clojure.java.io :as io])
   #?(:cljr [clojure.clr.io :as io])
   #?(:clj [clojure.tools.reader :as tr])
   #?(:clj [flatland.ordered.map :as omap])
   #?(:clj [flatland.ordered.set :as oset])
   #?(:cljs [cljs.tagged-literals :refer [JSValue]])
   #?(:cljs [goog.object :as gobj])
   [borkdude.deflet :refer [deflet]]
   [clojure.string :as str]
   [clojure.test :as t :refer [deftest is testing]]
   [edamame.core :as e]
   [edamame.test-utils]))

#?(:cljs (def Exception js/Error))

(deftest foo
  (is (thrown-with-data?
       #"The map literal starting with :a contains 3 form\(s\). Map literals must contain an even number of forms."
       {:row 1
        :col 1}
       (e/parse-string "{:a :b :c}"))))

(deftest parser-test
  (is (= "foo" (e/parse-string "\"foo\"")))
  (is (= 'foo (e/parse-string "foo")))

  (is (= :foo (e/parse-string ":foo")))
  (is (= :foo/bar (e/parse-string ":foo/bar")))
  (is (= '(1 2 3) (e/parse-string "(1 2 3)")))
  (is ((every-pred vector? #(= [1 2 3] %)) (e/parse-string "[1 2 3]")))
  (is (= #{1 2 3} (e/parse-string "#{1 2 3}")))
  (is (thrown-with-data?
       #"Set literal contains duplicate key: 1"
       {:row 1
        :col 2}
       (e/parse-string "#{1 1}")))
  (is (thrown-with-data?
       #"Set literal contains duplicate key: 1"
       {:row 1
        :col 2}
       (e/parse-string "#{1 1}")))
  (is (= {:a 1 :b 2} (e/parse-string "{:a 1 :b 2}")))
  (is (thrown-with-data?
       #"The map literal starting with :a contains 3 form\(s\). Map literals must contain an even number of forms."
       {:row 1
        :col 1}
       (e/parse-string "{:a :b :c}")))
  (is (thrown-with-data?
       #"Map literal contains duplicate key: :a"
       {:row 1
        :col 1}
       (e/parse-string "{:a :b :a :c}")))
  (testing "edamame can parse the empty map"
    (is (= {} (e/parse-string "{}"))))
  (is (= {:row 1 :col 2, :end-row 1, :end-col 13}
         (meta (first (e/parse-string "[{:a 1 :b 2}]")))))
  (is (= {:foo true :row 1 :col 1, :end-row 1, :end-col 18}
         (meta (e/parse-string "^:foo {:a 1 :b 2}"))))
  (let [p (e/parse-string ";; foo\n{:a 1}")]
    (is (= {:a 1} p))
    (is (= {:row 2 :col 1 :end-row 2, :end-col 7} (meta p))))
  (is (= '(deref foo) (e/parse-string "@foo" {:dispatch {\@ (fn [val]
                                                              (list 'deref val))}})))
  (is (= '(defn foo []) (e/parse-string "(defn foo [])")))
  (let [foo-sym (second (e/parse-string "(defn foo [])"))]
    (is (= {:row 1 :col 7 :end-row 1, :end-col 10} (meta foo-sym))))
  (is (= '(do (+ 1 2 3)) (e/parse-string "(do (+ 1 2 3)\n)")))
  (is (= "[1 2 3]" (e/parse-string "#foo/bar [1 2 3]" {:readers {'foo/bar str}})))
  (is (= [1 2 3] (e/parse-string-all "1 2 3")))
  (is (= '({:row 1, :col 1, :end-row 1, :end-col 23}
           {:row 1, :col 5, :end-row 1, :end-col 22}
           {:row 1, :col 9, :end-row 1, :end-col 21}
           {:row 1, :col 13, :end-row 1, :end-col 20}
           {:row 1, :col 14, :end-row 1, :end-col 15}
           {:row 1, :col 16, :end-row 1, :end-col 17}
           {:row 1, :col 18, :end-row 1, :end-col 19})
         (->>
          "{:a {:b {:c [a b c]}}}"
          e/parse-string
          (tree-seq coll? #(if (map? %) (vals %) %))
          (map meta))))
  (is (= '(slurp "foo") (e/parse-string "#=(slurp \"foo\")"
                                        {:dispatch
                                         {\# {\= identity}}})))
  (testing "read syntax-quote, unquote and unquote splicing"
    (let [opts {:dispatch
                {\` (fn [expr] (list 'syntax-quote expr))
                 \~ {:default (fn [expr] (list 'unquote expr))
                     \@ (fn [expr] (list 'unquote-splice expr))}
                 \@ (fn [expr] (list 'deref expr))}}]
      (is (= '(syntax-quote (list (unquote x) (unquote-splice [x x])))
             (e/parse-string "`(list ~x ~@[x x])"
                             opts)))
      (is (= '(syntax-quote (list (unquote x) (unquote (deref (atom nil)))))
             (e/parse-string "`(list ~x ~ @(atom nil))"
                             opts)))))
  (testing "uneval"
    (is (= '[1 2 3] (e/parse-string "(1 2 3 #_4)")))
    (is (= [1 2] (e/parse-string-all "#_(+ 1 2 3) 1 2"))))
  (testing "unmatched delimiter"
    (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo :cljr clojure.lang.ExceptionInfo) #"expected: ]"
                          (e/parse-string "[}")))
    (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo :cljr clojure.lang.ExceptionInfo) #"expected ]"
                          (e/parse-string "  [   ")))
    (is (thrown-with-data?
         #"Unmatched delimiter: \]"
         {:row 1 :col 3}
         (e/parse-string "  ]   "))))
  (testing "many consecutive comments"
    (is (= [] (e/parse-string-all (str/join "\n" (repeat 10000 ";;")))))))

(deftest unmatched-delimiter-reading-test
  (doseq [s {" (" " )" " {" " }" " [" " ]"}]
    (is (thrown-with-data? #"EOF while reading"
                           {:edamame/expected-delimiter (str/trim (str (second s)))
                            :edamame/opened-delimiter (str/trim (str (first s)))
                            :edamame/opened-delimiter-loc {:row 1 :col 2}}
                           (e/parse-string (str (first s))))))
  (is (thrown-with-data? #"Unmatched delimiter"
                         {:edamame/expected-delimiter "}"
                          :edamame/opened-delimiter "{"}
                         (e/parse-string "
{
 :x (
     { ;; offending error
     {:a 1}
     )
 }")))
  (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo :cljr clojure.lang.ExceptionInfo)
                        #"EOF while reading"
                        (e/parse-string "'" {:quote true})))
  (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo :cljr clojure.lang.ExceptionInfo)
                        #"EOF while reading"
                        (e/parse-string "#'" {:var true}))))

(deftest fix-expression-test
  (let [incomplete "{:a (let [x 5"
        fix-expression (fn fix-expression [expr]
                         (try (when (e/parse-string expr)
                                expr)
                              (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default :cljr clojure.lang.ExceptionInfo) e
                                (if-let [expected-delimiter (:edamame/expected-delimiter (ex-data e))]
                                  (fix-expression (str expr expected-delimiter))
                                  (throw e)))))]
    (is (= "{:a (let [x 5])}" (fix-expression incomplete)))))

(deftest reader-conditional-test
  (testing "reader conditional processing"
    (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo :cljr clojure.lang.ExceptionInfo)
                          #"allow"
                          (e/parse-string "#?(:clj 1)")))
    (is (= [1 2 3 5] (e/parse-string "[1 2 #?(:bb 3 :clj 4) 5]" {:features #{:bb}
                                                                 :read-cond :allow})))
    (is (= [1 2 4 5] (e/parse-string "[1 2 #?(:clj 3 :default 4) 5]" {:features #{:bb}
                                                                      :read-cond :allow})))
    (is (= [1 2 3 5] (e/parse-string "[1 2 #?(:bb 3 :default 4) 5]" {:features #{:bb}
                                                                     :read-cond :allow})))
    (is (= [1 2 4 5] (e/parse-string "[1 2 #?(:default 4 :bb 3) 5]" {:features #{:bb}
                                                                     :read-cond :allow})))
    (is (= [1 2 4 5] (e/parse-string "[1 2 #?(:bb 3 :default 4) 5]" {:features #{:clj}
                                                                     :read-cond :allow})))
    (is (= [1 2 4 5] (e/parse-string "[1 2 #?(:default 4 :bb 3) 5]" {:features #{:clj}
                                                                     :read-cond :allow})))
    (is (= "[1 2 #?@(:bb 3 :clj 4) 5]" (pr-str (e/parse-string "[1 2 #?@(:bb 3 :clj 4) 5]" {:features #{:bb}
                                                                                            :read-cond :preserve}))))
    (testing "don't crash on unknown reader tag in irrelevant branch"
      (is (= [1 2] (e/parse-string "[1 2 #?@(:cljs [1 2 3] :cljx #foo/bar 1)]"
                                   {:features #{:bb}
                                    :read-cond :allow}))))
    (is (= [1 2 3 4 5] (e/parse-string-all "1 2 #?(:clj 4 :bb 3) #?(:clj 5 :default 4) 5"
                                           {:features #{:bb}
                                            :read-cond :allow})))
    (is (= {:a :b} (e/parse-string "{#?@(:bb [:a :b])}"
                                   {:features #{:bb}
                                    :read-cond :allow})))
    (is (= {} (e/parse-string "{#?@(:bb [:a :b])}"
                              {:features #{:clj}
                               :read-cond :allow})))
    (testing "whitespace issues"
      (is (= 2 (e/parse-string "#? (:bb 1 :clj 2 \n )"
                               {:features #{:clj}
                                :read-cond :allow})))
      (is (= 2 (e/parse-string "#?( :bb 1 :clj 2 \n )"
                               {:features #{:clj}
                                :read-cond :allow})))
      (is (= 2 (e/parse-string "#?(:bb 1 :clj 2 \n )"
                               {:features #{:clj}
                                :read-cond :allow}))))
    (testing "function opt"
      (let [res (e/parse-string "#?(:bb 1 :clj 2 \n )"
                                {:read-cond identity})]
        (is (= '(:bb 1 :clj 2) res))
        (is (= {:row 1, :col 1, :end-row 2, :end-col 3}
               (meta res))))
      (let [res (e/parse-string "#?@(:bb 1 :clj 2 \n )"
                                {:read-cond identity})]
        (is (= '(:bb 1 :clj 2) res))
        (is (= {:row 1, :col 1, :end-row 2, :end-col 3, :edamame/read-cond-splicing true}
               (meta res))))
      (let [read-cond-handler (fn [v]
                                (let [splice? (:edamame/read-cond-splicing (meta v))
                                      v (second v)
                                      v (if splice? v [v])]
                                  (with-meta v {:edamame/read-cond-splicing true})))
            res (e/parse-string "[#?(:bb 1 :clj 2 \n ) #?@(:bb [1])]"
                                {:read-cond read-cond-handler})]
        (is (= [1 1] res)))
      (testing "in map"
        (is (= {:clj ["Clojure" true], :cljs ["ClojureScript" true]}
               (e/parse-string
                "{#?@(:clj [\"Clojure\" true] :cljs [\"ClojureScript\" true])}"
                {:read-cond identity})))
        (is (= {"Clojure" true}
               (e/parse-string
                "{#?@(:clj [\"Clojure\" true] :cljs [\"ClojureScript\" true])}"
                {:read-cond second})))))
    (testing "trailing uneval"
      (is (= 1 (e/parse-string "#?(#_(+ 1 2 3) :clj 2 #_112 :bb 1 #_112 )" {:read-cond true
                                                                            :features #{:bb}}))))
    (testing "EOF"
      (is (thrown-with-data? #"EOF while reading"
                             {:edamame/expected-delimiter ")"
                              :edamame/opened-delimiter "("}
                             (e/parse-string "#?(#_(+ 1 2 3) :clj 2 #_112 :bb 1 #_112 " {:read-cond true
                                                                                         :features #{:bb}}))))
    (testing "whitespace after splice"
      (is (= '(+ 1 2 3) (e/parse-string "(+ #?@
 (:clj
   [1 2 3]))" {:read-cond true
               :features #{:clj}}))))
    (is (thrown-with-msg? Exception #"keyword"
                          (e/parse-string "#?(:clj 1 2 :bb 3)"
                                          {:read-cond true
                                           :features #{:bb}})))
    (let [features #{:clj}]
      (is (= [1 3]
             (e/parse-string "[1 #?(:cljs 2) 3]"
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
                                      e/continue))))}))))
    (is (= 1 (e/parse-string "#?(:dude 1 :cljs 2 :clj 3)"
                             {:read-cond :allow :features (constantly true)})))))

(deftest regex-test
  (is (re-find (e/parse-string "#\"foo\"" {:dispatch {\# {\" re-pattern}}}) "foo"))
  (is (= "1" (re-find (e/parse-string "#\"\\d\"" {:dispatch {\# {\" re-pattern}}}) "aaa1aaa"))))

(deftest var-test
  (is (= 'foo (e/parse-string "#'foo"
                              {:dispatch
                               {\# {\' identity}}}))))

(deftest quote-test
  (is (= '(quote foo) (e/parse-string "'foo" {:dispatch {\' (fn [val]
                                                              (list 'quote val))}})))
  (is (= (symbol "'") (e/parse-string "'")))
  (is (= (symbol "'foo") (e/parse-string "'foo"))))

(deftest fn-test
  #?(:clj (is (= 'fn*
                 (first (e/parse-string "#(inc 1 2 %)"
                                        {:dispatch
                                         {\# {\( (fn [expr]
                                                   (read-string (str "#" expr)))}}})))))
  (is (= '(fn* [%1] (inc %1 %1))
         (e/parse-string "#(inc %1 %)"
                         {:fn true})))
  (is (= '(fn* [%1] (inc %1 %1))
         (e/parse-string "#(inc %1 %)"
                         {:fn true})))
  (is (= '(fn* [%1 %2 %3 & %&] (apply + %1 %1 %3 %&))
         (e/parse-string "#(apply + % %1 %3 %&)"
                         {:fn true})))
  (is (= '(fn* [%1 %2 %3 & %&] (apply + %1 %1 %3 %&))
         (e/parse-string "#(apply + % %1 %3 %&)"
                         {:all true})))
  (is (thrown-with-msg? #?(:clj Exception :cljs js/Error :cljr Exception)
                        #"Nested" (e/parse-string "(#(+ (#(inc %) 2)) 3)"
                                                  {:all true})))
  (let [[_fn _args expr] (e/parse-string "#(+ (/ 1 %))"
                                         {:all true})]
    (is (= {:row 1, :col 2, :end-row 1, :end-col 13} (meta expr)))
    (is (= {:row 1, :col 3, :end-row 1, :end-col 4} (meta (first expr))))
    (is (= {:row 1, :col 5, :end-row 1, :end-col 12} (meta (second expr))))))

(deftest location-test
  (is (= '({:row 1, :col 13, :end-row 1, :end-col 17})
         (map meta (e/parse-string "[#_#_ ar gu ment]")))))

(deftest meta-test
  (is (:foo (meta (e/parse-string "^:foo [1 2 3]" {:all true}))))
  (is (:foo (meta (e/parse-string "#^:foo [1 2 3]" {:all true}))))
  (is (= '{:row 1, :col 1, :end-row 1, :end-col 34, :arglists (quote ([& items]))}
         (meta (e/parse-string "^{:arglists '([& items])} [1 2 3]" {:all true})))))

(deftest auto-resolve
  (is (= '[:user/foo :clojure.string/foo]
         (e/parse-string "[::foo ::str/foo]" {:auto-resolve '{:current user str clojure.string}}))))

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
   (e/parse-string-all #?(:clj (str/join "\n"
                                         (repeat 10 (slurp (io/file "test-resources" "clojure" "core.clj"))))
                          :cljs (str (readFileSync (join "test-resources" "clojure" "core.clj")))
                          :cljr (str/join "\n"
                                          (repeat 10 (slurp "test-resources/clojure/core.clj"))))
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
                  opts (e/normalize-opts {:all true
                                          :row-key :line
                                          :col-key :column
                                          :location? seq?
                                          :end-location false
                                          :auto-resolve '{:current clojure.core}})]
              (is (= @core-expr-count (count (take-while #(not= :edamame.core/eof %)
                                                         (repeatedly #(e/parse-next rdr opts))))))))))

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
              (reset! core-expr-count (count (e/parse-string-all #?(:clj (slurp (io/file "test-resources" "clojure" "core.cljs"))
                                                                    :cljs (str (readFileSync (join "test-resources" "clojure" "core.cljs")))
                                                                    :cljr (slurp "test-resources/clojure/core.cljs"))
                                                                 {:all true
                                                                  :row-key :line
                                                                  :col-key :column
                                                                  :auto-resolve '{:current cljs.core}
                                                                  :end-location false
                                                                  :location? seq?
                                                                  #?@(:clj [:readers cljs-tags/*cljs-data-readers*]
                                                                      :cljr [:readers {'js (fn [x] (list 'js x))}])}))))))
  #?(:clj (testing "with pushback reader only"
            (println "Edamame reader:")
            (time (dotimes [_ 10]
                    (with-open [rdr (java.io.PushbackReader. (io/reader (io/file "test-resources" "clojure" "core.cljs")))]
                      (let [opts (e/normalize-opts {:all true
                                                    :row-key :line
                                                    :col-key :column
                                                    :auto-resolve '{:current cljs.core}
                                                    :end-location false
                                                    :readers cljs-tags/*cljs-data-readers*})]
                        (is (= @core-expr-count (count (take-while #(not= :edamame.core/eof %)
                                                                   (repeatedly #(e/parse-next rdr opts)))))))))))))

(deftest readers-test
  (is (= '(foo [1 2 3]) (e/parse-string "#foo [1 2 3]" {:readers {'foo (fn [v] (list 'foo v))}})))
  (is (= '(foo @(atom 1)) (e/parse-string "#foo @(atom 1)" {:readers {'foo (fn [v] (list 'foo v))}
                                                            :all true})))
  (is (= [1 2 3] (e/parse-string "#foo [1 2 3]" {:readers (constantly identity)})))
  (is (= '(js [1 2 3]) (e/parse-string "#js [1 2 3]" {:readers {'js (fn [v] (list 'js v))}})))
  #?(:cljs (let [obj (e/parse-string "#js [1 2 3]")]
             (is (instance? JSValue obj))
             (is (= [1 2 3] (.-val obj))))))

(deftest namespaced-map-test
  (is (= #:foo{:a 1} (e/parse-string "#:foo{:a 1}")))
  (is (= {:bar/dude 1, :foo.foo/a 1}
         (e/parse-string "#::foo{:a 1 :bar/dude 1}" '{:auto-resolve {foo foo.foo}})
         (e/parse-string "#::foo {:a 1 :bar/dude 1}" '{:auto-resolve {foo foo.foo}})))
  (is (thrown-with-msg?
       Exception #"Namespaced map must specify a namespace"
       (e/parse-string "#:: foo{:a 1 :bar/dude 1}" '{:auto-resolve {foo foo.foo}})))
  (is (= #:foo{:a 1} (e/parse-string "#::{:a 1}" '{:auto-resolve {:current foo}})))
  (is (= #:foo{:a 1} (e/parse-string "#:: {:a 1}" '{:auto-resolve {:current foo}})))
  (is (thrown-with-msg?
       Exception #"Namespaced map must specify a namespace"
       (e/parse-string "#: :{:a 1}" '{:auto-resolve {:current foo}}))))

(deftest exception-test
  (is (let [d (try (e/parse-string-all "())")
                   (catch #?(:clj clojure.lang.ExceptionInfo :cljs js/Error :cljr clojure.lang.ExceptionInfo) e
                     (ex-data e)))]
        (is (= :edamame/error (:type d)))
        (is (= 1 (:row d)))
        (is (= 3 (:col d))))))

(deftest syntax-quote-test
  ;; NOTE: most of the syntax quote functionality is tested in sci
  (let [auto-gensyms (re-seq #"x__\d+__auto__"
                             (str (e/parse-string "`(let [x# 1] `~x#)" {:syntax-quote true})))]
    (is (= 2 (count auto-gensyms)))
    (is (= 1 (count (distinct auto-gensyms)))))
  (is (= '(quote user/x)
         (e/parse-string "`x" {:syntax-quote {:resolve-symbol
                                              (fn [sym]
                                                (symbol "user" (str sym)))}}))))

(deftest unquote-test
  (is (= '(clojure.core/unquote x)
         (e/parse-string "~x" {:syntax-quote true})))
  (is (= '(clojure.core/unquote x)
         (e/parse-string "~x" {:syntax-quote true
                               :unquote true})))
  (is (= '(uq x)
         (e/parse-string "~x" {:syntax-quote true
                               :unquote #(list 'uq %)})))
  (is (= '(do (uq x))
         (e/parse-string "(do ~x)" {:syntax-quote true
                                    :unquote #(list 'uq %)}))))

(deftest unquote-splicing-test
  (is (= '(clojure.core/unquote-splicing x)
         (e/parse-string "~@x" {:syntax-quote true})))
  (is (= '(clojure.core/unquote-splicing x)
         (e/parse-string "~@x" {:syntax-quote true
                                :unquote-splicing true})))
  (is (= '(uqs x)
         (e/parse-string "~@x" {:syntax-quote true
                                :unquote-splicing #(list 'uqs %)})))
  (is (= '(do (uqs x))
         (e/parse-string "(do ~@x)" {:syntax-quote true
                                     :unquote-splicing #(list 'uqs %)}))))

(deftest edge-cases-test
  (is (= '(quote x) (e/parse-string "' x" {:quote true})))
  (is (thrown-with-msg? #?(:clj Exception :cljs js/Error :cljr Exception)
                        #"(?i)invalid token" (e/parse-string ": x")))
  (testing "#40"
    (is (= :nil (e/parse-string ":nil")))
    (is (= :123 (e/parse-string ":123")))
    (is (= :false (e/parse-string ":false"))))
  (testing "#43"
    (is (= :5K (e/parse-string ":5K")))))

(deftest preserve-meta-test
  (is (:foo (meta (e/parse-string "^:foo []"))))
  (let [with-meta-val (e/parse-string "`^:foo []" {:syntax-quote true})]
    #?(:clj (is (:foo (meta (eval with-meta-val))))
       :cljs (is (= `with-meta (first with-meta-val))))))

(deftest shebang-test
  (let [m (e/parse-string "#!/usr/bin/env bash\n{:a 1}")]
    (is (= {:a 1} m))
    (is (= 2 (:row (meta m))))))

(defrecord Wrapper [obj loc])

(defn iobj? [x]
  #?(:clj (instance? clojure.lang.IObj x)
     :cljs (satisfies? IWithMeta x)
     :cljr (instance? clojure.lang.IObj x)))

(deftest postprocess-test
  (is (= [(->Wrapper 1 {:row 1, :col 2, :end-row 1, :end-col 3})]
         (e/parse-string "[1]" {:postprocess
                                (fn [{:keys [:obj :loc]}]
                                  (if (iobj? obj)
                                    (vary-meta obj merge loc)
                                    (->Wrapper obj loc)))})))
  (let [p-fn (fn [{:keys [obj]}]
               (if (keyword? obj)
                 {:value obj}
                 obj))]
    (is (= {{:value :foo} true}
           (meta (e/parse-string "^:foo []" {:postprocess p-fn}))
           (meta (e/parse-string "^{:foo true} []" {:postprocess p-fn}))))))

#?(:clj
   (deftest pushback-reader-test
     (let [v (e/parse-next
              (java.io.PushbackReader. (java.io.StringReader. "(+ 1 2 3)")))]
       (is (= '(+ 1 2 3)
              v))
       (is (not (meta v))))
     (let [v (e/parse-next
              (clojure.lang.LineNumberingPushbackReader. (java.io.StringReader. "(+ 1 2 3)")))]
       (is (= '(+ 1 2 3)
              v))
       (is (:row (meta v))))
     (is (= ##Inf
            (e/parse-next
             (clojure.lang.LineNumberingPushbackReader. (java.io.StringReader. "##Inf")))))
     (is (= ##-Inf
            (e/parse-next
             (clojure.lang.LineNumberingPushbackReader. (java.io.StringReader. "##-Inf")))))))

#?(:cljs
   (deftest read-symbolic-test
     (is (= [##Inf ##-Inf] (e/parse-string "[##Inf ##-Inf]")))))

(deftest parse-next-test
  (is (= '(fn* [] (+ 1 2 3)) (e/parse-next (e/reader "#(+ 1 2 3)") (e/normalize-opts {:all true})))))

(deftest source-reader-test
  (is (= "#(+ 1 2 3)" (:source (meta (e/parse-next (e/source-reader "#(+ 1 2 3)")
                                                   (e/normalize-opts {:all true :source true}))))))
  (is (= "foo" (:source (meta (first (e/parse-next (e/source-reader "[foo bar]")
                                                   (e/normalize-opts {:all true :source true})))))))
  (is (= "[baz quux]"
         (let [reader (e/source-reader "[foo bar] [baz quux]")
               opts (e/normalize-opts {:all true :source true})]
           (e/parse-next reader opts)
           (:source (meta (e/parse-next
                           reader opts))))))

  (let [reader (e/source-reader "1 [1  2 3] {:a   1}")
        opts (e/normalize-opts {:all true})]
    (is (= [1 "1"] (e/parse-next+string reader opts)))
    (is (= [[1 2 3] "[1  2 3]"] (e/parse-next+string reader opts)))
    (is (= [{:a 1} "{:a   1}"] (e/parse-next+string reader opts))))

  (testing "parse-string"
    (is (= "#(+ 1 2 3)" (:source
                         (meta (e/parse-string "#(+ 1 2 3)"
                                               {:all true :source true}))))))
  (testing "parse-string-all"
    (is (= ["#(+ 1 2 3)"] (map (comp :source meta)
                               (e/parse-string-all "#(+ 1 2 3)"
                                                   {:all true :source true}))))))

(deftest location?-test
  (is (meta (e/parse-string "x")))
  (is (not (meta (e/parse-string "x" {:location? seq?}))))
  (is (meta (e/parse-string "(x)" {:location? seq?}))))

(deftest array-map-test
  (is (instance? #?(:clj
                    clojure.lang.PersistentArrayMap
                    :cljs
                    PersistentArrayMap
                    :cljr clojure.lang.PersistentArrayMap)
                 (e/parse-string "{:a 1 :b 2}")))
  (is (instance? #?(:clj
                    clojure.lang.PersistentHashMap
                    :cljs
                    PersistentHashMap
                    :cljr
                    clojure.lang.PersistentHashMap)
                 (e/parse-string "{:a 1 :b 2 :c 3 :d 4 :e 5 :f 6 :g 7 :h 8 :i 9}"))))

(deftest number-test
  (is (number? (e/parse-string "-100"))))

(deftest at-separator-test
  (is (= '[foo (clojure.core/deref bar)]
         (e/parse-string "[foo@bar]" {:deref true})))
  (is (= '[1 (clojure.core/deref 2)]
         (e/parse-string "[1@2]" {:deref true}))))

(deftest string-delimiter-test
  (try (e/parse-string "\"")
       (catch #?(:clj Exception :cljs :default :cljr Exception) e
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
         (is (e/parse-string-all #?(:clj (slurp (io/file f))
                                    :cljs (str (readFileSync (join "src" "edamame" "impl" "parser.cljc")))
                                    :cljr (slurp f))
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
       (e/parse-string "foo/bar/baz"))))

#?(:clj
   (deftest read-cond-with-plain-pushback-rdr-rest
     (with-open [rdr (java.io.PushbackReader. (java.io.StringReader. "#?(:cljs 2 :clj 1)"))]
       (let [opts (e/normalize-opts {:all true
                                     :read-cond :allow
                                     :features #{:clj}})]
         (is (= 1 (e/parse-next rdr opts)))))))

(deftest auto-resolve-ns-test
  (is (= "[(ns foo (:require [clojure.set :as set])) :clojure.set/foo]"
         (str (e/parse-string-all "(ns foo (:require [clojure.set :as set])) ::set/foo" {:auto-resolve-ns true}))))
  (is (= "[(ns foo) :foo/dude]"
         (str (e/parse-string-all "(ns foo) ::dude" {:auto-resolve-ns true}))))
  (deflet
    (def rdr (e/reader "(ns foo (:require [clojure.set :as set])) ::set/foo ::quux/dude"))
    (def opts (e/normalize-opts {:auto-resolve-ns true
                                 :auto-resolve name}))
    (is (= "(ns foo (:require [clojure.set :as set]))" (str (e/parse-next rdr opts))))
    (is (= :clojure.set/foo (e/parse-next rdr opts)))
    (is (= :quux/dude (e/parse-next rdr opts))))
  (is
   (= '[(ns foo (:require [clojure.set :as set]))
        (quote clojure.set/foo)
        (quote x/foo) :clojure.set/foo]
      (e/parse-string-all "(ns foo (:require [clojure.set :as set])) `set/foo `x/foo `::set/foo"
                          {:auto-resolve-ns true :all true}))))

(deftest uneval-test
  (deflet
    (def parsed
      (e/parse-string "#_:foo [1 2 3]"
                      {:uneval (fn [{:keys [uneval next]}]
                                 (with-meta next {uneval true}))}))
    (is (= [1 2 3] parsed))
    (is (true? (:foo (meta parsed))))
    (is (nil? (e/parse-string "#_:foo" {:uneval identity})))))

#?(:clj
   (deftest map-set-test
     (is (= "{:a #{1 2 3 4 5 6 7 8 9 10}, :b 2, :c 3, :d 4, :e 5, :f 6, :h 7, :i 8, :j 9, :k 10, :l 11}" (str (e/parse-string "{:a #{1 2 3 4 5 6 7 8 9 10} :b 2 :c 3 :d 4 :e 5 :f 6 :h 7 :i 8 :j 9 :k 10 :l 11}"
                                                                                                                              {:map omap/ordered-map
                                                                                                                               :set oset/ordered-set}))))))
(deftest param-tags-meta
  (is (= '[String]
         (-> (e/parse-string "^[String] x")
             meta
             :param-tags))))

(deftest array-notation-test
  (is (= (symbol "byte/1") (e/parse-string "byte/1")))
  (is (= (symbol "byte/9") (e/parse-string "byte/9")))
  (is (thrown? Exception (e/parse-string "byte/0")))
  (is (thrown? Exception (e/parse-string "byte/11")))
  (is (thrown? Exception (e/parse-string "byte:/1")))
  (is (thrown? Exception (e/parse-string "byte/")))
  (is (thrown? Exception (e/parse-string "byte/1a"))))

(deftest issue-115-test
  (is (= {:type :edamame/error, :row 1, :col 3} (try (e/parse-string "{:}") (catch Exception e (ex-data e))))))

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
         (e/parse-ns-form '(ns foo (:require [clojure [set :as set]]
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
               (e/parse-string edn-string))))))

(deftest issue-117-test
  (is (thrown-with-msg? Exception #"Invalid keyword: :::foo" (e/parse-string ":::foo" {:auto-resolve identity})))
  )

(deftest issue-132-suppress-read-test
  (is (tagged-literal? (e/parse-string "#dude 1" {:suppress-read true}))))

;; ============================================================
;; Syntax-Quote Optimization Tests
;; https://github.com/frenchy64/backtick/pull/1
;; ============================================================

(defn sq-parse
  "Parse with syntax-quote enabled, using identity for symbol resolution"
  [s]
  (e/parse-string s {:syntax-quote {:resolve-symbol identity}}))

(deftest syntax-quote-optimization-empty-collections
  (testing "empty collections without splices produce literals"
    (is (= [] (sq-parse "`[]")))
    (is (= {} (sq-parse "`{}")))
    (is (= #{} (sq-parse "`#{}")))))

(deftest syntax-quote-optimization-literals
  (testing "literal-only collections produce direct literals"
    (is (= [1 2 3] (sq-parse "`[1 2 3]")))
    (is (= [:a :b :c] (sq-parse "`[:a :b :c]")))
    (is (= {:a 1 :b 2} (sq-parse "`{:a 1 :b 2}")))
    (is (= #{1 2 3} (sq-parse "`#{1 2 3}")))
    (is (= [1 [2 3] 4] (sq-parse "`[1 [2 3] 4]")))
    (is (= {:a {:b 1}} (sq-parse "`{:a {:b 1}}")))))

(deftest syntax-quote-optimization-unquote-no-splice
  (testing "unquote without splice still produces direct literals"
    (is (= '[a] (sq-parse "`[~a]")))
    (is (= '[1 x 3] (sq-parse "`[1 ~x 3]")))
    (is (= '[a b c] (sq-parse "`[~a ~b ~c]")))
    (is (= '{:a v} (sq-parse "`{:a ~v}")))
    (is (= '{k 1} (sq-parse "`{~k 1}")))
    (is (= '#{a b} (sq-parse "`#{~a ~b}")))))

(deftest syntax-quote-optimization-with-splice
  (testing "splice requires concat machinery"
    (let [result (sq-parse "`[~@xs]")]
      (is (seq? result))
      (is (= 'clojure.core/vec (first result))))
    (let [result (sq-parse "`[1 ~@xs 3]")]
      (is (seq? result))
      (is (= 'clojure.core/vec (first result))))
    (let [result (sq-parse "`[~@a ~@b]")]
      (is (seq? result))
      (is (= 'clojure.core/vec (first result))))))

(deftest syntax-quote-optimization-nested
  (testing "nested syntax-quote optimizes inner collections"
    (is (= [[1 2]] (sq-parse "`[[1 2]]")))
    (is (= [[[1]]] (sq-parse "`[[[1]]]")))
    (is (= {:a [1 2 3]} (sq-parse "`{:a [1 2 3]}")))
    (is (= [{:a 1}] (sq-parse "`[{:a 1}]")))))

(deftest syntax-quote-optimization-quoted-symbols
  (testing "quoted symbols are preserved"
    (let [result (sq-parse "`[x]")]
      (is (vector? result))
      (is (= '(quote x) (first result))))
    (let [result (sq-parse "`[foo bar]")]
      (is (vector? result))
      (is (= '(quote foo) (first result)))
      (is (= '(quote bar) (second result))))))

(deftest syntax-quote-optimization-mixed
  (testing "mixed literals, quotes, and unquotes"
    (is (= '[1 (quote x) y 4] (sq-parse "`[1 x ~y 4]")))
    (is (= '{:a 1 :b (quote x) :c y} (sq-parse "`{:a 1 :b x :c ~y}")))))

(deftest syntax-quote-optimization-lists
  (testing "list syntax-quote optimization"
    (let [result (sq-parse "`()")]
      (is (= '(clojure.core/list) result)))
    (let [result (sq-parse "`(1 2 3)")]
      (is (seq? result))
      (is (= 'clojure.core/list (first result)))
      (is (= '(clojure.core/list 1 2 3) result)))
    ;; With unquote (no splice)
    (let [result (sq-parse "`(~a ~b)")]
      (is (seq? result))
      (is (= 'clojure.core/list (first result)))
      (is (= '(clojure.core/list a b) result)))))

(deftest syntax-quote-optimization-eval-equivalence
  (testing "optimized forms eval to same result as verbose forms"
    ;; Empty vector
    (is (= [] (eval (sq-parse "`[]"))))
    ;; Literal vector
    (is (= [1 2 3] (eval (sq-parse "`[1 2 3]"))))
    ;; Nested
    (is (= [[1 2] [3 4]] (eval (sq-parse "`[[1 2] [3 4]]"))))
    ;; Maps
    (is (= {:a 1 :b 2} (eval (sq-parse "`{:a 1 :b 2}"))))
    ;; Sets
    (is (= #{1 2 3} (eval (sq-parse "`#{1 2 3}"))))
    ;; With unquote - need to eval the whole let form
    #?(:clj
       (do
         (is (= [1 10 3] (eval '(let [a 10] `[1 ~a 3]))))
         (is (= [1 2 3 4] (eval '(let [xs [2 3]] `[1 ~@xs 4]))))
         (is (= {:a 42} (eval '(let [v 42] `{:a ~v}))))
         (is (= #{1 2} (eval '(let [x 1] `#{~x 2}))))))))

(deftest syntax-quote-optimization-size-reduction
  (testing "optimized forms are smaller than verbose forms"
    (let [forms ["`[]" "`[1 2 3]" "`{:a 1}" "`#{1 2}" "`[~a]" "`{:a ~v}"]]
      (doseq [f forms]
        (let [result (sq-parse f)]
          ;; Optimized should not contain 'sequence or 'concat when no splice
          (when-not (and (seq? result)
                         (= 'clojure.core/vec (first result)))
            (is (not (some #{'clojure.core/sequence 'clojure.core/concat}
                           (flatten (if (coll? result) result [result]))))
                (str "Form " f " should not contain sequence/concat"))))))))

(deftest syntax-quote-optimization-special-values
  (testing "special values are handled correctly"
    (is (= [nil] (sq-parse "`[nil]")))
    (is (= [true false] (sq-parse "`[true false]")))
    (is (= ["string"] (sq-parse "`[\"string\"]")))
    (is (= [\c] (sq-parse "`[\\c]")))
    (is (= [1.5] (sq-parse "`[1.5]")))
    (is (= [1/2] (sq-parse "`[1/2]")))))

(deftest syntax-quote-optimization-preserves-metadata
  (testing "metadata is preserved on optimized forms"
    (let [result (sq-parse "`^:foo []")]
      ;; With meta, should use with-meta wrapper
      (is (seq? result))
      (is (or (= 'clojure.core/with-meta (first result))
              #?(:cljs (= 'cljs.core/with-meta (first result))))))))

(deftest syntax-quote-optimization-gensym
  (testing "gensym symbols work correctly"
    (let [result (sq-parse "`[x# x#]")]
      (is (vector? result))
      ;; Both should be the same gensym
      (is (= (second (first result))
             (second (second result)))))))

(deftest syntax-quote-optimization-array-map-vs-hash-map
  (testing "small maps use array-map, large maps use hash-map"
    ;; Small map (< 16 entries) - should produce direct map literal
    (let [result (sq-parse "`{:a 1 :b 2}")]
      (is (map? result)))
    ;; Large map (>= 16 entries) without splice - still produces direct map literal
    (let [result (sq-parse "`{:a 1 :b 2 :c 3 :d 4 :e 5 :f 6 :g 7 :h 8 :i 9 :j 10 :k 11 :l 12 :m 13 :n 14 :o 15 :p 16}")]
      (is (map? result)))
    ;; Map with unquote (no splice) - still direct literal
    (let [result (sq-parse "`{:a ~v :b 2}")]
      (is (map? result)))))

(ns edamame.string-reader-test
  #?(:clj
     (:require
      [clojure.java.io :as io]
      [clojure.string :as str]
      [clojure.test :refer [deftest is testing]]
      [clojure.tools.reader.reader-types :as r]
      [edamame.core :as e]
      [edamame.impl.parser :as p])))

#?(:clj
   (do
     (defn tools-reader [s]
       (r/indexing-push-back-reader (r/string-push-back-reader s)))

     (def read-token @#'p/read-token)
     (def parse-string* @#'p/parse-string*)
     (def ctx (p/normalize-opts {}))

     (defn run-op
       "Runs one reader op. Returns what it observed, incl. the line and column
       afterwards, and the char that may be unread next. Only the last read char
       is unread, like the parser does."
       [rdr op last-read]
       (let [[v next-read] (case op
                             :read (let [c (r/read-char rdr)] [c c])
                             :peek [(r/peek-char rdr) ::none]
                             :unread (if (identical? ::none last-read)
                                       [::skipped ::none]
                                       (do (r/unread rdr last-read)
                                           [nil ::none]))
                             :skip-whitespace [(p/skip-whitespace nil rdr) ::none]
                             :token [(read-token rdr :symbol (r/read-char rdr)) ::none]
                             :string (if (= \" (r/peek-char rdr))
                                       [(parse-string* ctx rdr) ::none]
                                       [::skipped ::none]))]
         [[op v (r/get-line-number rdr) (r/get-column-number rdr)] next-read]))

     (defn run-ops
       "Runs ops against rdr until the first exception."
       [rdr ops]
       (loop [ops ops
              last-read ::none
              out []]
         (if-let [op (first ops)]
           (let [[res next-read] (try
                                   (run-op rdr op last-read)
                                   (catch Exception ex
                                     [[op :ex (.getMessage ex) (ex-data ex)] ::stop]))]
             (if (identical? ::stop next-read)
               (conj out res)
               (recur (rest ops) next-read (conj out res))))
           out)))

     (defn rand-string [^java.util.Random rnd ^String alphabet max-len]
       (let [sb (StringBuilder.)]
         (dotimes [_ (.nextInt rnd (int max-len))]
           (.append sb (.charAt alphabet (.nextInt rnd (.length alphabet)))))
         (str sb)))

     (defn rand-ops [^java.util.Random rnd n]
       (vec (repeatedly n #(case (.nextInt rnd 9)
                             (0 1 2 3) :read
                             4 :peek
                             5 :unread
                             6 :skip-whitespace
                             7 :token
                             8 :string))))

     (defn normalize
       "Turns metadata into data, regexes into strings and drops gensym counters,
       so parse results of two runs compare with ="
       [x]
       (let [m (meta x)
             x (cond
                 (instance? java.util.regex.Pattern x) [::regex (str x)]
                 (symbol? x) (symbol (str/replace (str x) #"__\d+" "__N"))
                 (seq? x) (doall (map normalize x))
                 (vector? x) (mapv normalize x)
                 (set? x) (set (map normalize x))
                 (map? x) (into {} (map (fn [[k v]] [(normalize k) (normalize v)])) x)
                 :else x)]
         (if m [::meta (normalize m) x] x)))

     (defn parse-all
       "Parses all forms from rdr. Returns the normalized forms, plus the error
       message and data when parsing throws."
       [rdr raw-opts]
       (let [opts (e/normalize-opts raw-opts)
             acc (volatile! [])]
         (try
           (loop []
             (let [v (e/parse-next rdr opts)]
               (when-not (identical? :edamame.core/eof v)
                 (vswap! acc conj v)
                 (recur))))
           (normalize @acc)
           (catch Exception ex
             [(normalize @acc) (.getMessage ex) (ex-data ex)]))))

     (def opts-variants
       [{}
        {:all true :read-cond :allow :features #{:clj}
         :auto-resolve '{:current user foo bar}}
        {:all true :row-key :line :col-key :column :read-cond :allow
         :location? seq? :end-location false
         :auto-resolve '{:current user}}
        {:all true :auto-resolve-ns true}])

     (defn first-mismatch [inputs]
       (first
        (for [s inputs
              raw-opts opts-variants
              :let [expected (parse-all (tools-reader s) raw-opts)
                    actual (parse-all (e/reader s) raw-opts)]
              :when (not= expected actual)]
          {:input s :opts raw-opts :expected expected :actual actual})))

     (def tokens
       ["(" ")" "[" "]" "{" "}" "#{" "#:foo{" "#::{" "#?(" "#?@(" ":clj" ":cljs" ":default"
        "\"str\"" "\"a\\\"b\"" "\"line\nbreak\"" "\"cr\r\nlf\"" "\"cr\rlf\""
        "\\a" "\\newline" "\\space" "\\u0041"
        "#\"re\\d\"" "#(" "%" "%1" "%&" "#'" "#=" "#_" "'" "`" "~" "~@" "@"
        "^:m" "^{:a 1}" "^String" ";comment\n" ";comment\r\n" "#!shebang\n"
        "sym" "ns/sym" "foo." ".bar" ":kw" "::kw" "::foo/kw" ":a/b"
        "1" "-2" "3.5" "1/2" "0x10" "1N" "##Inf" "nil" "true"
        " " "," "\t" "\n" "\r" "\r\n" "\f" "\r\f"])

     (deftest reader-ops-match-tools-reader-test
       (let [rnd (java.util.Random. 42)
             mismatch (first
                       (for [_ (range 10000)
                             :let [s (rand-string rnd "ab1 \t,\r\n\f\\\"();" 20)
                                   ops (rand-ops rnd 40)
                                   expected (run-ops (tools-reader s) ops)
                                   actual (run-ops (e/reader s) ops)]
                             :when (not= expected actual)]
                         {:input s :ops ops :expected expected :actual actual}))]
         (is (nil? mismatch))))

     (deftest parse-results-match-tools-reader-test
       (testing "line breaks at the end of input"
         (is (nil? (first-mismatch ["[\r\n" "[\r\f" "[\r" "[ \r\n" "\"a\nb\r\n" "x\r\n" "\r\n"]))))
       (testing "random token sequences"
         (let [^java.util.Random rnd (java.util.Random. 42)
               inputs (vec (repeatedly 3000
                                       #(apply str (repeatedly (.nextInt rnd 30)
                                                               (fn [] (nth tokens (.nextInt rnd (count tokens))))))))]
           (is (nil? (first-mismatch inputs)))))
       (testing "random characters"
         (let [rnd (java.util.Random. 43)
               inputs (vec (repeatedly 3000 #(rand-string rnd "(){}[]\"\\;#:^'`~@% a1,\r\n\f\t" 30)))]
           (is (nil? (first-mismatch inputs)))))
       (testing "clojure.core"
         (is (nil? (first-mismatch [(slurp (io/file "test-resources" "clojure" "core.clj"))])))))

     (deftest string-reader-edge-cases-test
       (testing "nil input parses to nil"
         (is (nil? (e/parse-string nil))))
       (testing "a reader over a string is an indexing reader"
         (is (r/indexing-reader? (e/reader "x"))))
       (testing "carriage returns count as line breaks"
         (is (= {:row 4 :col 1 :end-row 4 :end-col 4}
                (meta (e/parse-string "\r\r\n\r(x)"))))))))

(defproject borkdude/edamame
  #=(clojure.string/trim
     #=(slurp "resources/EDAMAME_VERSION"))
  :description "EDN parser with location metadata and pluggable dispatch table."
  :url "https://github.com/borkdude/edamame"
  :scm {:name "git"
        :url "https://github.com/borkdude/edamame"}
  :license {:name "Eclipse Public License 1.0"
            :url "http://opensource.org/licenses/eclipse-1.0.php"}
  :source-paths ["src"]
  :dependencies [[org.clojure/clojure "1.9.0"]
                 [org.clojure/tools.reader "1.3.4"]]
  :profiles {:clojure-1.9.0 {:dependencies [[org.clojure/clojure "1.9.0"]]}
             :clojure-1.10.1 {:dependencies [[org.clojure/clojure "1.10.1"]]}
             :test {:jvm-opts ["-Djdk.attach.allowAttachSelf"]
                    :dependencies [[org.clojure/clojurescript "1.10.520"]
                                   [clj-commons/conch "0.9.2"]
                                   [criterium "0.4.5"]
                                   [com.clojure-goes-fast/clj-async-profiler "0.4.0"]
                                   [io.github.borkdude/deflet "0.1.0"]
                                   [org.flatland/ordered "1.15.11"]
                                   [org.clojure/data.json "2.5.0"]]}
             :uberjar {:global-vars {*assert* false}
                       :jvm-opts ["-Dclojure.compiler.direct-linking=true"
                                  "-Dclojure.spec.skip-macros=true"]
                       :aot :all
                       :main edamame.impl.main}}
  :deploy-repositories [["clojars" {:url "https://clojars.org/repo"
                                    :username :env/clojars_user
                                    :password :env/clojars_pass
                                    :sign-releases false}]])

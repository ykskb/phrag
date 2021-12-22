(defproject phrag "0.1.0-SNAPSHOT"
  :description "Instantly operational GraphQL handler"
  :url "https://github.com/ykskb/phrag"
  :min-lein-version "2.0.0"
  :dependencies [[org.clojure/clojure "1.10.3"]
                 [org.clojure/java.jdbc "0.7.12"]
                 [org.clojure/tools.logging "0.3.1"]
                 [com.github.seancorfield/honeysql "2.0.0-rc3"]
                 [com.walmartlabs/lacinia "0.39-alpha-9"]
                 [ring/ring-core "1.9.3"]
                 [camel-snake-kebab "0.4.2"]
                 [inflections "0.13.2"]
                 [superlifter "0.1.3"]]
  :resource-paths ["resources" "target/resources"]
  :plugins [[lein-eftest "0.5.9"]
            [lein-cloverage "1.2.2"]]
  :eftest {:report eftest.report.pretty/report
           :report-to-file "target/junit.xml"}
  :profiles
  {:dev  [:project/dev :profiles/dev]
   :repl {:repl-options {:init-ns user}}
   :profiles/dev {}
   :project/dev  {:source-paths   ["dev/src"]
                  :resource-paths ["dev/resources"]
                  :dependencies   [[alekcz/charmander "1.0.2"]
                                   [bidi "2.1.6"]
                                   [cheshire "5.10.1"]
                                   [eftest "0.5.9"]
                                   [hawk "0.2.11"]
                                   [kerodon "0.9.1"]
                                   [integrant/repl "0.3.2"]
                                   [metosin/reitit "0.5.15"]
                                   [ch.qos.logback/logback-classic "1.1.1"]
                                   [org.postgresql/postgresql "42.3.0"]
                                   [org.xerial/sqlite-jdbc "3.34.0"]
                                   [ring-cors "0.1.13"]
                                   [ring/ring-json "0.5.1"]
                                   [ring/ring-jetty-adapter "1.9.3"]
                                   [threatgrid/ring-graphql-ui "0.1.3"]]}})


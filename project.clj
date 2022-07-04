(defproject com.github.ykskb/phrag "0.4.6"
  :description "GraphQL from a DB connection"
  :url "https://github.com/ykskb/phrag"
  :min-lein-version "2.0.0"
  :dependencies [[org.clojure/clojure "1.10.3"]
                 [org.clojure/java.jdbc "0.7.12"]
                 [org.clojure/tools.logging "0.3.1"]
                 [org.postgresql/postgresql "42.3.0"]
                 [org.xerial/sqlite-jdbc "3.34.0"]
                 [com.github.seancorfield/honeysql "2.0.0-rc3"]
                 [com.walmartlabs/lacinia "0.39-alpha-9"]
                 [metosin/jsonista "0.3.6"]
                 [ring/ring-core "1.9.3"]
                 [camel-snake-kebab "0.4.2"]
                 [environ "1.2.0"]
                 [hikari-cp "2.14.0"]
                 [inflections "0.13.2"]
                 [integrant "0.8.0"]]
  :plugins [[lein-eftest "0.5.9"]
            [lein-cloverage "1.2.2"]]
  :eftest {:report eftest.report.pretty/report
           :report-to-file "target/junit.xml"}
  :profiles
  {:1.8 {:dependencies [[org.clojure/clojure "1.8.0"]]}
   :1.9 {:dependencies [[org.clojure/clojure "1.9.0"]]}
   :1.11 {:dependencies [[org.clojure/clojure "1.11.1"]]}})


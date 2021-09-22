(ns dev-bidi
  (:refer-clojure :exclude [test])
  (:require [clojure.repl :refer :all]
            ;[next.jdbc :as jdbc]
            [clojure.java.jdbc :as jdbc]
            [bidi.ring :refer [make-handler]]
            [fipp.edn :refer [pprint]]
            [clojure.tools.namespace.repl :refer [refresh]]
            [clojure.java.io :as io]
            [phrag.core :as phrag]
            [eftest.runner :as eftest]
            [integrant.core :as ig]
            [integrant.repl :refer [clear halt go init prep reset]]
            [integrant.repl.state :refer [config system]]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.params :as prm]
            [ring.middleware.json :refer [wrap-json-params wrap-json-response]]
            [ring.util.response :as res]
            [ring-graphql-ui.core :as gql]))

(defn test []
  (eftest/run-tests (eftest/find-tests "test")))

(clojure.tools.namespace.repl/set-refresh-dirs
 "src" "test" "dev/src/dev_bidi.clj")

;;; handlers

(defmethod ig/init-key ::app [_ {:keys [routes]}]
  (println routes)
  (-> (make-handler routes)
      (gql/wrap-graphiql {:path "/graphiql"
                          :endpoint "/graphql"})
      wrap-json-params
      wrap-json-response))

;;; DB

(defmethod ig/init-key :database.sql/connection [_ db-spec]
  {:connection (jdbc/get-connection db-spec)})
;  (jdbc/get-datasource db-spec))

;;; API server

(defmethod ig/init-key ::server [_ {:keys [app options]}]
  (jetty/run-jetty app options))

(defmethod ig/halt-key! ::server [_ server]
  (.stop server))

(integrant.repl/set-prep!
 (constantly {:database.sql/connection
              {:connection-uri "jdbc:sqlite:db/dev.sqlite"}
              ;{:dbtype "sqlite" :dbname "dev.sqlite"}
              :phrag.core/bidi-graphql-route {:db (ig/ref :database.sql/connection)}
              ::app {:routes (ig/ref :phrag.core/bidi-graphql-route)}
              ::server {:app (ig/ref ::app)
                        :options {:port 3000
                                  :join? false}}}))

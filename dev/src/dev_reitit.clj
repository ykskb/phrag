(ns dev-reitit
  (:refer-clojure :exclude [test])
  (:require [clojure.repl :refer :all]
            ;[next.jdbc :as jdbc]
            [clojure.java.jdbc :as jdbc]
            [fipp.edn :refer [pprint]]
            [clojure.tools.namespace.repl :refer [refresh]]
            [clojure.java.io :as io]
            [phrag.core :as phrag]
            [eftest.runner :as eftest]
            [integrant.core :as ig]
            [integrant.repl :refer [clear halt go init prep reset]]
            [integrant.repl.state :refer [config system]]
            [ring.adapter.jetty :as jetty]
            [reitit.ring :as ring]
            [reitit.swagger :as swagger]
            [reitit.swagger-ui :as swagger-ui]
            [reitit.ring.coercion :as coercion]
            [reitit.coercion.spec]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [reitit.ring.middleware.exception :as exception]
            [reitit.ring.middleware.multipart :as multipart]
            [reitit.ring.middleware.parameters :as parameters]
            [ring.middleware.params :as params]
            [ring-graphql-ui.core :as gql]
            [muuntaja.core :as m]
            [clojure.java.io :as io]))

(defn test []
  (eftest/run-tests (eftest/find-tests "test")))

(clojure.tools.namespace.repl/set-refresh-dirs
 "src" "test" "dev/src/dev_reitit.clj")

;;; handlers

(defmethod ig/init-key ::app [_ {:keys [routes]}]
  (pprint routes)
  (ring/ring-handler
   (ring/router
       routes
       {:data {:coercion reitit.coercion.spec/coercion
               :muuntaja m/instance
               :middleware [parameters/parameters-middleware
                            muuntaja/format-negotiate-middleware
                            muuntaja/format-response-middleware
;                            exception/exception-middleware
                            muuntaja/format-request-middleware
;                            coercion/coerce-response-middleware
                            coercion/coerce-request-middleware
                            multipart/multipart-middleware]}})
      (ring/routes
       (swagger-ui/create-swagger-ui-handler {:path "/"})
       (gql/graphiql {:endpoint "/graphql"})
       (ring/create-default-handler))
  ))

;;; DB

(defmethod ig/init-key :database.sql/connection [_ db-spec]
  {:connection (jdbc/get-connection db-spec)})

;;; API server

(defmethod ig/init-key ::server [_ {:keys [app options]}]
  (jetty/run-jetty app options))

(defmethod ig/halt-key! ::server [_ server]
  (.stop server))

(integrant.repl/set-prep!
 (constantly {:database.sql/connection
              ;{:connection-uri "jdbc:sqlite:db/dev.sqlite"}
              {:dbtype "postgresql"
               :dbname "postgres"
               :host "localhost"
               :port 5432
               :user "postgres"
               :password "example"
               :stringtype "unspecified"}
              :phrag.core/reitit-graphql-route
              {:db (ig/ref :database.sql/connection)}
              ::app {:routes (ig/ref :phrag.core/reitit-graphql-route)}
              ::server {:app (ig/ref ::app)
                        :options {:port 3000
                                  :join? false}}}))

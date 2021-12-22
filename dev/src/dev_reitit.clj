(ns dev-reitit
  (:refer-clojure :exclude [test])
  (:require [charmander.core :as charm]
            [cheshire.core :as chesh]
            [clojure.repl :refer :all]
            [clojure.string :as s]
            [clojure.java.jdbc :as jdbc]
            [fipp.edn :refer [pprint]]
            [clojure.tools.namespace.repl :refer [refresh]]
            [clojure.java.io :as io]
            [phrag.core :as phrag]
            [phrag.db :as phrag-db]
            [eftest.runner :as eftest]
            [integrant.core :as ig]
            [integrant.repl :refer [clear halt go init prep reset]]
            [integrant.repl.state :refer [config system]]
            [ring.adapter.jetty :as jetty]
            [reitit.ring :as ring]
            [reitit.ring.coercion :as coercion]
            [reitit.coercion.spec]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [reitit.ring.middleware.exception :as exception]
            [reitit.ring.middleware.multipart :as multipart]
            [reitit.ring.middleware.parameters :as parameters]
            [ring.middleware.params :as params]
            [ring-graphql-ui.core :as gql]
            [ring.middleware.cors :refer [wrap-cors]]
            [muuntaja.core :as m])
  (:import java.util.Base64))

(defn test []
  (eftest/run-tests (eftest/find-tests "test")))

(clojure.tools.namespace.repl/set-refresh-dirs
 "src" "test" "dev/src/dev_reitit.clj")

(def ^:private firebase-prj-id "")

(defn- user-by-auth-id [db auth-id]
  (let [user (phrag-db/list-up db "enduser" {:where [:= :auth_id auth-id]})]
    (if (> (count user) 0)
      (first user)
      nil)))

(defn- validated-payload [req]
  (let  [token (get-in req [:headers "authorization"])
         validated (charm/validate-token firebase-prj-id token)]
    validated))

(defn- update-user-info [req db]
  (let [payload (validated-payload req)
        user (if (nil? payload) nil (user-by-auth-id db (:uid payload)))]
    (-> req
        (assoc :auth-payload payload)
        (assoc :auth-user user))))

(defn- auth-middleware [db]
  {:name ::user
   :wrap (fn [handler]
           (fn
             ([req]
                (handler (update-user-info req db)))
             ([req res raise]
              (handler (update-user-info req db) res raise))))})

(defmethod ig/init-key ::app [_ {:keys [routes db]}]
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
                            multipart/multipart-middleware
                            (auth-middleware db)]}})
      (ring/routes
       (gql/graphiql {:endpoint "/graphql"})
       (ring/create-default-handler))
  ))

(defmethod ig/init-key :database.sql/connection [_ db-spec]
  {:connection (jdbc/get-connection db-spec)})

(defmethod ig/init-key ::server [_ {:keys [app options]}]
  (jetty/run-jetty app options))

(defmethod ig/halt-key! ::server [_ server]
  (.stop server))

(defn- user-or-throw [ctx]
  (let [user (get-in ctx [:req :auth-user])]
    (when (nil? user)
      (throw (ex-info "Not authenticated." {})))
    user))

(defn- pre-create-user [args ctx]
  (let [payload (get-in ctx [:req :auth-payload])
        user (get-in ctx [:req :auth-user])]
    (if user
      nil
      (-> args
          (assoc :auth_id (:uid payload))
          (assoc :email (:email payload))))))

(defn- update-created-by [args ctx]
  (let [user (user-or-throw ctx)]
    (assoc args :created_by (:id user))))

(def ^:private signals {:enduser {:create {:pre pre-create-user}}
                        :post {:create {:pre update-created-by}}
                        :post_like {:create {:pre update-created-by}}
                        :post_dislike {:create {:pre update-created-by}}
                        :tag_follow {:create {:pre update-created-by}}})

(def ^:private tables [{:name "enduser"
                        :table-root :root
                        :columns [{:name "id"
                                   :type "int"
                                   :notnull true
                                   :dflt_value nil}
                                  {:name "username"
                                   :type "character varying"
                                   :notnull false
                                   :dflt_value nil}
                                  {:name "email"
                                   :type "character varying"
                                   :notnull true
                                   :dflt_value nil}]}])

(def ^:private reitit-cors-middleware
  #(wrap-cors % :access-control-allow-origin [#".*"]
              :access-control-allow-methods [:post]
              :access-control-allow-credentials "true"
              :access-control-allow-headers #{"accept"
                                              "accept-encoding"
                                              "accept-language"
                                              "authorization"
                                              "content-type"
                                              "origin"}))

(integrant.repl/set-prep!
 (constantly {:database.sql/connection
              ;{:connection-uri "jdbc:sqlite:db/dev.sqlite"}
              {:dbtype "postgresql"
               ;:dbname "postgres"
               :dbname "learn"
               :host "localhost"
               :port 5432
               :user "postgres"
               :password "example"
               :stringtype "unspecified"
               :currentSchema "public"}
              :phrag.core/reitit-graphql-route
              {:db (ig/ref :database.sql/connection)
               :tables tables
               :signals signals
               :middleware [reitit-cors-middleware]}
              ::app {:routes (ig/ref :phrag.core/reitit-graphql-route)
                     :db (ig/ref :database.sql/connection)}
              ::server {:app (ig/ref ::app)
                        :options {:port 3001
                                  :join? false}}}))

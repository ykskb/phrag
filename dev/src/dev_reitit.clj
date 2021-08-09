(ns dev-reitit
  (:refer-clojure :exclude [test])
  (:require [clojure.repl :refer :all]
            ;[next.jdbc :as jdbc]
            [clojure.java.jdbc :as jdbc]
            [fipp.edn :refer [pprint]]
            [clojure.tools.namespace.repl :refer [refresh]]
            [clojure.java.io :as io]
            [sapid.core :as sapid]
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
            [muuntaja.core :as m]
            [clojure.java.io :as io]
            ))

(defn test []
  (eftest/run-tests (eftest/find-tests "test")))

(clojure.tools.namespace.repl/set-refresh-dirs
 "src" "test" "dev/src/dev_reitit.clj")

(defmethod ig/init-key ::m-app [_ _]
  (ring/ring-handler
   (ring/router
    [["/swagger.json"
      {:get {:no-doc true
             :swagger {:info {:title "my-api"}
                       :basePath "/"} ;; prefix for all paths
             :handler (swagger/create-swagger-handler)}}]

     ["/math"
      {:swagger {:tags ["math"]}}

      ["/plus"
       {:get {:summary "plus with spec query parameters"
              :parameters {:query {:x int?, :y int?}}
              :responses {200 {:body {:total int?}}}
              :handler (fn [{{{:keys [x y]} :query} :parameters :as p}]
                         (println (:parameters p))
                         {:status 200
                          :body {:total (+ x y)}})}
        :post {:summary "plus with spec body parameters"
               :parameters {:body {:x int?, :y int?}}
               :responses {200 {:body {:total int?}}}
               :handler (fn [{{{:keys [x y]} :body} :parameters}]
                          {:status 200
                           :body {:total (+ x y)}})}}]]]

    {:data {:coercion reitit.coercion.spec/coercion
            :muuntaja m/instance
            :middleware [;; query-params & form-params
                         parameters/parameters-middleware
                         ;; content-negotiation
                         muuntaja/format-negotiate-middleware
                         ;; encoding response body
                         muuntaja/format-response-middleware
                         ;; exception handling
                         exception/exception-middleware
                         ;; decoding request body
                         muuntaja/format-request-middleware
                         ;; coercing response bodys
                         coercion/coerce-response-middleware
                         ;; coercing request parameters
                         coercion/coerce-request-middleware
                         ;; multipart
                         multipart/multipart-middleware]}})
   (ring/routes
    (swagger-ui/create-swagger-ui-handler {:path "/"})
    (ring/create-default-handler))))

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
                            exception/exception-middleware
                            muuntaja/format-request-middleware
                            coercion/coerce-response-middleware
                            coercion/coerce-request-middleware
                            multipart/multipart-middleware]}})
      (ring/routes
       (swagger-ui/create-swagger-ui-handler {:path "/"})
       (ring/create-default-handler))
  ))

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
              ; {:dbtype "sqlite" :dbname "dev.sqlite"}
              :sapid.core/reitit-routes {:db (ig/ref :database.sql/connection)}
              ::app {:routes (ig/ref :sapid.core/reitit-routes)}
              ; ::app {}
              ::server {:app (ig/ref ::app)
                        :options {:port 3000
                                  :join? false}}}))

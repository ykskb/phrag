(ns phrag.route
  (:require [clojure.walk :as w]
            [ring.util.response :as ring-res]
            [phrag.graphql :as gql]))

(defmulti graphql-route (fn [config & _] (:router config)))

;;; reitit

(defn- rtt-gql-handler [config]
  (let [schema (gql/schema config)
        sl-conf (gql/sl-config config)]
    (fn [req]
      (let [params (:body-params req)
            query (:query params)
            vars (:variables params)]
        {:status 200
         :body (gql/exec config sl-conf schema query vars req)}))))

(defmethod graphql-route :reitit [config]
  ["/graphql" {:post {:handler (rtt-gql-handler config)}
               :middleware (:middleware config)}])

;;; Bidi

(defn- bd-gql-handler [config]
  (let [schema (gql/schema config)
        sl-conf (gql/sl-config config)]
    (fn [req]
      (let [params (:params req)
            query (get params "query")
            vars (w/keywordize-keys (get params "variables"))]
        (ring-res/response (gql/exec config sl-conf schema query vars))))))

(defmethod graphql-route :bidi [config]
  ["/" {"graphql" {:post (bd-gql-handler config)}}])

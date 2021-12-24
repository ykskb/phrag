(ns phrag.route
  (:require [clojure.walk :as w]
            [ring.util.response :as ring-res]
            [phrag.graphql :as gql]))

(defmulti graphql-route (fn [config & _] (:router config)))

;;; reitit

(defn- rtt-param-data [req]
  (w/stringify-keys (or (:body-params req) (:form-params req))))

(defn- rtt-gql-handler [config]
  (let [schema (gql/schema config)]
    (fn [req]
      (let [params (rtt-param-data req)
            query (get params "query")
            vars (w/keywordize-keys (get params "variables"))]
        {:status 200
         :body (gql/exec config schema query vars req)}))))

(defmethod graphql-route :reitit [config]
  ["/graphql" {:post {:handler (rtt-gql-handler config)}
               :middleware (:middleware config)}])

;;; Bidi

(defn- bd-gql-handler [config]
  (let [schema (gql/schema config)]
    (fn [req]
      (let [params (:params req)
            query (get params "query")
            vars (w/keywordize-keys (get params "variables"))]
        (ring-res/response (gql/exec config schema query vars))))))

(defmethod graphql-route :bidi [config]
  ["/" {"graphql" {:post (bd-gql-handler config)}}])

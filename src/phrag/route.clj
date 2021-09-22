(ns phrag.route
  (:require [clojure.walk :as w]
            [com.walmartlabs.lacinia :as lcn]
            [ring.util.response :as ring-res]
            [phrag.graphql :as gql]))

(defmulti graphql-route (fn [config & _] (:router config)))

(defn- rtt-param-data [req]
  (w/stringify-keys (or (:body-params req) (:form-params req))))

;;; reitit

(defn- rtt-gql-handler [schema]
  (fn [req]
    (let [params (rtt-param-data req)
          query (get params "query")
          vars (w/keywordize-keys (get params "variables"))
          result (lcn/execute schema query vars nil)]
      {:status 200
       :body result})))

(defmethod graphql-route :reitit [config]
  (let [schema (gql/schema config)]
    ["/graphql" {:post {:handler (rtt-gql-handler schema)}}]))

;;; Bidi

(defn- bd-gql-handler [schema]
  (fn [req]
    (let [params (:params req)
          query (get params "query")
          vars (w/keywordize-keys (get params "variables"))
          result (lcn/execute schema query vars nil)]
      (ring-res/response result))))

(defmethod graphql-route :bidi [config]
  (let [schema (gql/schema config)]
    ["/" {"graphql" {:post (bd-gql-handler schema)}}]))

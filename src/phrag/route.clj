(ns phrag.route
  (:require [clojure.walk :as w]
            [ring.util.response :as ring-res]
            [phrag.graphql :as gql]))

(defmulti graphql-route (fn [config & _] (:router config)))

(defn- rtt-param-data [req]
  (w/stringify-keys (or (:body-params req) (:form-params req))))

;;; reitit

(defn- rtt-gql-handler [config]
  (let []
    (fn [req]
      (let [sl-ctx (gql/sl-ctx config)
            scm (gql/schema config sl-ctx)
            params (rtt-param-data req)
            query (get params "query")
            vars (w/keywordize-keys (get params "variables"))
            result {:status 200
                    :body (gql/exec scm query vars)}]
        (gql/sl-stop! sl-ctx)
        result))))

(defmethod graphql-route :reitit [config]
  ["/graphql" {:post {:handler (rtt-gql-handler config)}}])

;;; Bidi

(defn- bd-gql-handler [config]
  (fn [req]
    (let [params (:params req)
          query (get params "query")
          vars (w/keywordize-keys (get params "variables"))
          result (gql/exec query vars config)]
      (ring-res/response result))))

(defmethod graphql-route :bidi [config]
  ["/" {"graphql" {:post (bd-gql-handler config)}}])

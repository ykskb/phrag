(ns phrag.route
  (:require [clojure.walk :as w]
            [integrant.core :as ig]
            [ring.util.response :as ring-res]
            [phrag.core :as core]
            [phrag.context :as ctx]))

(defmulti graphql-route (fn [config & _] (:router config)))

;;; Reitit

(defn- rtt-gql-handler [config]
  (let [schema (core/schema config)]
    (fn [req]
      (let [params (:body-params req)
            query (:query params)
            vars (:variables params)]
        {:status 200
         :body (core/exec config schema query vars req)}))))

(defmethod graphql-route :reitit [config]
  ["/graphql" {:post {:handler (rtt-gql-handler config)}
               :middleware (:middleware config)}])

(defn reitit [options]
  (graphql-route (ctx/options->config (assoc options :router :reitit))))

(defmethod ig/init-key ::reitit [_ options]
  (reitit options))

;;; Bidi

(defn- bd-gql-handler [config]
  (let [schema (core/schema config)]
    (fn [req]
      (let [params (:params req)
            query (get params "query")
            vars (w/keywordize-keys (get params "variables"))]
        (ring-res/response (core/exec config schema query vars))))))

(defmethod graphql-route :bidi [config]
  ["/" {"graphql" {:post (bd-gql-handler config)}}])

(defn bidi [options]
  (graphql-route (ctx/options->config (assoc options :router :bidi))))

(defmethod ig/init-key ::bidi [_ options]
  (bidi options))

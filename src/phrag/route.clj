(ns phrag.route
  "Routes + handlders for reitit and bidi."
  (:require [clojure.walk :as w]
            [integrant.core :as ig]
            [ring.util.response :as ring-res]
            [phrag.core :as core]
            [phrag.context :as ctx]))

;;; Reitit

(defn- rtt-gql-handler [config]
  (let [schema (core/schema config)]
    (fn [req]
      (let [params (:body-params req)
            query (:query params)
            vars (:variables params)]
        {:status 200
         :body (core/exec config schema query vars req)}))))

(defn reitit
  "Returns a route setup for reitit at specified path or `/graphql`.
  Format: `[\"path\" {:post handler}]"
  [options]
  (let [config (ctx/options->config options)]
    [(:graphql-path config "/graphql") {:post {:handler (rtt-gql-handler config)}
                                        :middleware (:middleware config)}]))

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

(defn bidi
  "Returns a route setup for Bidi at specified path or `/graphql`.
  Format: `[\"/\" {\"path\" {:post handler}}]"
  [options]
  (let [config (ctx/options->config options)]
    ["/" {(:graphql-path config "graphql") {:post (bd-gql-handler config)}}]))

(defmethod ig/init-key ::bidi [_ options]
  (bidi options))

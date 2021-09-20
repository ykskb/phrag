(ns phrag.route
  (:require [phrag.handlers.bidi :as bd]
            [phrag.handlers.reitit :as rtt]
            [phrag.table :as tbl]
            [phrag.graphql :as gql]))

(defmulti graphql-route (fn [config & _] (:router config)))

;;; reitit

(defmethod graphql-route :reitit [config]
  (let [schema (gql/schema config)]
    ["/graphql" {:post {:handler (rtt/graphql schema)}}]))

;;; Bidi

;;; Duct Ataraxy

(defn- handler-key [_project-ns action]
  (keyword "phrag.handlers.duct-ataraxy" action))

(defn- route-key [project-ns resource action]
  (let [ns (str project-ns ".handler." resource)] (keyword ns action)))

(defn- handler-map [handler-key route-key opts]
  (derive route-key handler-key)
  {[handler-key route-key] opts})

(defn- route-map [path route-key param-names]
  (if (coll? param-names)
    {path (into [] (concat [route-key] param-names))}
    {path [route-key]}))

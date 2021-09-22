(ns phrag.core
  (:require [clojure.string :as s]
            [duct.core :as core]
            [phrag.route :as rt]
            [phrag.table :as tbl]
            [integrant.core :as ig]
            [clojure.pprint :as pp]))

(defn- options->config [options]
(let [db (:db options)
        config {:router (:router options)
                :db db
                :table-name-plural (:table-name-plural options true)
                :resource-path-plural (:resource-path-plural options true)
                :project-ns (:project-ns options)
                :db-keys (:db-keys options)
                :db-ref (:db-ref options)}]
    (assoc config :tables (or (:tables options) (tbl/schema-from-db config db)))))

;;; reitit

(defn make-reitit-graphql-route [options]
  (let [db (or (:db options) nil)
        config (options->config (-> options
                                    (assoc :router :reitit)
                                    (assoc :db db)))]
  (rt/graphql-route config)))

(defmethod ig/init-key ::reitit-graphql-route [_ options]
  (make-reitit-graphql-route options))

;;; bidi

(defn make-bidi-graphql-route [options]
  (let [db (or (:db options) nil)
        config (options->config (-> options
                                    (assoc :router :bidi)
                                    (assoc :db db)))]
    (rt/graphql-route config)))

(defmethod ig/init-key ::bidi-graphql-route [_ options]
  (make-bidi-graphql-route options))

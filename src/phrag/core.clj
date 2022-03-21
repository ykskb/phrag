(ns phrag.core
  (:require [phrag.route :as rt]
            [phrag.table :as tbl]
            [phrag.context :as ctx]
            [integrant.core :as ig]))

(defn options->config [options]
  (let [config {:router (:router options)
                :db (:db options)
                :tables (:tables options)
                :signals (:signals options)
                :signal-ctx (:signal-ctx options)
                :middleware (:middleware options)
                :scan-schema (:scan-schema options true)
                :default-limit (:default-limit options)
                :no-fk-on-db (:no-fk-on-db options false)
                :plural-table-name (:plural-table-name options true)
                :use-aggregation (:use-aggregation options true)}
        db-scm (tbl/db-schema config)]
    (-> config
        (assoc :tables (ctx/schema-context db-scm))
        (assoc :sl-config (ctx/sl-config db-scm (:db options))))))

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

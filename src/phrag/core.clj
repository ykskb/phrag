(ns phrag.core
  (:require [clojure.string :as s]
            [duct.core :as core]
            [phrag.route :as rt]
            [phrag.table :as tbl]
            [integrant.core :as ig]
            [clojure.pprint :as pp]))

(defn- get-ig-db [config db-ig-key db-keys]
  (ig/load-namespaces config)
  (let [init-config (ig/init config [db-ig-key])
        db (or (db-ig-key init-config)
               (second (first (ig/find-derived init-config db-ig-key))))]
    (get-in db db-keys)))

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

(defn make-reitit-graphql [options]
  (let [db (or (:db options) nil)
        config (options->config (-> options
                                    (assoc :router :reitit)
                                    (assoc :db db)))]
  (rt/graphql-route config)))

(defmethod ig/init-key ::reitit-graphql [_ options]
  (make-reitit-graphql options))

;;; bidi

(defn make-bidi-graphql [options]
  (let [db (or (:db options) nil)
        config (options->config (-> options
                                    (assoc :router :bidi)
                                    (assoc :db db)))]
    ;; TODO: add graphql route for bidi
    ["" (apply merge (:routes {}))]))

(defmethod ig/init-key ::bidi-graphql [_ options]
  (make-bidi-graphql options))

;;; Duct Ataraxy

(defn- duct-project-ns [config options]
  (:project-ns options (:duct.core/project-ns config)))

(defn merge-duct-graphql [duct-config routes]
  (let [flat-routes (apply merge (:routes routes))
        route-config {:duct.router/ataraxy {:routes flat-routes}}
        handler-config (apply merge (:handlers routes))]
    (-> duct-config
        (core/merge-configs route-config)
        (core/merge-configs handler-config))))

(defmethod ig/init-key ::duct-graphql [_ options]
  (fn [config]
    (let [project-ns (duct-project-ns config options)
          db-ig-key (:db-ig-key options :duct.database/sql)
          db-keys (if (contains? options :db-keys) (:db-keys options) [:spec])
          db-ref (or (:db-ref options) (ig/ref db-ig-key))
          db (or (:db options) (get-ig-db config db-ig-key db-keys))
          gql-config (options->config (-> options
                                      (assoc :router :ataraxy)
                                      (assoc :project-ns project-ns)
                                      (assoc :db-keys db-keys)
                                      (assoc :db-ref db-ref)
                                      (assoc :db db)))]
      ;; TODO: add graphql route
      (merge-duct-graphql config {}))))

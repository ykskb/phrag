(ns duct-rest.module.sql
  (:require [clojure.string :as s]
            [duct.core :as core]
            [duct-rest.boundary.db.core :as db]
            [integrant.core :as ig]
            [clojure.pprint :as pp]))

(defn get-db-schema [db]
  (let [tables (map :name (db/get-table-names db))]
    (map (fn [table-name]
           {:name table-name
            :columns (db/get-columns db table-name)
            :fks (db/get-fks db table-name)})
         tables)))

(defn resource-handler-key [project-ns action]
  (let [ns (str project-ns ".handler.sql")]
    (keyword ns action)))

(defn resource-route-key [project-ns resource action]
  (let [ns (str project-ns ".handler.sql." resource)]
    (keyword ns action)))

(defn handler-map [handler-key route-key opts]
    (derive route-key handler-key)
    {[handler-key route-key] opts})

(defn make-root-config [table ns db db-ref]
  (let [rsc (:name table)]
    (if-not (s/includes? rsc "_") ; exclude n-n tables
      (map (fn [[action method]]
             (let [route-key (resource-route-key ns rsc action)
                   handler-key (resource-handler-key ns action)]
               (list {[method (str "/" rsc)] ; todo: support qs
                      [route-key]}
                     (handler-map handler-key route-key
                                  {:db db-ref :rsc rsc}))))
           [["list" :get], ["create" :post]]))))

(defn make-1-n-config [table ns db db-ref]
  nil)

(defn make-n-n-config [table ns db db-ref]
  nil)

(defn make-rest-config
  "Makes handler config and routes from DB. Each config is a list with 
  2 elements of a handler config map and a route map."
  ([ns db db-ref]
   (make-rest-config ns db db-ref (get-db-schema db)))
  ([ns db db-ref db-schema]
   (apply map list
          (mapcat (fn [table]
                    (concat (make-root-config table ns db db-ref)
                            (make-1-n-config table ns db db-ref)
                            (make-n-n-config table ns db db-ref)))
                  db-schema))))

(defn- get-project-ns [config options]
  (:project-ns options (:duct.core/project-ns config)))

(defn- get-db
  "Builds database config and returns database. Required as
  ig/ref to :duct.database/sql is only built in :duct/profile
  namespace."
  [config conf-db-key db-key]
   (ig/load-namespaces config)
   (db-key (ig/init config [conf-db-key])))

(defn- merge-rest-config [config rest-config]
  (let [route-config {:duct.router/ataraxy
                      {:routes (apply merge (first rest-config))}}
        handler-config (apply merge (second rest-config))]
    (core/merge-configs config (merge route-config handler-config))))

(defmethod ig/init-key ::register [_ options]
  (fn [config]
    (let [project-ns (get-project-ns config options)
          config-db-key (:config-db-key options :duct.database/sql)
          db-key (:db-key options :duct.database.sql/hikaricp)
          db-ref (ig/ref config-db-key)
          db (get-db config config-db-key db-key)
          rest-config (make-rest-config project-ns db db-ref)]
      (merge-rest-config config rest-config))))

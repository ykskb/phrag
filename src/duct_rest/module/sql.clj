(ns duct-rest.module.sql
  (:require [duct.core :as core]
            [duct-rest.boundary.db.core :as db]
            [integrant.core :as ig]
            [clojure.pprint :as pp]))

;; DB operations

;; Config functions

(defn get-db-schema [db]
  (println "get-db-schema" db)
  (let [tables (map :name (db/get-table-names db))]
    (map (fn [table-name]
           (list (keyword table-name)
            {:columns (db/get-columns db table-name)
             :fks (db/get-fks db table-name)}))
         tables)))

(defn resource-handler-key [project-ns resource action]
  (let [ns (str project-ns ".handler." resource)]
    (keyword ns action)))

(defn handler-map [project-ns db]
  (let [handler {[:duct-rest.handler.sql/list
                  :duct-rest.handler.sql/dynamic]
                 {:db db}}] ; (ig/ref :duct.database/sql)}}]
    (derive :auto-test.handler.example/example-test
            :auto-test.handler.example/list)
    (println handler)
    handler))

;(defn rest-config [project-ns db]
;  (let [db-schema (get-db-schema db)]
;    (

(defn- get-project-ns [config options]
  (:project-ns options (:duct.core/project-ns config)))

(defn- get-db
  ([]
   (get-db nil))
  ([options]
   (:db options (ig/ref :duct.database/sql))))

;; Key implementations

(defmethod ig/init-key :db-rest.module/register
  [_ {:keys [db project-ns]}]
  #(let [rest-config (handler-map project-ns)]
;         db-schema (get-db-schema db)]
     (println "module db")
     (println db)
;     (println "sche")
;     (println db-schema)
     (core/merge-configs % rest-config)))

;(defmethod ig/init-key :duct.module/db-rest [_ options]
;  #(let [project-ns (get-project-ns % options)]
;     core/merge-configs % options {:db-rest.handler/register
;                                   {:db (ig/ref :duct.database/sql)
;                                    :project-ns project-ns}}))

;(defmethod ig/prep-key :auto-rest.handler/example [_ options]
;  (println "fdsa" options)
;  (if (:db options)
;    options
;    (assoc options :db (ig/ref :duct.database.sql))))

;(defmethod ig/prep-key ::register [_ opts]
;  (println "prepin")
;  (println opts)
;  (if (:db opts)
;    opts
;    (assoc opts :db (ig/ref :duct.database/sql))))

(defmethod ig/init-key ::register [_ options]
  #(let [project-ns (get-project-ns % options)
         db (get-db options)
         rest-config (handler-map project-ns db)]
     (println %)
     (println "built" project-ns)
     (println "db" db)
     (core/merge-configs % rest-config)))

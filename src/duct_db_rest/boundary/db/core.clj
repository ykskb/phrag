(ns duct-db-rest.boundary.db.core
  (:require [clojure.java.jdbc :as jdbc]))

;; todo: query handling to be improved with proper formatting

(defn get-table-names [{db :spec}]
  (jdbc/query db (str "select name from sqlite_master "
                      "where type = 'table' "
                      "and name not like 'sqlite%' "
                      "and name not like '%migration%';")))

(defn get-columns [{db :spec} table]
  (jdbc/query db (str "pragma table_info(" table ");")))

(defn get-fks [{db :spec} table]
  (jdbc/query db (str "pragma foreign_key_list(" table ");")))

(defn list[{db :spec} rsc]
  (jdbc/query db (str "select * from " rsc ";")))

(defn fetch [{db :spec} rsc id]
  (jdbc/query db [(str "select * from " rsc " where id = ?") id]))

(defn delete! [{db :spec} rsc id]
  (jdbc/delete! db rsc ["id = ?" id]))

(defn create! [{db :spec} rsc raw-map]
  (jdbc/insert! db rsc raw-map))

(defn update! [{db :spec} rsc id raw-map]
  (jdbc/update! db rsc raw-map ["id = ?" id]))

(defn get-db-schema [db]
  (let [tables (map :name (get-table-names db))]
    (map (fn [table-name]
           {:name table-name
            :columns (get-columns db table-name)
            :fks (get-fks db table-name)})
         tables)))


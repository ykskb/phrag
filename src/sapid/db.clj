(ns sapid.db
  (:refer-clojure :exclude [group-by update])
  (:require [clojure.core :as c]
            [clojure.java.jdbc :as jdbc]
            [honey.sql.helpers :refer
             [select update delete-from from where] :as h]
            [honey.sql :as sql]))

;; todo: query handling to be improved with proper formatting

;; Schema queries

(defn get-table-names [db]
  (jdbc/query db (str "select name from sqlite_master "
                      "where type = 'table' "
                      "and name not like 'sqlite%' "
                      "and name not like '%migration%';")))

(defn get-columns [db table]
  (jdbc/query db (str "pragma table_info(" table ");")))

(defn get-fks [db table]
  (jdbc/query db (str "pragma foreign_key_list(" table ");")))

(defn get-db-schema [db]
  (let [tables (map :name (get-table-names db))]
    (map (fn [table-name]
           {:name table-name
            :columns (get-columns db table-name)
            :fks (get-fks db table-name)})
         tables)))

;; Resource queries

(defn list-up [db rsc & [filters]]
  (println filters (not-empty filters))
  (let [q (-> (select :*) (from (keyword rsc)))
        q (if (not-empty filters) (apply where q filters) q)]
    (println (sql/format q))
    (->> (sql/format q)
         (jdbc/query db))))

(defn fetch [db rsc id & [filters]]
  (let [q (-> (select :*) (from (keyword rsc)))
        q (if (empty? filters) (where q [[:= :id id]])
              (apply where q (conj filters [:= :id id])))]
    (->> (sql/format q)
         (jdbc/query db)
         first)))

(defn delete! [db rsc id & [p-col p-id]]
  (let [filters (if (nil? p-id) [[:= :id id]]
                    [[:= :id id] [:= (keyword p-col) p-id]])
        q (apply where (delete-from (keyword rsc)) filters)]
    (->> (sql/format q)
         (jdbc/execute! db))))

(defn delete-where! [db rsc filters]
  (let [q (apply where (delete-from (keyword rsc)) filters)]
    (->> (sql/format q)
         (jdbc/execute! db))))

(defn create! [db rsc raw-map]
  (jdbc/insert! db rsc raw-map))

(defn update! [db rsc id raw-map & [p-col p-id]]
  (let [filters (if (nil? p-id) [[:= :id id]]
                    [[:= :id id] [:= (keyword p-col) p-id]])
        q (-> (h/update (keyword rsc)) (h/set raw-map))]
    (->> (apply where q filters)
         sql/format
         (jdbc/execute! db))))


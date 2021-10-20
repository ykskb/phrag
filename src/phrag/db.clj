(ns phrag.db
  (:refer-clojure :exclude [group-by update])
  (:require [clojure.core :as c]
            [clojure.java.jdbc :as jdbc]
            ;; [next.jdbc :as jdbc]
            [honey.sql.helpers :refer
             [select update delete-from from where join order-by
              limit offset] :as h]
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
  (let [whr (:where filters)
        o-col (:order-col filters)
        q (-> (select :*) (from (keyword rsc))
              (limit (:limit filters 100)) (offset (:offset filters 0)))
        q (if (not-empty whr) (apply where q whr) q)
        q (if (some? o-col) (order-by q [o-col (:direc filters)]) q)]
    ;;(println (sql/format q))
    (->> (sql/format q)
         (jdbc/query db))))

(defn list-through [db rsc nn-table nn-join-col & [filters]]
  (let [nn-col-key (keyword (str "nn." nn-join-col))
        whr (:where filters)
        o-col (:order-col filters)
        q (-> (select :t.* :nn.*) (from [(keyword nn-table) :nn])
              (join [(keyword rsc) :t] [:= nn-col-key :t.id])
              (limit (:limit filters 100)) (offset (:offset filters 0)))
        q (if (not-empty whr) (apply where q whr) q)
        q (if (some? o-col) (order-by q [o-col (:direc filters)]) q)]
    ;;(println (sql/format q))
    (->> (sql/format q)
         (jdbc/query db))))

(defn fetch [db rsc id & [filters]]
  (let [whr (:where filters)
        q (-> (select :*) (from (keyword rsc)))
        q (if (empty? whr) (where q [[:= :id id]])
              (apply where q (conj whr [:= :id id])))]
    ;;(println (sql/format q))
    (->> (sql/format q)
         (jdbc/query db)
         first)))

(defn delete! [db rsc id & [p-col p-id]]
  (let [whr (if (nil? p-id) [[:= :id id]]
                    [[:= :id id] [:= (keyword p-col) p-id]])
        q (apply where (delete-from (keyword rsc)) whr)]
    (->> (sql/format q)
         (jdbc/execute! db))))

(defn delete-where! [db rsc filters]
  (let [whr (:where filters)
        q (apply where (delete-from (keyword rsc)) whr)]
    (->> (sql/format q)
         (jdbc/execute! db))))

(defn create! [db rsc raw-map]
  (jdbc/insert! db rsc raw-map {:return-keys ["id"]}))

(defn update! [db rsc id raw-map & [p-col p-id]]
  (let [whr (if (nil? p-id) [[:= :id id]]
                    [[:= :id id] [:= (keyword p-col) p-id]])
        q (-> (h/update (keyword rsc)) (h/set raw-map))]
    (->> (apply where q whr)
         sql/format
         (jdbc/execute! db))))


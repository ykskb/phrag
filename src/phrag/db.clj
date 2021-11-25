(ns phrag.db
  (:refer-clojure :exclude [group-by update])
  (:require [clojure.core :as c]
            [clojure.java.jdbc :as jdbc]
            [honey.sql.helpers :refer
             [select update delete-from from where join order-by
              limit offset group-by] :as h]
            [honey.sql :as sql]))

;; Schema queries

(defn- db-type [db-spec]
  (-> (.getMetaData (:connection db-spec))
      (.getDatabaseProductName)))

(defmulti table-names (fn [db & _] (db-type db)))
(defmulti column-info (fn [db & _] (db-type db)))
(defmulti foreign-keys (fn [db & _] (db-type db)))
(defmulti primary-keys (fn [db & _] (db-type db)))

(defmethod table-names "SQLite" [db]
  (jdbc/query db (str "SELECT name FROM sqlite_master "
                      "WHERE type = 'table' "
                      "AND name NOT LIKE 'sqlite%' "
                      "AND name NOT LIKE '%migration%';")))

(defmethod column-info "SQLite" [db table]
  (jdbc/query db (str "pragma table_info(" table ");")))

(defmethod foreign-keys "SQLite" [db table]
  (jdbc/query db (str "pragma foreign_key_list(" table ");")))

(defmethod primary-keys "SQLite" [db table]
  (reduce (fn [v col]
            (if (> (:pk col) 0) (conj v col) v))
          [] (column-info db table)))

(defmethod table-names "PostgreSQL" [db]
  (jdbc/query db (str "SELECT table_name AS name "
                      "FROM information_schema.tables "
                      "WHERE table_schema='public' "
                      "AND table_type='BASE TABLE' "
                      "AND table_name not like '%migration%';")))

(defmethod column-info "PostgreSQL" [db table]
  (jdbc/query db (str "SELECT column_name AS name, data_type AS type, "
                      "(is_nullable = 'NO') AS notnull, "
                      "column_default AS dflt_value "
                      "FROM information_schema.columns "
                      "WHERE table_name = '" table "';")))

(defmethod foreign-keys "PostgreSQL" [db table]
  (jdbc/query db (str "SELECT kcu.column_name as from, ccu.table_name AS table, "
                      "ccu.column_name AS to "
                      "FROM information_schema.table_constraints as tc "
                      "JOIN information_schema.key_column_usage AS kcu "
                      "ON tc.constraint_name = kcu.constraint_name "
                      "AND tc.table_schema = kcu.table_schema "
                      "JOIN information_schema.constraint_column_usage AS ccu "
                      "ON ccu.constraint_name = tc.constraint_name "
                      "AND ccu.table_schema = tc.table_schema "
                      "WHERE tc.constraint_type = 'FOREIGN KEY' "
                      "AND tc.table_name='" table "';")))

(defmethod primary-keys "PostgreSQL" [db table]
  (jdbc/query db (str "SELECT c.column_name AS name, c.data_type AS type "
                      "FROM information_schema.table_constraints tc "
                      "JOIN information_schema.constraint_column_usage AS ccu "
                      "USING (constraint_schema, constraint_name) "
                      "JOIN information_schema.columns AS c ON c.table_schema = tc.constraint_schema "
                      "AND tc.table_name = c.table_name AND ccu.column_name = c.column_name "
                      "WHERE constraint_type = 'PRIMARY KEY' and tc.table_name = '" table "';")))

(defn schema [db]
  (map (fn [table-name]
         {:name table-name
          :columns (column-info db table-name)
          :fks (foreign-keys db table-name)
          :pks (primary-keys db table-name)})
       (map :name (table-names db))))

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

(defn aggregate [db rsc aggrs & [filters]]
  (let [whr (:where filters)
        q (-> (apply select aggrs) (from (keyword rsc))
              (limit (:limit filters 100)) (offset (:offset filters 0)))
        q (if (not-empty whr) (apply where q whr) q)]
    (println (sql/format q))
    (->> (sql/format q)
         (jdbc/query db))))

(defn aggregate-grp-by [db rsc aggrs grp-by & [filters]]
  (let [whr (:where filters)
        q (-> (apply select aggrs) (select grp-by)
              (from (keyword rsc)) (group-by grp-by))
        q (if (not-empty whr) (apply where q whr) q)]
    (println (sql/format q))
    (->> (sql/format q)
         (jdbc/query db))))

(defn delete! [db rsc id & [p-col p-id]]
  (let [whr (if (nil? p-id)
              [[:= :id id]]
              [[:= :id id] [:= (keyword p-col) p-id]])
        q (apply where (delete-from (keyword rsc)) whr)]
    (->> (sql/format q)
         (jdbc/execute! db))))

(defn delete-where! [db rsc filters]
  (let [whr (:where filters)
        q (apply where (delete-from (keyword rsc)) whr)]
    (->> (sql/format q)
         (jdbc/execute! db))))

(defn create! [db rsc raw-map opts]
  (jdbc/insert! db rsc raw-map opts))

(defn update! [db rsc id raw-map & [p-col p-id]]
  (let [whr (if (nil? p-id) [[:= :id id]]
                    [[:= :id id] [:= (keyword p-col) p-id]])
        q (-> (h/update (keyword rsc)) (h/set raw-map))]
    (->> (apply where q whr)
         sql/format
         (jdbc/execute! db))))


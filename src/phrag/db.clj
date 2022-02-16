(ns phrag.db
  (:refer-clojure :exclude [group-by update])
  (:require [clojure.core :as c]
            [clojure.java.jdbc :as jdbc]
            [honey.sql.helpers :as h]
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

(defn list-up [db table & [params]]
  (let [whr (:where params)
        selects (:select params [:*])
        o-col (:order-col params)
        q (-> (apply h/select selects)
              (h/from table)
              (h/limit (:limit params 100))
              (h/offset (:offset params 0)))
        q (if (not-empty whr) (apply h/where q whr) q)
        q (if (some? o-col) (h/order-by q [o-col (:direc params)]) q)]
    (println (sql/format q))
    (->> (sql/format q)
         (jdbc/query db))))

(defn list-partitioned [db table part-col-key order-col order-direc & [params]]
  (let [whr (:where params)
        sub-selects [:* (h/over [[:raw "row_number()"]
                                 (-> (h/partition-by part-col-key)
                                     (h/order-by [order-col order-direc]))
                                 :p_id])]
        sub-q (-> (apply h/select sub-selects)
                  (h/from table))
        sub-q (if (not-empty whr) (apply h/where sub-q whr) sub-q)
        pid-gt (:offset params 0)
        pid-lte (+ pid-gt (:limit params 100))
        q (-> (h/select :*)
              (h/from [sub-q :sub])
              (h/where [:> :p_id pid-gt])
              (h/where [:<= :p_id pid-lte]))]
    (println (sql/format q))
    (->> (sql/format q)
         (jdbc/query db))))

(defn aggregate [db table aggrs & [params]]
  (let [whr (:where params)
        q (-> (apply h/select aggrs)
              (h/from table)
              (h/limit (:limit params 100))
              (h/offset (:offset params 0)))
        q (if (not-empty whr) (apply h/where q whr) q)]
    ;;(println (sql/format q))
    (->> (sql/format q)
         (jdbc/query db))))

(defn aggregate-grp-by [db table aggrs grp-by & [params]]
  (let [whr (:where params)
        q (-> (apply h/select aggrs)
              (h/select grp-by)
              (h/from table)
              (h/group-by grp-by))
        q (if (not-empty whr) (apply h/where q whr) q)]
    ;;(println (sql/format q))
    (->> (sql/format q)
         (jdbc/query db))))

(defn delete! [db table pk-map]
  (let [whr (map (fn [[k v]] [:= k v]) pk-map)
        q (apply h/where (h/delete-from table) whr)]
    (prn (sql/format q))
    (->> (sql/format q)
         (jdbc/execute! db))))

(defn create! [db rsc raw-map opts]
  (prn db rsc raw-map)
  (jdbc/insert! db rsc raw-map opts))

(defn update! [db table pk-map raw-map]
  (let [whr (map (fn [[k v]] [:= k v]) pk-map)
        q (-> (h/update table)
              (h/set raw-map))]
    (prn (sql/format (apply h/where q whr)))
    (->> (apply h/where q whr)
         sql/format
         (jdbc/execute! db))))


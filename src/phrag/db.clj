(ns phrag.db
  "DB operations for DB schema retrieval and GraphQL operations."
  (:refer-clojure :exclude [group-by update])
  (:require [clojure.core :as c]
            [clojure.string :as s]
            [clojure.java.jdbc :as jdbc]
            [honey.sql.helpers :as h]
            [honey.sql :as sql]))

;; Schema queries

(defn- connection-db-type [db]
  (let [db-info (s/lower-case (-> (.getMetaData (:connection db))
                                  (.getDatabaseProductName)))]
    (cond
      (s/includes? db-info "sqlite") :sqlite
      (s/includes? db-info "postgres") :postgres
      :else nil)))

(defn- data-src-db-type [db]
  (let [data-src (:datasource db)
        db-info (s/lower-case (or (.getJdbcUrl data-src)
                                  (.getDataSourceClassName data-src)))]
    (cond
      (s/includes? db-info "sqlite") :sqlite
      (s/includes? db-info "postgres") :postgres
      :else nil)))

(defn- db-type [db]
  (cond
    (:connection db) (connection-db-type db)
    (:datasource db) (data-src-db-type db)
    :else nil))

(defmulti table-names (fn [db & _] (db-type db)))
(defmulti column-info (fn [db & _] (db-type db)))
(defmulti foreign-keys (fn [db & _] (db-type db)))
(defmulti primary-keys (fn [db & _] (db-type db)))

(defmethod table-names :sqlite [db]
  (jdbc/with-db-connection [conn db]
    (jdbc/query conn (str "SELECT name FROM sqlite_master "
                          "WHERE type = 'table' "
                          "AND name NOT LIKE 'sqlite%' "
                          "AND name NOT LIKE '%migration%';"))))

(defmethod column-info :sqlite [db table]
  (jdbc/with-db-connection [conn db]
    (jdbc/query conn (str "pragma table_info(" table ");"))))

(defmethod foreign-keys :sqlite [db table]
  (jdbc/with-db-connection [conn db]
    (jdbc/query conn (str "pragma foreign_key_list(" table ");"))))

(defmethod primary-keys :sqlite [db table]
  (reduce (fn [v col]
            (if (> (:pk col) 0) (conj v col) v))
          [] (column-info db table)))

(defmethod table-names :postgres [db]
  (jdbc/with-db-connection [conn db]
    (jdbc/query conn (str "SELECT table_name AS name "
                          "FROM information_schema.tables "
                          "WHERE table_schema='public' "
                          "AND table_type='BASE TABLE' "
                          "AND table_name not like '%migration%';"))))

(defmethod column-info :postgres [db table]
  (jdbc/with-db-connection [conn db]
    (jdbc/query conn (str "SELECT column_name AS name, data_type AS type, "
                          "(is_nullable = 'NO') AS notnull, "
                          "column_default AS dflt_value "
                          "FROM information_schema.columns "
                          "WHERE table_name = '" table "';"))))

(defmethod foreign-keys :postgres [db table]
  (jdbc/with-db-connection [conn db]
    (jdbc/query conn (str "SELECT kcu.column_name AS from, "
                          "ccu.table_name AS table, "
                          "ccu.column_name AS to "
                          "FROM information_schema.table_constraints as tc "
                          "JOIN information_schema.key_column_usage AS kcu "
                          "ON tc.constraint_name = kcu.constraint_name "
                          "AND tc.table_schema = kcu.table_schema "
                          "JOIN information_schema.constraint_column_usage AS ccu "
                          "ON ccu.constraint_name = tc.constraint_name "
                          "AND ccu.table_schema = tc.table_schema "
                          "WHERE tc.constraint_type = 'FOREIGN KEY' "
                          "AND tc.table_name='" table "';"))))

(defmethod primary-keys :postgres [db table]
  (jdbc/with-db-connection [conn db]
    (jdbc/query conn (str "SELECT c.column_name AS name, c.data_type AS type "
                          "FROM information_schema.table_constraints tc "
                          "JOIN information_schema.constraint_column_usage AS ccu "
                          "USING (constraint_schema, constraint_name) "
                          "JOIN information_schema.columns AS c "
                          "ON c.table_schema = tc.constraint_schema "
                          "AND tc.table_name = c.table_name "
                          "AND ccu.column_name = c.column_name "
                          "WHERE constraint_type = 'PRIMARY KEY' "
                          "AND tc.table_name = '" table "';"))))

(defn schema
  "Queries DB schema including primary keys and foreign keys."
  [db]
  (map (fn [table-name]
         {:name table-name
          :columns (column-info db table-name)
          :fks (foreign-keys db table-name)
          :pks (primary-keys db table-name)})
       (map :name (table-names db))))

;; Resource queries

(defn list-up
  "Executes a select statement from a table with provided parameters."
  [db table params]
  (let [whr (:where params)
        selects (:select params [:*])
        sorts (:sort params)
        q (cond-> (apply h/select selects)
            true (h/from table)
            (:limit params) (h/limit (:limit params))
            (:offset params) (h/offset (:offset params)))
        q (if (not-empty whr) (apply h/where q whr) q)
        q (if (not-empty sorts) (apply h/order-by q sorts) q)]
    ;; (println (sql/format q))
    ;; (prn db)
    (jdbc/with-db-connection [conn db]
      (->> (sql/format q)
           (jdbc/query conn)))))

(defn list-partitioned
  "Executes select statements with partitions by a column."
  [db table part-col-key params]
  (let [whr (:where params)
        selects (:select params [:*])
        sub-part (apply h/order-by (h/partition-by part-col-key) (:sort params))
        sub-selects (conj selects (h/over [[:raw "row_number()"] sub-part :p_id]))
        sub-q (-> (apply h/select sub-selects)
                  (h/from table))
        sub-q (if (not-empty whr) (apply h/where sub-q whr) sub-q)
        pid-gt (:offset params 0)
        pid-lte (+ pid-gt (:limit params 100))
        q (cond-> (apply h/select selects)
              true (h/from [sub-q :sub])
              (:offset params) (h/where [:> :p_id pid-gt])
              (:limit params) (h/where [:<= :p_id pid-lte]))]
    ;; (println (sql/format q))
    (jdbc/with-db-connection [conn db]
      (->> (sql/format q)
           (jdbc/query conn)))))

(defn aggregate
  "Executes aggregation query with provided paramters."
  [db table aggrs & [params]]
  (let [whr (:where params)
        q (cond-> (apply h/select aggrs)
            true (h/from table)
            (:limit params) (h/limit (:limit params))
            (:offset params) (h/offset (:offset params)))
        q (if (not-empty whr) (apply h/where q whr) q)]
    ;;(println (sql/format q))
    (jdbc/with-db-connection [conn db]
      (->> (sql/format q)
           (jdbc/query conn)))))

(defn aggregate-grp-by
  "Executes aggregation with a single group-by clause."
  [db table aggrs grp-by & [params]]
  (let [whr (:where params)
        q (-> (apply h/select aggrs)
              (h/select grp-by)
              (h/from table)
              (h/group-by grp-by))
        q (if (not-empty whr) (apply h/where q whr) q)]
    ;;(println (sql/format q))
    (jdbc/with-db-connection [conn db]
      (->> (sql/format q)
           (jdbc/query conn)))))

(defn create!
  "Executes create statement with parameter map."
  [db rsc raw-map opts]
  ;; (prn rsc raw-map)
  (jdbc/with-db-connection [conn db]
    (jdbc/insert! conn rsc raw-map opts)))

(defn update!
  "Executes update statement with primary key map and parameter map."
  [db table pk-map raw-map]
  (let [whr (map (fn [[k v]] [:= k v]) pk-map)
        q (-> (h/update table)
              (h/set raw-map))]
    ;; (prn (sql/format (apply h/where q whr)))
    (jdbc/with-db-connection [conn db]
      (->> (apply h/where q whr)
           sql/format
           (jdbc/execute! conn)))))

(defn delete!
  "Executes delete statement with primary key map."
  [db table pk-map]
  (let [whr (map (fn [[k v]] [:= k v]) pk-map)
        q (apply h/where (h/delete-from table) whr)]
    ;; (prn (sql/format q))
    (jdbc/with-db-connection [conn db]
      (->> (sql/format q)
           (jdbc/execute! conn)))))

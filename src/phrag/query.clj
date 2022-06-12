(ns phrag.query
  "DB operations for DB schema retrieval and GraphQL operations."
  (:refer-clojure :exclude [group-by update])
  (:require [clojure.core :as c]
            [clojure.string :as s]
            [clojure.java.jdbc :as jdbc]
            [honey.sql.helpers :as h]
            [honey.sql :as sql]
            [phrag.db.core :as db]))

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
        q (cond-> (apply h/select selects)
              true (h/from [sub-q :sub])
              (:offset params) (h/where [:> :p_id pid-gt])
              (:limit params) (h/where [:<= :p_id (+ pid-gt (:limit params))]))]
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
    ;; (println (sql/format q))
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
    ;; (println (sql/format q))
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

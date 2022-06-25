(ns phrag.table
  "Table data handling for Phrag's GraphQL."
  (:require [clojure.string :as s]
            [clojure.pprint :as pp]
            [phrag.db.core :as db]
            [phrag.logging :refer [log]]
            [inflections.core :as inf]))

;; Table utils

(defn col-key-set
  "Returns a set of column keywords from a table map."
  [table]
  (set (map #(keyword (:name %)) (:columns table))))

(defn pk-keys
  "Returns a list of PK keywords from a table map"
  [table]
  (let [pk-names (map :name (:pks table))]
    (map keyword pk-names)))

(defn circular-m2m-fk?
  "Bridge tables of circular many-to-many have 2 columns linked to the
  same table. Example: `user_follow` table where following and the followed
  are both linked to `users` table."
  [table fk-from]
  (let [fk-tbls (map :table (:fks table))
        cycl-linked-tbls (set (for [[tbl freq] (frequencies fk-tbls)
                                    :when (> freq 1)] tbl))
        cycl-link-fks (filter #(contains? cycl-linked-tbls (:table %))
                              (:fks table))]
    (contains? (set (map :from cycl-link-fks)) fk-from)))

;;; Table schema map from config

(defn- table-schema
  "Queries table schema including primary keys and foreign keys."
  [adapter]
  (map (fn [table-name]
         {:name table-name
          :columns (db/column-info adapter table-name)
          :fks (db/foreign-keys adapter table-name)
          :pks (db/primary-keys adapter table-name)})
       (map :name (db/table-names adapter))))

(defn- view-schema
  "Queries views with columns."
  [adapter]
  (map (fn [view-name]
         {:name view-name
          :columns (db/column-info adapter view-name)})
       (map :name (db/view-names adapter))))

(defn- merge-config-tables [tables config]
  (let [cfg-tables (:tables config)
        cfg-tbl-names (map :name cfg-tables)
        tbl-names (map :name tables)
        tbl-name-set (set tbl-names)
        cfg-tbl-map (zipmap cfg-tbl-names cfg-tables)
        merged (map (fn [table]
                      (merge table (get cfg-tbl-map (:name table))))
                    tables)
        cfg-tbl-diff (filter (fn [table]
                               (not (contains? tbl-name-set (:name table))))
                             cfg-tables)]
    (concat merged cfg-tbl-diff)))

(defn- validate-tables [tables]
  (reduce (fn [v table]
            (if (or (< (count (:columns table)) 1)
                    (< (count (:pks table)) 1))
              (do (log :warn "No column or primary key for table:" (:name table))
                  v)
              (conj v table)))
          [] tables))

(defn db-schema
  "Conditionally retrieves DB schema data from a DB connection and merge table
  data provided into config if there's any."
  [config]
  (let [tables (cond-> (if (:scan-tables config)
                         (table-schema (:db-adapter config))
                         (:tables config))
                 (:scan-tables config) (merge-config-tables config)
                 true (validate-tables))
        views (if (:scan-views config)
                (view-schema (:db-adapter config))
                nil)]
    (log :debug "Origin DB table schema:\n"
         (with-out-str (pp/pprint tables)))
    (log :debug "Origin DB view schema:\n"
         (with-out-str (pp/pprint views)))
    {:tables tables
     :views views}))

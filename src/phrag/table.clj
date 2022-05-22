(ns phrag.table
  "Table data handling for Phrag's GraphQL."
  (:require [clojure.string :as s]
            [clojure.pprint :as pp]
            [phrag.db :as db]
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

(defn is-circular-m2m-fk?
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

;;; Optional foreign key detection from table/column names

(defn- to-table-name [rsc config]
  (if (:plural-table-name config) (inf/plural rsc) (inf/singular rsc)))

(defn- fks-by-names [table config]
  (reduce (fn [v column]
            (let [col-name (:name column)]
              (if (s/ends-with? (s/lower-case col-name) "_id")
                (conj v
                      {:table (to-table-name (s/replace col-name "_id" "")
                                             config)
                       :from col-name :to "id"})
                v)))
          []
          (:columns table)))

(defn- update-fks-by-names [tables config]
  (map (fn [table]
         (assoc table :fks (fks-by-names table config)))
       tables))

;;; Table schema map from config

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
                         (db/table-schema (:db config))
                         (:tables config))
                 (:scan-tables config) (merge-config-tables config)
                 (:no-fk-on-db config) (update-fks-by-names config)
                 true (validate-tables))
        views (if (:scan-views config)
                (db/view-schema (:db config))
                nil)]
    (log :debug "Origin DB table schema:\n"
         (with-out-str (pp/pprint tables)))
    (log :debug "Origin DB view schema:\n"
         (with-out-str (pp/pprint views)))
    {:tables tables
     :views views}))

(ns phrag.table
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

(defn primary-fks
  "Returns a list of FK maps that are also primary keys."
  [table]
  (let [fks (:fks table)
        pk-names (map :name (:pks table))
        fk-map (zipmap (map :from fks) fks)]
    (vals (select-keys fk-map pk-names))))

(defn is-circular-m2m-fk?
  "Bridge tables of circular many-to-many have 2 columns linked to the
  same table. Example: `user_follow` table where following and the followed
  are both linked to `users` table."
  [table fk-from]
  (let [p-fks (primary-fks table)
        p-fk-tbls (map :table p-fks)
        cycl-linked-tbls (set (for [[tbl freq] (frequencies p-fk-tbls)
                                    :when (> freq 1)] tbl))
        cycl-link-fks (filter #(contains? cycl-linked-tbls (:table %)) p-fks)]
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

(defn db-schema
  "Conditionally retrieves DB schema data from a DB connection and merge table
  data provided into config if there's any."
  [config]
  (let [scm (if (:scan-schema config)
              (cond-> (db/schema (:db config))
                true (merge-config-tables config)
                (:no-fk-on-db config) (update-fks-by-names config))
              (cond-> (:tables config)
                (:no-fk-on-db config) (update-fks-by-names config)))]
    (log :debug "Origin DB schema:\n"
         (with-out-str (pp/pprint scm)))
    scm))

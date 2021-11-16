(ns phrag.table
  (:require [clojure.string :as s]
            [clojure.pprint :as pp]
            [phrag.db :as db]
            [phrag.logging :refer [log]]
            [inflections.core :as inf]))

;; Table utils

(defn col-names [table]
  (set (map :name (:columns table))))

(defn primary-fks [table]
  (vals (select-keys (:fk-map table) (keys (:pk-map table)))))

(defn is-circular-m2m-fk?
  "Circular many-to-many links records on the same table.
  Example: `user_follow` table where followers and the followed are both
  linked to `users` table."
  [table fk-from]
  (let [p-fks (primary-fks table)
        p-fk-tbls (map :table p-fks)
        cycl-linked-tbls (set (for [[tbl freq] (frequencies p-fk-tbls)
                                    :when (> freq 1)] tbl))
        cycl-link-fks (filter #(contains? cycl-linked-tbls (:table %)) p-fks)]
    (contains? (set (map :from cycl-link-fks)) fk-from)))

;;; Full relationship map per table including reverse relations

(defn- rels [table]
  (let [table-name (:name table)
        rel-map {table-name (set (map #(:table %) (:fks table)))}]
    (reduce (fn [m fk] (assoc m (:table fk) #{table-name}))
            rel-map (:fks table))))

(defn full-rel-map [config]
  (reduce (fn [m table]
            (merge-with into m (rels table)))
          {} (:tables config)))

;;; Optional foreign key detection from table/column names

(defn- to-table-name [rsc config]
  (if (:plural-table-name config) (inf/plural rsc) (inf/singular rsc)))

(defn- fks-by-names [table config]
  (reduce (fn [v column]
            (let [col-name (:name column)]
              (if (s/ends-with? (s/lower-case col-name) "_id")
                (conj v
                      {:table (to-table-name (s/replace col-name "_id" "") config)
                       :from col-name :to "id"})
                v)))
          []
          (:columns table)))

(defn- update-fks-by-names [tables config]
  (map (fn [table]
         (let [fks (fks-by-names table config)]
           (-> table
               (assoc :fks fks)
               (assoc :fk-map (zipmap (map :from fks) fks)))))
       tables))

;;; Table schema map from config

(defn- is-pivot-table? [table]
  (and (> (count (:pks table)) 1)
       (let [fk-names (set (keys (:fk-map table)))
             pk-names (keys (:pk-map table))]
         (every? #(contains? fk-names %) pk-names))))

(defn- table-type [table]
  (if (is-pivot-table? table) :pivot :root))

(defn- update-table-types [tables]
  (map #(assoc % :table-type (table-type %)) tables))

(defn- update-info-maps [tables]
  (map (fn [table]
         (let [cols (:columns table)
               fks (:fks table)
               pks (:pks table)]
           (-> table
               (assoc :col-map (zipmap (map :name cols) cols))
               (assoc :fk-map (zipmap (map :from fks) fks))
               (assoc :pk-map (zipmap (map :name pks) pks)))))
       tables))

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

(defn schema-from-db
  "By default foreign keys constraints in a database are used for relationships.
  Alternatively, `:no-fk-on-db` can be used to detect relations by table/column names,
  but it has limitations as tables and columns need to be matching."
  [config]
  (let [scm (if (:scan-schema config)
              (cond-> (db/schema (:db config))
                true (update-info-maps)
                (:no-fk-on-db config) (update-fks-by-names config)
                true (update-table-types)
                true (merge-config-tables config))
              (cond-> (:tables config)
                true (update-info-maps)
                (:no-fk-on-db config) (update-fks-by-names config)))]
    (log :info "Origin DB schema:\n"
         (with-out-str (pp/pprint (map #(-> %
                                            (dissoc :col-map)
                                            (dissoc :fk-map)
                                            (dissoc :pk-map)) scm))))
    scm))


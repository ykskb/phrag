(ns phrag.table
  (:require [clojure.string :as s]
            [clojure.pprint :as pp]
            [phrag.db :as db]
            [phrag.logging :refer [log]]
            [inflections.core :as inf]))

;; Table utils

(defn col-names [table]
  (set (map :name (:columns table))))

(defn col-key-set [table]
  (set (map #(keyword (:name %)) (:columns table))))

(defn primary-fks [table]
  (vals (select-keys (:fk-map table) (keys (:pk-map table)))))

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

(defn has-many-field-key
  "Checks if a given table is a bridge table of cicular many-to-many or not,
  and if it is, adds FK column name to the field key of nested object."
  [table fk]
  (let [tbl-name (:name table)
        rscs (inf/plural tbl-name)
        fk-from (:from fk)]
    (keyword (if (is-circular-m2m-fk? table fk-from)
               (str rscs "_on_" fk-from)
               rscs))))

(defn has-one-field-key
  "If a FK column has `_id` naming, nested objects get field keys with trailing
  `_id` removed. If not, FK destination is added to FK origin column.
  Example: `user_id` => `user` : `created_by` => `created_by_user`"
  [fk]
  (let [from (:from fk)]
    (if (s/ends-with? from "_id")
      (keyword (s/replace from #"_id" ""))
      (keyword (str from "_" (inf/singular (:table fk)))))))

;;; Object field map of relations

(defn- relation-field-names-per-table [table]
  (let [table-name (:name table)
        ;; assoc has-one on FK origin table
        origin-fks (set (map #(has-one-field-key %) (:fks table)))
        has-one-mapped {table-name origin-fks}]
    ;; assoc has-many inverse relation on FK destination tables
    (reduce (fn [m fk]
              (let [has-many-key (has-many-field-key table fk)
                    has-many-aggr-key (keyword (str (name has-many-key) "_aggregate"))]
                (merge-with into m {(:table fk) #{has-many-key has-many-aggr-key}})))
            has-one-mapped (:fks table))))

(defn relation-map [config]
  (reduce (fn [m table]
            (merge-with into m (relation-field-names-per-table table)))
          {} (:tables config)))

(defn relation-name-set [config]
  (let [rel-map (relation-map config)]
    (reduce-kv (fn [s _table rels]
              (into s rels))
            #{} rel-map)))

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

(defn- update-column-maps [tables]
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
                true (update-column-maps)
                (:no-fk-on-db config) (update-fks-by-names config)
                true (merge-config-tables config))
              (cond-> (:tables config)
                true (update-column-maps)
                (:no-fk-on-db config) (update-fks-by-names config)))]
    (log :debug "Origin DB schema:\n"
         (with-out-str (pp/pprint (map #(-> %
                                            (dissoc :col-map)
                                            (dissoc :fk-map)
                                            (dissoc :pk-map)) scm))))
    scm))

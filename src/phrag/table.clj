(ns phrag.table
  (:require [clojure.string :as s]
            [clojure.pprint :as pp]
            [phrag.db :as db]
            [phrag.logging :refer [log]]
            [inflections.core :as inf]))

;; Table utils

(defn table-by-name [name tables]
  (first (filter #(= (:name %) name) tables)))

(defn col-names [table]
  (set (map :name (:columns table))))

(defn col-key-set [table]
  (set (map #(keyword (:name %)) (:columns table))))

(defn pk-keys [table]
  (map #(keyword %) (keys (:pk-map table))))

(defn primary-fks [table]
  (vals (select-keys (:fk-map table) (keys (:pk-map table)))))

(defn- is-circular-m2m-fk?
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

(defn- has-many-field
  "Checks if a given table is a bridge table of cicular many-to-many or not,
  and if it is, adds FK column name to the field key of nested object."
  [table fk]
  (let [tbl-name (:name table)
        rscs (inf/plural tbl-name)
        fk-from (:from fk)]
    (if (is-circular-m2m-fk? table fk-from)
      (str rscs "_on_" fk-from)
      rscs)))

(defn- has-one-field
  "If a FK column has `_id` naming, nested objects get field keys with trailing
  `_id` removed. If not, FK destination is added to FK origin column.
  Example: `user_id` => `user` / `created_by` => `created_by_user`"
  [fk]
  (let [from (:from fk)]
    (if (s/ends-with? from "_id")
      (s/replace from #"_id" "")
      (str from "_" (inf/singular (:table fk))))))

(defn fk-query-keys [table fk]
  (let [has-many-fld (has-many-field table fk)]
    {:has-many {:field-key (keyword has-many-fld)
                :aggregate-key (keyword (str has-many-fld "_aggregate"))}
     :has-one {:field-key (keyword (has-one-field fk))}}))

;;; Object field map of relations

(defn- relation-context-per-table [table]
  (let [;; assoc has-one on FK origin table
        origin-fields (set (map #(keyword (has-one-field %)) (:fks table)))
        origin-columns (set (map #(keyword (:from %)) (:fks table)))
        has-one-mapped {:fields {(:name table) origin-fields}
                        :columns {(:name table) origin-columns}}]
    ;; assoc has-many inverse relation on FK destination tables
    (reduce
     (fn [m fk]
       (let [has-many-key (keyword (has-many-field table fk))
             has-many-aggr-key (keyword (str (name has-many-key) "_aggregate"))
             to-columns (set (map #(keyword (:to %)) (:fks table)))]
         {:fields (merge-with into (:fields m)
                              {(:table fk) #{has-many-key has-many-aggr-key}})
          :columns (merge-with into (:columns m) {(:table fk) to-columns})}))
     has-one-mapped (:fks table))))

(defn relation-context [config]
  (reduce (fn [m table]
            (let [rel-ctx (relation-context-per-table table)]
              {:fields (merge-with into (:fields m) (:fields rel-ctx))
               :columns (merge-with into (:columns m) (:columns rel-ctx))}))
          {:fields {} :columns {}}
          (:tables config)))

(defn relation-name-set [config]
  (let [rel-ctx (relation-context config)]
    (reduce-kv (fn [s _table rels]
                 (into s rels))
               #{} (:fields rel-ctx))))

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

(defn db-schema
  "By default foreign keys constraints in a database are used for relationships.
  Alternatively, `:no-fk-on-db` can be used to detect relations by table/column
  names, but it has limitations as tables and columns need to be matching."
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

(ns phrag.context
  (:require [camel-snake-kebab.core :as csk]
            [clojure.string :as s]
            [clojure.pprint :as pp]
            [inflections.core :as inf]
            [phrag.table :as tbl]
            [phrag.field :as fld]))

;;; Relation Context (field names & columns)

(defn- has-many-field
  "Checks if a given table is a bridge table of cicular many-to-many or not,
  and if it is, adds FK column name to the field key of nested object."
  [table fk]
  (let [tbl-name (:name table)
        rscs (inf/plural tbl-name)
        fk-from (:from fk)]
    (if (tbl/is-circular-m2m-fk? table fk-from)
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

(defn- relation-context-per-table [table]
  (let [fks (:fks table)
        ;; assoc has-one on FK origin table
        origin-fields (set (map #(keyword (has-one-field %)) fks))
        origin-columns (set (map #(keyword (:from %)) fks))
        has-one-mapped {:fields {(:name table) origin-fields}
                        :columns {(:name table) origin-columns}}]
    ;; assoc has-many inverse relation on FK destination tables
    (reduce
     (fn [m fk]
       (let [has-many-key (keyword (has-many-field table fk))
             has-many-aggr-key (keyword (str (name has-many-key) "_aggregate"))
             to-col-key #{(keyword (:to fk))}]
         {:fields (merge-with into (:fields m)
                              {(:table fk) #{has-many-key has-many-aggr-key}})
          :columns (merge-with into (:columns m) {(:table fk) to-col-key})}))
     has-one-mapped
     fks)))

(defn- relation-context [tables]
  (reduce (fn [m table]
            (let [rel-ctx (relation-context-per-table table)]
              {:fields (merge-with into (:fields m) (:fields rel-ctx))
               :columns (merge-with into (:columns m) (:columns rel-ctx))}))
          {:fields {} :columns {}}
          tables))

(defn- relation-name-set [tables]
  (let [rel-ctx (relation-context tables)]
    (reduce-kv (fn [s _table rels]
                 (into s rels))
               #{} (:fields rel-ctx))))

;;; Schema Context

(defn- rsc-names [table-name sgl-or-plr]
  (let [bare (if (= :singular sgl-or-plr)
               (inf/singular table-name)
               (inf/plural table-name))
        pascal (csk/->PascalCase bare)]
    {:bare bare
     :bare-key (keyword bare)
     :pascal pascal
     :pascal-key (keyword pascal)}))

;; FK Context

(defn- fk-field-keys [fk table to-table-name]
  (let [has-many-fld (has-many-field table fk)
        to-rsc-name (csk/->PascalCase (inf/singular to-table-name))]
    {:to (keyword to-rsc-name)
     :has-many (keyword has-many-fld)
     :has-many-aggr (keyword (str has-many-fld "_aggregate"))
     :has-one (keyword (has-one-field fk))}))

(defn- fk-context [from-key fk table table-map rel-ctx]
  (let [to-table-name (:table fk)
        to-table-key (keyword to-table-name)
        fk-to-table (to-table-key table-map)]
    {:from-key from-key
     :to-key (keyword (:to fk))
     :to-tbl-key to-table-key
     :to-tbl-sgl-names (rsc-names to-table-name :singular)
     :to-tbl-col-keys (tbl/col-key-set fk-to-table)
     :field-keys (fk-field-keys fk table to-table-name)
     :to-tbl-rel-cols (get-in rel-ctx [:columns to-table-name])
     :to-tbl-rel-flds (get-in rel-ctx [:fields to-table-name])}))

(defn- fk-ctx-map [table table-map rel-ctx]
  (let [fks (:fks table)
        fk-map (zipmap (map #(keyword (:from %)) fks) fks)]
    (reduce-kv (fn [m k v]
                 (assoc m k (fk-context k v table table-map rel-ctx)))
               {} fk-map)))

;;; Lacinia Schema Context from Table Data

(defn- update-tables [table-map rel-ctx]
  (reduce-kv
   (fn [m k table]
     (let [table-name (:name table)
           obj-keys (fld/lcn-obj-keys table-name)
           pk-keys (tbl/pk-keys table)]
       (assoc m k
              (-> m
                  (assoc :sgl-names (rsc-names table-name :singular))
                  (assoc :plr-names (rsc-names table-name :plural))
                  (assoc :col-keys (tbl/col-key-set table))
                  (assoc :fks (fk-ctx-map table table-map rel-ctx))
                  (assoc :pk-keys pk-keys)
                  (assoc :lcn-obj-keys obj-keys)
                  (assoc :lcn-qry-keys (fld/lcn-qry-keys table-name))
                  (assoc :lcn-mut-keys (fld/lcn-mut-keys table-name))
                  (assoc :lcn-descs (fld/lcn-descs table-name))
                  (assoc :lcn-fields (fld/lcn-fields table obj-keys pk-keys))
                  (assoc :rel-flds (get-in rel-ctx [:fields table-name]))
                  (assoc :rel-cols (get-in rel-ctx [:columns table-name]))))))
   {} table-map))

(defn schema-context [tables]
  (let [table-map (zipmap (map #(keyword (:name %)) tables) tables)
        rel-ctx (relation-context tables)]
    (update-tables table-map rel-ctx)))

(def init-schema {:enums fld/sort-op-enum
                  :input-objects fld/filter-input-objects
                  :objects fld/result-object
                  :queries {}})

(defn sl-config [tables db]
  (let [buckets (reduce (fn [m bucket-name]
                          (assoc m (keyword bucket-name)
                                 {:triggers {:elastic {:threshold 0}}}))
                        {:default {:triggers {:elastic {:threshold 0}}}}
                        (relation-name-set tables))]
    {:buckets buckets
     :urania-opts {:env {:db db}}}))

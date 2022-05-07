(ns phrag.context
  "Context for constructing Phrag's GraphQL schema from DB schema data."
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

(defn- nest-fk [rel-type table-key fk]
  (-> (reduce-kv (fn [m k v] (assoc m k (keyword v))) {} fk)
      (assoc :from-table table-key)
      (assoc :type rel-type)))

(defn- relation-context-per-table [table]
  (let [fks (:fks table)
        ;; assoc has-one on FK origin table
        tbl-key (keyword (:name table))
        has-one (reduce (fn [m fk]
                          (let [has-1-fld (keyword (has-one-field fk))]
                               (-> m
                                   (update-in [:fields tbl-key] conj has-1-fld)
                                   (update-in [:columns tbl-key] conj
                                              (keyword (:from fk)))
                                   (assoc-in [:nest-fks tbl-key has-1-fld]
                                             (nest-fk :has-one tbl-key fk)))))
                        {:fields {tbl-key #{}}
                         :columns {tbl-key #{}}
                         :nest-fks {tbl-key {}}}
                        fks)]
    ;; assoc has-many inverse relation on FK destination tables
    (reduce
     (fn [m fk]
       (let [has-many-key (keyword (has-many-field table fk))
             has-many-aggr-key (keyword (str (name has-many-key) "_aggregate"))
             to-col-key #{(keyword (:to fk))}
             to-tbl-key (keyword (:table fk))
             n-fk (nest-fk :has-many tbl-key fk)
             n-aggr-fk (nest-fk :has-many-aggr tbl-key fk)]
         {:fields (merge-with into (:fields m)
                              {to-tbl-key #{has-many-key has-many-aggr-key}})
          :columns (merge-with into (:columns m) {to-tbl-key to-col-key})
          :nest-fks (-> (:nest-fks m)
                        (assoc-in [to-tbl-key has-many-key] n-fk)
                        (assoc-in [to-tbl-key has-many-aggr-key] n-aggr-fk))}))
     has-one
     fks)))

(defn- relation-context [tables]
  (reduce (fn [m table]
            (let [rel-ctx (relation-context-per-table table)]
              {:fields (merge-with into (:fields m) (:fields rel-ctx))
               :columns (merge-with into (:columns m) (:columns rel-ctx))
               :nest-fks (merge-with merge (:nest-fks m) (:nest-fks rel-ctx))}))
          {:fields {} :columns {} :nest-fks {}}
          tables))

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

;;; Signal functions

(defn- conj-items [v]
  (reduce (fn [v fns]
            (if (coll? fns)
              (into v fns)
              (conj v fns)))
          [] v))

(defn- signal-per-table
  "Signal functions per resource and operation."
  [signal-map table-key op]
  (let [all-tbl-fns (:all signal-map)
        all-op-fns (get-in signal-map [table-key :all])
        all-timing-fns (get-in signal-map [table-key op :all])
        pre-fns (get-in signal-map [table-key op :pre])
        post-fns (get-in signal-map [table-key op :post])]
    {:pre (filter fn? (conj-items [all-tbl-fns all-op-fns
                                   all-timing-fns pre-fns]))
     :post (filter fn? (conj-items [all-tbl-fns all-op-fns
                                    all-timing-fns post-fns]))}))

;;; Lacinia Schema Context from Table Data

(defn- update-tables [table-map rel-ctx signals]
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
                  (assoc :rel-flds (get-in rel-ctx [:fields k]))
                  (assoc :rel-cols (get-in rel-ctx [:columns k]))
                  (assoc :rels (get-in rel-ctx [:rels k]))
                  (assoc :query-signals (signal-per-table signals k :query))
                  (assoc :create-signals (signal-per-table signals k :create))
                  (assoc :delete-signals (signal-per-table signals k :delete))
                  (assoc :update-signals (signal-per-table signals k :update))))))
   {} table-map))

(defn- schema-context
  "Compiles resource names, Lacinia fields and relationships from table data."
  [tables rel-ctx signals]
  (let [table-map (zipmap (map #(keyword (:name %)) tables) tables)]
    (update-tables table-map rel-ctx signals)))

(def ^:no-doc init-schema {:enums fld/sort-op-enum
                           :input-objects fld/filter-input-objects
                           :objects fld/result-object
                           :queries {}})

(defn options->config [options]
  (let [signals (:signals options)
        config {:router (:router options)
                :db (:db options)
                :tables (:tables options)
                :signals signals
                :signal-ctx (:signal-ctx options)
                :middleware (:middleware options)
                :scan-schema (:scan-schema options true)
                :default-limit (:default-limit options)
                :no-fk-on-db (:no-fk-on-db options false)
                :plural-table-name (:plural-table-name options true)
                :use-aggregation (:use-aggregation options true)}
        db-scm (tbl/db-schema config)
        rel-ctx (relation-context db-scm)]
    (-> config
        (assoc :relation-ctx rel-ctx)
        (assoc :tables (schema-context db-scm rel-ctx signals)))))


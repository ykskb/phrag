(ns phrag.context
  "Context from DB schema data to construct Phrag's GraphQL."
  (:require [camel-snake-kebab.core :as csk]
            [clojure.string :as s]
            [clojure.pprint :as pp]
            [inflections.core :as inf]
            [phrag.db.adapter :as db-adapter]
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
    (if (tbl/circular-m2m-fk? table fk-from)
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

(defn- nest-fk-map
  "Fk map of both directions for resolving nested queries."
  [rel-type table-key fk]
  (-> (reduce-kv (fn [m k v] (assoc m k (keyword v))) {} fk)
      (assoc :from-table table-key)
      (assoc :type rel-type)))

(defn- assoc-has-one-maps
  "assoc has-one on FK origin table"
  [m table-key fks]
  (reduce (fn [m fk]
            (let [has-1-fld (keyword (has-one-field fk))]
              (assoc-in m [:nest-fks table-key has-1-fld]
                        (nest-fk-map :has-one table-key fk))))
          m
          fks))

(defn- assoc-has-many-maps
  "assoc has-many inverse relation on FK destination tables"
  [m table-key table fks]
  (reduce
   (fn [m fk]
     (let [has-many-key (keyword (has-many-field table fk))
           has-many-aggr-key (keyword (str (name has-many-key) "_aggregate"))
           to-tbl-key (keyword (:table fk))
           n-fk (nest-fk-map :has-many table-key fk)
           n-aggr-fk (nest-fk-map :has-many-aggr table-key fk)]
       {:nest-fks (-> (:nest-fks m)
                      (assoc-in [to-tbl-key has-many-key] n-fk)
                      (assoc-in [to-tbl-key has-many-aggr-key] n-aggr-fk))}))
   m
   fks))

(defn- relation-ctx-per-table [table]
  (let [fks (:fks table)
        tbl-key (keyword (:name table))
        has-one-mapped (assoc-has-one-maps {:nest-fks {tbl-key {}}} tbl-key fks)]
    (assoc-has-many-maps has-one-mapped tbl-key table fks)))

(defn- relation-context [tables]
  (reduce (fn [m table]
            (let [rel-ctx (relation-ctx-per-table table)]
              {:nest-fks (merge-with merge (:nest-fks m) (:nest-fks rel-ctx))}))
          {:fields {} :columns {} :nest-fks {}}
          tables))

;; FK Context

(defn- fk-field-keys [fk table to-table-name]
  (let [has-many-fld (has-many-field table fk)
        to-rsc-name (csk/->PascalCase (inf/singular to-table-name))]
    {:to (keyword to-rsc-name)
     :has-many (keyword has-many-fld)
     :has-many-aggr (keyword (str has-many-fld "_aggregate"))
     :has-one (keyword (has-one-field fk))}))

(defn- fk-context [table]
  (let [fks (:fks table)
        fk-map (zipmap (map #(keyword (:from %)) fks) fks)]
    (reduce-kv (fn [m from-key fk]
                 (assoc m from-key
                        {:field-keys (fk-field-keys fk table (:table fk))}))
               {} fk-map)))

;;; Signal functions

(defn- conj-items [v]
  (reduce (fn [v fns]
            (if (coll? fns)
              (into v fns)
              (conj v fns)))
          [] v))

(defn- signal-per-type
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

(defn- table-context
  "Compiles resource names, Lacinia fields and relationships from table data."
  [tables signals]
  (let [table-map (zipmap (map #(keyword (:name %)) tables) tables)]
    (reduce-kv
     (fn [m k table]
       (let [table-name (:name table)
             obj-keys (fld/lcn-obj-keys table-name)
             pk-keys (tbl/pk-keys table)]
         (assoc
          m k
          (-> m
              (assoc :col-keys (tbl/col-key-set table))
              (assoc :fks (fk-context table))
              (assoc :pk-keys pk-keys)
              (assoc :lcn-obj-keys obj-keys)
              (assoc :lcn-qry-keys (fld/lcn-qry-keys table-name))
              (assoc :lcn-mut-keys (fld/lcn-mut-keys table-name))
              (assoc :lcn-descs (fld/lcn-descs table-name))
              (assoc :lcn-fields (fld/lcn-fields table obj-keys pk-keys))
              (assoc :signals {:query (signal-per-type signals k :query)
                               :create (signal-per-type signals k :create)
                               :delete (signal-per-type signals k :delete)
                               :update (signal-per-type signals k :update)})))))
     {} table-map)))

(defn- view-context [views signals]
  (let [view-map (zipmap (map #(keyword (:name %)) views) views)]
    (reduce-kv
     (fn [m k view]
       (let [view-name (:name view)
             obj-keys (fld/lcn-obj-keys view-name)]
         (assoc m k
                (-> m
                    (assoc :lcn-obj-keys obj-keys)
                    (assoc :lcn-qry-keys (fld/lcn-qry-keys view-name))
                    (assoc :lcn-descs (fld/lcn-descs view-name))
                    (assoc :lcn-fields (fld/lcn-fields view obj-keys nil))
                    (assoc :signals
                           {:query (signal-per-type signals k :query)})))))
     {} view-map)))

(defn options->config
  "Creates a config map from user-provided options."
  [options]
  (let [signals (:signals options)
        config {:router (:router options)
                :db (:db options)
                :db-adapter (db-adapter/db->adapter (:db options))
                :tables (:tables options)
                :signal-ctx (:signal-ctx options)
                :middleware (:middleware options)
                :scan-tables (:scan-tables options true)
                :scan-views (:scan-views options true)
                :default-limit (:default-limit options)
                :max-nest-level (:max-nest-level options)
                :no-fk-on-db (:no-fk-on-db options false)
                :plural-table-name (:plural-table-name options true)
                :use-aggregation (:use-aggregation options true)}
        db-scm (tbl/db-schema config)]
    (-> config
        (assoc :relation-ctx (relation-context (:tables db-scm)))
        (assoc :tables (table-context (:tables db-scm) signals))
        (assoc :views (view-context (:views db-scm) signals)))))

(def ^:no-doc init-schema {:enums fld/sort-op-enum
                           :input-objects fld/filter-input-objects
                           :objects fld/result-object
                           :queries {}})


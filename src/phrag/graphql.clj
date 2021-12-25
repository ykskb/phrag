(ns phrag.graphql
  (:require [phrag.table :as tbl]
            [phrag.logging :refer [log]]
            [phrag.resolver :as rslv]
            [camel-snake-kebab.core :as csk]
            [com.walmartlabs.lacinia :as lcn]
            [com.walmartlabs.lacinia.schema :as schema]
            [clojure.pprint :as pp]
            [clojure.string :as s]
            [inflections.core :as inf]
            [superlifter.core :as sl-core]))

;;; Objects

(def ^:private field-types
  {"int" 'Int
   "integer" 'Int
   "bigint" 'Int
   "text" 'String
   "timestamp" 'String
   "character varying" 'String
   "timestamp without time zone" 'String
   "boolean" 'Boolean})

(defn- needs-non-null? [col]
  (and (= 1 (:notnull col)) (nil? (:dflt_value col))))

(defn- rsc-object [table]
  (reduce (fn [m col]
            (let [col-name (:name col)
                  col-key (keyword col-name)
                  col-type (get field-types (:type col))
                  field (if (needs-non-null? col)
                          {:type `(~'non-null ~col-type)}
                          {:type col-type})]
              (assoc m col-key field)))
          {} (:columns table)))

(defn- aggr-object [rsc-obj-key]
  {:count {:type 'Int}
   :sum {:type rsc-obj-key}
   :avg {:type rsc-obj-key}
   :max {:type rsc-obj-key}
   :min {:type rsc-obj-key}})

;;; Input objects

;; Where-style filters

(def ^:private clauses-desc
  (str "Format for where clauses is {column: {operator: value}}. "
       "Multiple parameters are applied with `AND` operators."))

(def ^:private where-desc
  (str "AND / OR group can be created as clause lists in "
       "\"and\" / \"or\" parameter under \"where\". "
       "Multiple parameters are applied with `AND` operators."))

(def ^:private filter-inputs
  {:StrWhere {:fields {:in {:type '(list String)}
                       :eq {:type 'String}
                       :like {:type 'String}}}
   :FloatWhere {:fields {:in {:type '(list Float)}
                         :eq {:type 'Float}
                         :gt {:type 'Float}
                         :lt {:type 'Float}
                         :gte {:type 'Float}
                         :lte {:type 'Float}}}
   :IntWhere {:fields {:in {:type '(list Int)}
                       :eq {:type 'Int}
                       :gt {:type 'Int}
                       :lt {:type 'Int}
                       :gte {:type 'Int}
                       :lte {:type 'Int}}}
   :BoolWhere {:fields {:eq {:type 'Boolean}}}})

(def ^:private flt-input-types
  {"int" :IntWhere
   "integer" :IntWhere
   "bigint" :IntWhere
   "text" :StrWhere
   "timestamp" :StrWhere
   "character varying" :StrWhere
   "timestamp without time zone" :StrWhere
   "boolean" :BoolWhere})

(defn- rsc-clauses [table]
  (reduce (fn [m col]
            (let [col-name (:name col)
                  col-key (keyword col-name)
                  input-type (get flt-input-types (:type col))
                  field {:type input-type}]
              (assoc m col-key field)))
          {} (:columns table)))

(defn- rsc-where [table rsc-cls-key]
  (-> (rsc-clauses table)
      (assoc :and {:type `(~'list ~rsc-cls-key)})
      (assoc :or {:type `(~'list ~rsc-cls-key)})))

;; Sort fields

(def ^:private sort-desc
  (str "Sort format is {column: \"asc\" or \"desc\"}."))

(def ^:private sort-op
  {:SortOperator {:values [:asc :desc]}})

(defn- sort-fields [table]
  (reduce (fn [m col]
            (let [col-name (:name col)
                  col-key (keyword col-name)
                  field {:type :SortOperator}]
              (assoc m col-key field)))
          {} (:columns table)))

;;; Queries

(defn- rsc-relations [rel-map table-name]
  (let [rels (get rel-map table-name)]
    (into rels (map #(str % "_aggregate") rels))))

(defn- assoc-queries [schema table rsc-name table-key obj-fields
                      rel-map use-aggr]
  (let [table-name (:name table)
        rscs (inf/plural table-name)
        rscs-name (csk/->PascalCase rscs)
        query-key (keyword rscs)
        rsc-name-key (keyword rsc-name)
        rsc-cls-key (csk/->PascalCaseKeyword (str rsc-name "Clauses"))
        rsc-whr-key (csk/->PascalCaseKeyword (str rsc-name "Where"))
        rsc-sort-key (csk/->PascalCaseKeyword (str rsc-name "Sort"))
        rels (rsc-relations rel-map table-name)
        entity-schema (-> schema
        (assoc-in [:objects rsc-name-key]
                  {:description rsc-name
                   :fields obj-fields})
        (assoc-in [:input-objects rsc-cls-key]
                  {:description clauses-desc
                   :fields (rsc-clauses table)})
        (assoc-in [:input-objects rsc-whr-key]
                  {:description where-desc
                   :fields (rsc-where table rsc-cls-key)})
        (assoc-in [:input-objects rsc-sort-key]
                  {:description sort-desc
                   :fields (sort-fields table)})
        (assoc-in [:queries query-key]
                  {:type `(~'list ~rsc-name-key)
                   :description (str "Query " rscs-name ".")
                   :args {:where {:type rsc-whr-key}
                          :sort {:type rsc-sort-key}
                          :limit {:type 'Int}
                          :offset {:type 'Int}}
                   :resolve (partial rslv/list-query
                                     table-key rels)}))]
    (if use-aggr
      (let [rsc-fields-key (keyword (str rsc-name "Fields"))
            rsc-aggr-key (keyword (str rsc-name "Aggregate"))
            aggr-q-key (keyword (str rscs "_aggregate"))]
        (-> entity-schema
            (assoc-in [:objects rsc-fields-key]
                      {:description rsc-name
                       :fields obj-fields})
            (assoc-in [:objects rsc-aggr-key]
                      {:description rsc-name
                       :fields (aggr-object rsc-fields-key)})
            (assoc-in [:queries aggr-q-key]
                      {:type rsc-aggr-key
                       :description (str "Aggrecate " rscs-name ".")
                       :args {:where {:type rsc-whr-key}}
                       :resolve (partial rslv/aggregate-root
                                         table-key)})))
      entity-schema)))

;;; Mutations

;; Primary key fields

(defn- pk-obj [pk-kws obj-fields]
  (reduce (fn [m k]
            (let [t (get-in obj-fields [k :type])]
              (assoc m k {:type `(~'non-null ~t)})))
          {} pk-kws))

(defn- update-obj [pk-kws obj-fields]
  (reduce (fn [m k] (dissoc m k)) obj-fields pk-kws))

(defn- assoc-mutations [schema table rsc-name table-key obj-fields]
  (let [create-key (keyword (str "create" rsc-name))
        update-key (keyword (str "update" rsc-name))
        delete-key (keyword (str "delete" rsc-name))
        rsc-pk-key (csk/->PascalCaseKeyword (str rsc-name "Pks"))
        rsc-pk-input-key (csk/->PascalCaseKeyword
                          (str rsc-name "PkColumns"))
        pk-keys (map #(keyword (:name %)) (:pks table))
        update-fields (update-obj pk-keys obj-fields)
        col-keys (tbl/col-kw-set table)]
        (-> schema
            (assoc-in [:objects rsc-pk-key]
                      {:description sort-desc
                       :fields (pk-obj pk-keys obj-fields)})
            (assoc-in [:input-objects rsc-pk-input-key]
                      {:description sort-desc
                       :fields (pk-obj pk-keys obj-fields)})
            (assoc-in [:mutations create-key]
                      {:type rsc-pk-key
                       ;; Assumption: `id` column is auto-incremental
                       :args (dissoc obj-fields :id)
                       :resolve (partial rslv/create-root
                                         table-key pk-keys col-keys)})
            (assoc-in [:mutations update-key]
                      {:type :Result
                       :args
                       (assoc update-fields :pk_columns
                              {:type `(~'non-null ~rsc-pk-input-key)})
                       :resolve
                       (partial rslv/update-root table-key col-keys)})
            (assoc-in [:mutations delete-key]
                      {:type :Result
                       :args {:pk_columns
                              {:type `(~'non-null ~rsc-pk-input-key)}}
                       :resolve
                       (partial rslv/delete-root table-key)}))))

;;; GraphQL schema

(defn- root-schema [config rel-map]
  (reduce (fn [m table]
            (let [table-name (:name table)
                  table-key (keyword table-name)
                  rsc (inf/singular table-name)
                  rsc-name (csk/->PascalCase rsc)
                  obj-fields (rsc-object table)
                  use-aggr (:use-aggregation config)]
              (-> m
                  (assoc-queries table rsc-name table-key obj-fields
                                 rel-map use-aggr)
                  (assoc-mutations table rsc-name table-key
                                   obj-fields))))
          {:enums (merge sort-op)
           :input-objects filter-inputs
           :objects {:Result {:fields {:result {:type 'Boolean}}}}
           :queries {}} (:tables config)))

(defn- has-many-field-key [table fk]
  (let [tbl-name (:name table)
        rscs (inf/plural tbl-name)
        fk-from (:from fk)]
    (keyword (if (tbl/is-circular-m2m-fk? table fk-from)
               (str rscs "_on_" fk-from)
               rscs))))

(defn- has-one-field-key [fk]
  (let [from (:from fk)]
    (if (s/ends-with? from "_id")
      (keyword (s/replace from #"_id" ""))
      (keyword (str from "_" (inf/singular (:table fk)))))))

(defn- update-fk-schema [schema table rel-map use-aggr]
  (let [table-name (:name table)
        table-key (keyword table-name)
        rsc (inf/singular table-name)
        rsc-name (csk/->PascalCase rsc)
        rsc-name-key (keyword rsc-name)
        rsc-aggr-key (keyword (str rsc-name "Aggregate"))
        rsc-whr-key (keyword (str rsc-name "Where"))
        rsc-sort-key (keyword (str rsc-name "Sort"))
        rsc-rels (rsc-relations rel-map table-name)]
    (reduce (fn [m fk]
              (let [fk-to-tbl-name (:table fk)
                    fk-to-tbl-key (keyword fk-to-tbl-name)
                    fk-to-rsc (inf/singular fk-to-tbl-name)
                    fk-to-rsc-name (csk/->PascalCase fk-to-rsc)
                    fk-to-rsc-name-key (keyword fk-to-rsc-name)
                    fk-from-rsc-col (keyword (:from fk))
                    fk-to-rels (rsc-relations rel-map fk-to-tbl-name)
                    has-many-fld-key (has-many-field-key table fk)]
                (cond-> m
                  ;; has-many on linked tables
                  true (assoc-in
                        [:objects fk-to-rsc-name-key
                         :fields has-many-fld-key]
                        {:type `(~'list ~rsc-name-key)
                         :args {:where {:type rsc-whr-key}
                                :sort {:type rsc-sort-key}
                                :limit {:type 'Int}
                                :offset {:type 'Int}}
                         :resolve (partial rslv/has-many fk-from-rsc-col
                                           table-key rsc-rels)})
                  ;; has-many aggregate on linked tables
                  use-aggr (assoc-in
                            [:objects fk-to-rsc-name-key :fields
                             (keyword (str (name has-many-fld-key)
                                           "_aggregate"))]
                            {:type rsc-aggr-key
                             :args {:where {:type rsc-whr-key}}
                             :resolve
                             (partial rslv/aggregate-has-many
                                      fk-from-rsc-col table-key)})
                  ;; has-one on fk origin tables
                  true (assoc-in
                        [:objects rsc-name-key
                         :fields (has-one-field-key fk)]
                        {:type fk-to-rsc-name-key
                         :resolve
                         (partial rslv/has-one fk-from-rsc-col
                                  fk-to-tbl-key fk-to-rels)}))))
            schema (:fks table))))

(defn- update-relationships [schema config rel-map]
  (reduce (fn [m table]
            (update-fk-schema m table rel-map
                              (:use-aggregation config)))
          schema (:tables config)))

(defn- sl-config [config]
  (let [buckets
        (reduce (fn [m tbl]
                  (let [tbl-name (:name tbl)
                        aggr-name (str tbl-name "_aggregate")]
                    (-> m
                        (assoc (keyword tbl-name)
                               {:triggers {:elastic {:threshold 0}}})
                        (assoc (keyword aggr-name)
                               {:triggers {:elastic {:threshold 0}}}))))
                {:default {:triggers {:elastic {:threshold 0}}}}
                (:tables config))]
    {:buckets buckets
     :urania-opts {:env {:db (:db config)}}}))

(defn- sl-ctx [config]
  (sl-core/start! (sl-config config)))

(defn- sl-stop! [sl-ctx]
  (sl-core/stop! sl-ctx))

(defn schema [config]
  (let [rel-map (tbl/full-rel-map config)
        scm-map (-> (root-schema config rel-map)
                    (update-relationships config rel-map))]
    (log :info "Generated queries: " (sort (keys (:queries scm-map))))
    (log :info "Generated mutations: " (sort (keys (:mutations scm-map))))
    (schema/compile scm-map)))

(defn exec [config schema query vars req]
  (let [sl-ctx (sl-ctx config)
        ctx (-> (:signal-ctx config {})
                (assoc :req req)
                (assoc :sl-ctx sl-ctx)
                (assoc :db (:db config))
                (assoc :signals (:signals config)))
        res (lcn/execute schema query vars ctx)]
    (let [_ctx (sl-stop! sl-ctx)]
      res)))


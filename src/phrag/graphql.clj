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
   "boolean" 'Boolean
   })

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
                       :lte {:type 'Int}}}})

(def ^:private flt-input-types
  {"int" :IntWhere
   "integer" :IntWhere
   "bigint" :IntWhere
   "text" :StrWhere
   "timestamp" :StrWhere
   "character varying" :StrWhere
   "timestamp without time zone" :StrWhere})

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
  {:SortOp {:values [:asc :desc]}})

(defn- sort-fields [table]
  (reduce (fn [m col]
            (let [col-name (:name col)
                  col-key (keyword col-name)
                  field {:type :SortOp}]
              (assoc m col-key field)))
          {} (:columns table)))

;;; GraphQL schema

(defn- rsc-relations [rel-map table-name]
  (let [rels (get rel-map table-name)]
    (into rels (map #(str % "_aggregate") rels))))

(defn- root-schema [config rel-map]
  (reduce (fn [m table]
            (let [table-name (:name table)
                  table-type (:table-type table)
                  use-aggr (:use-aggregation config)
                  rsc (inf/singular table-name)
                  rscs (inf/plural table-name)
                  rsc-name (csk/->PascalCase rsc)
                  rscs-name (csk/->PascalCase rscs)
                  rsc-cls-key (csk/->PascalCaseKeyword (str rsc-name "Clauses"))
                  rsc-whr-key (csk/->PascalCaseKeyword (str rsc-name "Where"))
                  rsc-sort-key (csk/->PascalCaseKeyword (str rsc-name "Sort"))
                  rsc-name-key (keyword rsc-name)
                  rsc-fields-key (keyword (str rsc-name "Fields"))
                  rsc-aggr-key (keyword (str rsc-name "Aggregate"))
                  list-q-key (keyword rscs)
                  aggr-q-key (keyword (str rscs "_aggregate"))
                  create-key (keyword (str "create" rsc-name))
                  update-key (keyword (str "update" rsc-name))
                  delete-key (keyword (str "delete" rsc-name))
                  obj-fields (rsc-object table)
                  cols (tbl/col-names table)
                  rels (rsc-relations rel-map table-name)]
              (cond-> m
                    true (assoc-in [:objects rsc-name-key]
                                   {:description rsc-name
                                    :fields obj-fields})
                    true (assoc-in [:input-objects rsc-cls-key]
                                   {:description clauses-desc
                                    :fields (rsc-clauses table)})
                    true (assoc-in [:input-objects rsc-whr-key]
                                   {:description where-desc
                                    :fields (rsc-where table rsc-cls-key)})
                    true (assoc-in [:input-objects rsc-sort-key]
                                   {:description sort-desc
                                    :fields (sort-fields table)})
                    use-aggr (assoc-in [:objects rsc-fields-key]
                                       {:description rsc-name
                                        :fields obj-fields})
                    use-aggr (assoc-in [:objects rsc-aggr-key]
                                       {:description rsc-name
                                        :fields (aggr-object rsc-fields-key)})

                    (= table-type :root)
                    (assoc-in [:queries list-q-key]
                              {:type `(~'list ~rsc-name-key)
                               :description (str "Query " rscs-name ".")
                               :args {:where {:type rsc-whr-key}
                                      :sort {:type rsc-sort-key}
                                      :limit {:type 'Int}
                                      :offset {:type 'Int}}
                               :resolve (partial rslv/list-query
                                                 table-name rels)})
                    use-aggr
                    (assoc-in [:queries aggr-q-key]
                              {:type rsc-aggr-key
                               :description (str "Aggrecate " rscs-name ".")
                               :args {:where {:type rsc-whr-key}}
                               :resolve (partial rslv/aggregate-root
                                                 table-name)})

                    (= table-type :root)
                    (assoc-in [:mutations create-key]
                              {:type :NewId
                               :args (dissoc obj-fields :id)
                               :resolve (partial rslv/create-root
                                                 table-name cols)})
                    (= table-type :root)
                    (assoc-in [:mutations update-key]
                              {:type :Result
                               :args obj-fields
                               :resolve (partial rslv/update-root
                                                 table-name cols)})
                    (= table-type :root)
                    (assoc-in [:mutations delete-key]
                              {:type :Result
                               :args {:id {:type '(non-null ID)}}
                               :resolve (partial rslv/delete-root table-name)}))))
          {:enums (merge sort-op)
           :input-objects filter-inputs
           :objects {:Result {:fields {:result {:type 'Boolean}}}
                     :NewId {:fields {:id {:type 'Int}}}}
           :queries {}} (:tables config)))

(defn has-many-field-key [table fk]
  (let [tbl-name (:name table)
        rscs (inf/plural tbl-name)
        fk-from (:from fk)]
    (keyword (if (and (= (:table-type table) :pivot)
                      (tbl/is-circular-m2m-fk? table fk-from))
               (str rscs "_on_" fk-from)
               rscs))))

(defn- has-one-field-key [fk]
  (let [from (:from fk)]
    (if (s/ends-with? from "_id")
      (keyword (s/replace from #"_id" ""))
      (keyword (str from "_" (inf/singular (:table fk)))))))

(defn- update-fk-schema [schema table rel-map use-aggr]
  (let [table-name (:name table)
        rsc (inf/singular table-name)
        rsc-name (csk/->PascalCase rsc)
        rsc-name-key (keyword rsc-name)
        rsc-aggr-key (keyword (str rsc-name "Aggregate"))
        rsc-whr-key (keyword (str rsc-name "Where"))
        rsc-sort-key (keyword (str rsc-name "Sort"))
        rsc-rels (rsc-relations rel-map table-name)]
    (reduce (fn [m fk]
              (let [fk-to-tbl-name (:table fk)
                    fk-to-rsc (inf/singular fk-to-tbl-name)
                    fk-to-rsc-name (csk/->PascalCase fk-to-rsc)
                    fk-to-rsc-name-key (keyword fk-to-rsc-name)
                    fk-from-rsc-col (keyword (:from fk))
                    fk-to-rels (rsc-relations rel-map fk-to-tbl-name)
                    has-many-fld-key (has-many-field-key table fk)]
                (cond-> m
                  ;; has-many on linked tables
                  true (assoc-in
                        [:objects fk-to-rsc-name-key :fields has-many-fld-key]
                        {:type `(~'list ~rsc-name-key)
                         :args {:where {:type rsc-whr-key}
                                :sort {:type rsc-sort-key}
                                :limit {:type 'Int}
                                :offset {:type 'Int}}
                         :resolve (partial rslv/has-many fk-from-rsc-col
                                           table-name rsc-rels)})
                  ;; has-many aggregate on linked tables
                  use-aggr (assoc-in
                            [:objects fk-to-rsc-name-key :fields
                             (keyword (str (name has-many-fld-key)
                                           "_aggregate"))]
                            {:type rsc-aggr-key
                             :args {:where {:type rsc-whr-key}}
                             :resolve (partial rslv/aggregate-has-many
                                               fk-from-rsc-col table-name)})
                  ;; has-one on fk origin tables
                  true (assoc-in
                        [:objects rsc-name-key :fields (has-one-field-key fk)]
                        {:type fk-to-rsc-name-key
                         :resolve (partial rslv/has-one fk-from-rsc-col
                                           fk-to-tbl-name fk-to-rels)}))))
            schema (:fks table))))

(defn- update-pivot-schema [schema table]
  (let [tbl-name (:name table)
        primary-fks (tbl/primary-fks table)
        rsc-a-col (:from (first primary-fks))
        rsc-b-col (:from (second primary-fks))
        rsc-name (csk/->PascalCase (inf/singular tbl-name))
        create-key (keyword (str "create" rsc-name))
        delete-key (keyword (str "delete" rsc-name))
        obj-fields (rsc-object table)
        cols (tbl/col-names table)]
    (-> schema
        (assoc-in [:mutations create-key]
                  {:type :Result
                   :args (dissoc obj-fields :id)
                   :resolve (partial rslv/create-n-n rsc-a-col rsc-b-col
                                     tbl-name cols)})
        (assoc-in [:mutations delete-key]
                  {:type :Result
                   :args (dissoc obj-fields :id)
                   :resolve (partial rslv/delete-n-n rsc-a-col rsc-b-col
                                     tbl-name)}))))

(defn- update-relationships [schema config rel-map]
  (reduce (fn [m table]
            (cond-> m
              true (update-fk-schema table rel-map (:use-aggregation config))
              (= (:table-type table) :pivot) (update-pivot-schema table)))
          schema (:tables config)))

(defn- sl-config [config]
  (let [buckets (reduce (fn [m tbl]
                          (let [tbl-name (:name tbl)]
                            (-> m
                                (assoc (keyword tbl-name)
                                       {:triggers {:elastic {:threshold 0}}})
                                (assoc (keyword (str tbl-name "_aggregate"))
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

(defn exec [config schema query vars]
  (let [sl-ctx (sl-ctx config)
        ctx (-> (:signal-ctx config {})
                (assoc :sl-ctx sl-ctx)
                (assoc :db (:db config))
                (assoc :signals (:signals config)))
        res (lcn/execute schema query vars ctx)]
    (let [_ctx (sl-stop! sl-ctx)]
      res)))


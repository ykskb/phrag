(ns phrag.graphql
  (:require [phrag.table :as tbl]
            [phrag.logging :refer [log]]
            [phrag.resolver :as rslv]
            [camel-snake-kebab.core :as csk]
            [com.walmartlabs.lacinia :as lcn]
            [com.walmartlabs.lacinia.schema :as schema]
            [clojure.pprint :as pp]
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

(defn- root-fields [table]
  (reduce (fn [m col]
            (let [col-name (:name col)
                  col-key (keyword col-name)
                  col-type (get field-types (:type col))
                  field (if (needs-non-null? col)
                          {:type `(~'non-null ~col-type)}
                          {:type col-type})]
              (assoc m col-key field)))
          {} (:columns table)))

;;; Input objects

;; Where filter

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

;; Sort field

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

;;; Schema

(defn- root-schema [config rel-map]
  (reduce (fn [m table]
            (let [table-name (:name table)
                  table-type (:table-type table)
                  rsc (inf/singular table-name)
                  rscs (inf/plural table-name)
                  rsc-name (csk/->PascalCase rsc)
                  rscs-name (csk/->PascalCase rscs)
                  rsc-cls-key (csk/->PascalCaseKeyword (str rsc-name "Clauses"))
                  rsc-whr-key (csk/->PascalCaseKeyword (str rsc-name "Where"))
                  rsc-sort-key (csk/->PascalCaseKeyword (str rsc-name "Sort"))
                  rsc-name-key (keyword rsc-name)
                  list-q-key (keyword rscs)
                  create-key (keyword (str "create" rsc-name))
                  update-key (keyword (str "update" rsc-name))
                  delete-key (keyword (str "delete" rsc-name))
                  obj-fields (root-fields table)
                  cols (tbl/col-names table)
                  rels (get rel-map table-name)]
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

(defn- update-fk-schema [schema config table rel-map]
  (let [table-name (:name table)
        rsc (inf/singular table-name)
        rscs (inf/plural table-name)
        rsc-name (csk/->PascalCase rsc)
        rscs-key (keyword rscs)
        rsc-name-key (keyword rsc-name)
        rsc-whr-key (keyword (str rsc-name "Where"))
        rsc-sort-key (keyword (str rsc-name "Sort"))
        rsc-rels (get rel-map table-name)]
    (reduce (fn [m blg-to]
              (let [blg-to-tbl-name (:table blg-to)
                    blg-to-rsc (inf/singular blg-to-tbl-name)
                    blg-to-rsc-name (csk/->PascalCase blg-to-rsc)
                    blg-to-rsc-name-key (keyword blg-to-rsc-name)
                    blg-to-rsc-key (keyword blg-to-rsc)
                    blg-to-rsc-id (keyword (:from blg-to))
                    blg-to-rels (get rel-map blg-to-tbl-name)]
                (-> m
                    ;; has many
                    (assoc-in [:objects blg-to-rsc-name-key :fields rscs-key]
                              {:type `(~'list ~rsc-name-key)
                               :args {:where {:type rsc-whr-key}
                                      :sort {:type rsc-sort-key} ;
                                      :limit {:type 'Int}
                                      :offset {:type 'Int}}
                               :resolve (partial rslv/has-many blg-to-rsc-id
                                                 table-name rsc-rels)})
                    ;; has one
                    (assoc-in [:objects rsc-name-key :fields blg-to-rsc-key]
                              {:type blg-to-rsc-name-key
                               :resolve (partial rslv/has-one blg-to-rsc-id
                                                 blg-to-tbl-name
                                                 blg-to-rels)}))))
            schema (:fks table))))

(defn- update-pivot-schema [schema config table]
  (let [tbl-name (:name table)
        primary-fks (tbl/primary-fks table)
        rsc-a-tbl-name (:table (first primary-fks))
        rsc-b-tbl-name (:table (second primary-fks))
        rsc-a (inf/singular rsc-a-tbl-name)
        rsc-b (inf/singular rsc-b-tbl-name)
        rsc-a-name (csk/->PascalCase rsc-a)
        rsc-b-name (csk/->PascalCase rsc-b)
        rsc-a-col (:from (first primary-fks))
        rsc-b-col (:from (second primary-fks))
        create-key (keyword (str "create" rsc-a-name rsc-b-name))
        delete-key (keyword (str "delete" rsc-a-name rsc-b-name))
        obj-fields (root-fields table)
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
              true (update-fk-schema config table rel-map)
              (= (:table-type table) :pivot) (update-pivot-schema config table)))
          schema (:tables config)))

(defn- sl-config [config]
  (let [buckets (reduce (fn [m tbl]
                          (assoc m (keyword (:name tbl))
                                 {:triggers {:elastic {:threshold 0}}}))
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


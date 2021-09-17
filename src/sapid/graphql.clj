(ns sapid.graphql
  (:require [clojure.walk :as w]
            [sapid.table :as tbl]
            [sapid.handlers.core :as c]
            [camel-snake-kebab.core :as csk]
            [com.walmartlabs.lacinia.schema :as schema]
            ;; [clojure.pprint :as pp]
            [inflections.core :as inf]))

;; Resolvers

(def ^:private sort-ops
  {:eq  :=
   :lt  :<
   :le  :<=
   :lte :<=
   :gt  :>
   :ge  :>=
   :gte :>=
   :ne  :!=})

(defn- parse-filter [fltr]
  (map (fn [[k v]]
         (let [op ((:operator v) sort-ops)]
           [op k (:value v)]))
       fltr))

(defn- parse-sort [m v]
  (let [col (first (keys v))
        direc (col v :desc)]
    (if (and col direc)
      (-> m
          (assoc :order-col col)
          (assoc :direc direc))
      m)))

(defn- args->filters [args]
  (reduce (fn [m [k v]]
            (cond
              (= k :filter) (assoc m :filters (parse-filter v))
              (= k :sort) (parse-sort m v)
              (= k :limit) (assoc m :limit v)
              (= k :offset) (assoc m :offset v)
              :else m))
          {:limit 100 :offset 0}
          args))

(defn- resolve-list-query [db table _ctx args _val]
  (let [filters (args->filters args)]
    (c/list-root db table filters)))

(defn- resolve-id-query [db table _ctx args _val]
  (let [filters (args->filters args)]
    (c/fetch-root (:id args) db table filters)))

(defn- resolve-has-many [id-key db table _ctx args val]
  (let [arg-fltrs (args->filters args)
        filters (update arg-fltrs :filters conj [:= id-key (:id val)])]
    (c/list-root db table filters)))

(defn- resolve-has-one [id-key db table _ctx _args val]
  (c/fetch-root (id-key val) db table nil))

(defn- resolve-nn [join-col p-col db nn-table table _ctx args val]
  (let [filters (args->filters args)]
    (c/list-n-n join-col p-col (:id val) db nn-table table filters)))

(def ^:private res-true
  {:result true})

(defn- create-root [db table cols _ctx args _val]
  (c/create-root (w/stringify-keys args) db table cols)
  res-true)

(defn- update-root [db table cols _ctx args _val]
  (c/patch-root (:id args) (w/stringify-keys args) db table cols)
  res-true)

(defn- delete-root [db table _ctx args _val]
  (c/delete-root (:id args) db table)
  res-true)

(defn- create-n-n [col-a col-b db table cols _ctx args _val]
  (let [col-a-key (keyword col-a)
        col-b-key (keyword col-b)]
    (c/create-n-n col-a (col-a-key args) col-b (col-b-key args) args
                  db table cols)
    res-true))

(defn- delete-n-n [col-a col-b db table _ctx args _val]
  (let [col-a-key (keyword col-a)
        col-b-key (keyword col-b)]
    (c/delete-n-n col-a (col-a-key args) col-b (col-b-key args)
                  db table)
    res-true))

;; Object / input object fields

(def ^:private field-types
  {"int" 'Int
   "integer" 'Int
   "text" 'String
   "timestamp" 'String})

(def ^:private flt-input-types
  {"int" :IntFilter
   "integer" :IntFilter
   "text" :StringFilter
   "timestamp" :StringFilter})

(defn- has-rel-type? [t table]
  (some #(= t %) (:relation-types table)))

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

(defn- filter-fields [table]
  (reduce (fn [m col]
            (let [col-name (:name col)
                  col-key (keyword col-name)
                  input-type (get flt-input-types (:type col))
                  field {:type input-type}]
              (assoc m col-key field)))
          {} (:columns table)))

(defn- sort-fields [table]
  (reduce (fn [m col]
            (let [col-name (:name col)
                  col-key (keyword col-name)
                  field {:type :SortOp}]
              (assoc m col-key field)))
          {} (:columns table)))

;; Input object descriptions

(def ^:private filter-desc
  (str "Filter format is {[column]: {operator: [op] value: [val]}}. "
       "Supported operators are eq, ne, lt, le/lte, gt, and ge/gte. "
       "Multiple filters are applied with `AND` operators."))

(def ^:private sort-desc
  (str "Sort format is {[column]: [\"asc\" or \"desc\"]}."))

;; Input objects

(def ^:private filter-inputs
  {:StringFilter {:fields {:operator {:type :FilterOp}
                           :value {:type 'String}}}
   :FloatFilter {:fields {:operator {:type :FilterOp}
                          :value {:type 'Float}}}
   :IntFilter {:fields {:operator {:type :FilterOp}
                        :value {:type 'Int}}}})

;; Enums

(def ^:private filter-op
  {:FilterOp {:values [:eq :ne :lt :le :lte :gt :ge :gte]}})

(def ^:private sort-op
  {:SortOp {:values [:asc :desc]}})

;; Schema

(defn- root-schema [config]
  (reduce (fn [m table]
            (let [table-name (tbl/to-table-name (:name table) config)
                  rsc (inf/singular table-name)
                  rscs (inf/plural table-name)
                  rsc-name (csk/->PascalCase rsc)
                  rscs-name (csk/->PascalCase rscs)
                  rsc-flt-key (csk/->PascalCaseKeyword (str rsc-name "Filter"))
                  rsc-sort-key (csk/->PascalCaseKeyword (str rsc-name "Sort"))
                  rsc-name-key (keyword rsc-name)
                  id-q-key (keyword rsc)
                  list-q-key (keyword rscs)
                  create-key (keyword (str "create" rsc-name))
                  update-key (keyword (str "update" rsc-name))
                  delete-key (keyword (str "delete" rsc-name))
                  obj-fields (root-fields table)
                  db (:db config)
                  cols (tbl/col-names table)]
              (if (has-rel-type? :root table)
                (-> m
                    (assoc-in [:objects rsc-name-key]
                              {:description rsc-name
                               :fields obj-fields})
                    (assoc-in [:input-objects rsc-flt-key]
                              {:description filter-desc
                               :fields (filter-fields table)})
                    (assoc-in [:input-objects rsc-sort-key]
                              {:description sort-desc
                               :fields (sort-fields table)})
                    (assoc-in [:queries list-q-key]
                              {:type `(~'list ~rsc-name-key)
                               :description (str "List " rscs-name ".")
                               :args {:filter {:type rsc-flt-key}
                                      :sort {:type rsc-sort-key}
                                      :limit {:type 'Int}
                                      :offset {:type 'Int}}
                               :resolve (partial resolve-list-query
                                                 db table-name)})
                    (assoc-in [:queries id-q-key]
                              {:type rsc-name-key
                               :description (str "Query " rsc-name " by id.")
                               :args {:id {:type '(non-null ID)}}
                               :resolve (partial resolve-id-query
                                                 db table-name)})
                    (assoc-in [:mutations create-key]
                              {:type :Result
                               :args (dissoc obj-fields :id)
                               :resolve (partial create-root db table-name
                                                 cols)})
                    (assoc-in [:mutations update-key]
                              {:type :Result
                               :args obj-fields
                               :resolve (partial update-root db table-name
                                                 cols)})
                    (assoc-in [:mutations delete-key]
                              {:type :Result
                               :args {:id {:type '(non-null ID)}}
                               :resolve (partial delete-root db table-name)}))
                m)))
          {:enums (merge filter-op sort-op)
           :input-objects filter-inputs
           :objects {:Result {:fields {:result {:type 'Boolean}}}}
           :queries {}} (:tables config)))

(defn- add-one-n-schema [schema config table]
  (let [table-name (tbl/to-table-name (:name table) config)
        rsc (inf/singular table-name)
        rscs (inf/plural table-name)
        rsc-name (csk/->PascalCase rsc)
        rscs-key (keyword rscs)
        rsc-name-key (keyword rsc-name)
        rsc-flt-key (keyword (str rsc-name "Filter"))
        rsc-sort-key (keyword (str rsc-name "Sort"))
        db (:db config)]
    (reduce (fn [m blg-to]
              (let [blg-to-rsc (inf/singular blg-to)
                    blg-to-rsc-name (csk/->PascalCase blg-to-rsc)
                    blg-to-rsc-name-key (keyword blg-to-rsc-name)
                    blg-to-rsc-key (keyword blg-to-rsc)
                    blg-to-rsc-id (keyword (str blg-to-rsc "_id"))
                    blg-to-table-name (tbl/to-table-name blg-to-rsc config)]
                (-> m
                    ;; has many
                    (assoc-in [:objects blg-to-rsc-name-key :fields rscs-key]
                              {:type `(~'list ~rsc-name-key)
                               :args {:filter {:type rsc-flt-key}
                                      :sort {:type rsc-sort-key}
                                      :limit {:type 'Int}
                                      :offset {:type 'Int}}
                               :resolve (partial resolve-has-many blg-to-rsc-id
                                                 db table-name)})
                    ;; has one
                    (assoc-in [:objects rsc-name-key :fields blg-to-rsc-key]
                              {:type blg-to-rsc-name-key
                               :resolve (partial resolve-has-one blg-to-rsc-id
                                                 db blg-to-table-name)}))))
            schema (:belongs-to table))))

(defn- add-n-n-schema [schema config table]
  (let [tbl-name (tbl/to-table-name (:name table) config)
        rsc-a-tbl-name (first (:belongs-to table))
        rsc-b-tbl-name (second (:belongs-to table))
        rsc-a (inf/singular rsc-a-tbl-name)
        rsc-b (inf/singular rsc-b-tbl-name)
        rsc-a-name (csk/->PascalCase rsc-a)
        rsc-b-name (csk/->PascalCase rsc-b)
        rsc-a-name-key (keyword rsc-a-name)
        rsc-b-name-key (keyword rsc-b-name)
        rsc-a-col (str rsc-a "_id")
        rsc-b-col (str rsc-b "_id")
        rscs-a (inf/plural rsc-a-tbl-name)
        rscs-b (inf/plural rsc-b-tbl-name)
        rscs-a-key (keyword rscs-a)
        rscs-b-key (keyword rscs-b)
        create-key (keyword (str "create" rsc-a-name rsc-b-name))
        delete-key (keyword (str "delete" rsc-a-name rsc-b-name))
        obj-fields (root-fields table)
        cols (tbl/col-names table)
        db (:db config)]
    (-> schema
        (assoc-in [:objects rsc-a-name-key :fields rscs-b-key]
                  {:type `(~'list ~rsc-b-name-key)
                   :args {:limit {:type 'Int}
                          :offset {:type 'Int}}
                   :resolve (partial resolve-nn rsc-b-col rsc-a-col db
                                     tbl-name rsc-b-tbl-name)})
        (assoc-in [:objects rsc-b-name-key :fields rscs-a-key]
                  {:type `(~'list ~rsc-a-name-key)
                   :args {:limit {:type 'Int}
                          :offset {:type 'Int}}
                   :resolve (partial resolve-nn rsc-a-col rsc-b-col
                                     db tbl-name rsc-a-tbl-name)})
        (assoc-in [:mutations create-key]
                  {:type :Result
                   :args (dissoc obj-fields :id)
                   :resolve (partial create-n-n rsc-a-col rsc-b-col
                                     db tbl-name cols)})
        (assoc-in [:mutations delete-key]
                  {:type :Result
                   :args (dissoc obj-fields :id)
                   :resolve (partial delete-n-n rsc-a-col rsc-b-col
                                     db tbl-name)}))))

(defn- add-relationships [config schema]
  (reduce (fn [m table]
            (cond
              (has-rel-type? :one-n table) (add-one-n-schema m config table)
              (has-rel-type? :n-n table) (add-n-n-schema m config table)
              :else m))
          schema (:tables config)))

(defn schema [config]
  (->> (root-schema config)
       (add-relationships config)
       schema/compile))


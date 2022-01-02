(ns phrag.field
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

;; Resource Object Fields

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

(defn rsc-fields [table]
  (reduce (fn [m col]
            (let [col-name (:name col)
                  col-key (keyword col-name)
                  col-type (get field-types (:type col))
                  field (if (needs-non-null? col)
                          {:type `(~'non-null ~col-type)}
                          {:type col-type})]
              (assoc m col-key field)))
          {} (:columns table)))

;; Aggregation Input Object

(defn aggr-fields [rsc-obj-key]
  {:count {:type 'Int}
   :sum {:type rsc-obj-key}
   :avg {:type rsc-obj-key}
   :max {:type rsc-obj-key}
   :min {:type rsc-obj-key}})

;;; Where-style Filter Fields

;; Filter Input Object

(def filter-input-objects
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

(def clause-desc
  (str "Format for where clauses is {column: {operator: value}}. "
       "Multiple parameters are applied with `AND` operators."))

(defn clause-fields [table]
  (reduce (fn [m col]
            (let [col-name (:name col)
                  col-key (keyword col-name)
                  input-type (get flt-input-types (:type col))
                  field {:type input-type}]
              (assoc m col-key field)))
          {} (:columns table)))

(def where-desc
  (str "AND / OR groups can be created as clause lists in "
       "\"and\" / \"or\" parameter under \"where\". "
       "Multiple parameters are applied with `AND` operators."))

(defn where-fields [table rsc-cls-key]
  (-> (clause-fields table)
      (assoc :and {:type `(~'list ~rsc-cls-key)})
      (assoc :or {:type `(~'list ~rsc-cls-key)})))

;; Sort Fields

(def sort-desc
  (str "Sort format is {column: \"asc\" or \"desc\"}."))

(def sort-op-enum
  {:SortOperator {:values [:asc :desc]}})

(defn sort-fields [table]
  (reduce (fn [m col]
            (let [col-name (:name col)
                  col-key (keyword col-name)
                  field {:type :SortOperator}]
              (assoc m col-key field)))
          {} (:columns table)))

;; Primary Key Fields

(def pk-desc "Primary key fields.")

(defn pk-fields [pk-keys obj-fields]
  (reduce (fn [m k]
            (let [t (get-in obj-fields [k :type])]
              (assoc m k {:type `(~'non-null ~t)})))
          {} pk-keys))

(defn update-fields [pk-keys obj-fields]
  (reduce (fn [m k] (dissoc m k)) obj-fields pk-keys))



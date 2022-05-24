(ns phrag.field
  "Lacinia fields for a context of Phrag's schema."
  (:require [camel-snake-kebab.core :as csk]
            [inflections.core :as inf]))

;;; Descriptions

(def ^:private clause-desc
  (str "Format for where clauses is {column: {operator: value}}. "
       "Multiple parameters are applied with `AND` operators."))

(def ^:private where-desc
  (str "AND / OR groups can be created as clause lists in "
       "\"and\" / \"or\" parameter under \"where\". "
       "Multiple parameters are applied with `AND` operators."))

(def ^:private sort-desc
  (str "Sort format is {column: \"asc\" or \"desc\"}."))

(def ^:private pk-desc "Primary key fields.")

(defn lcn-descs
  "Returns Lacinia descriptions for a table."
  [table-name]
  (let [rsc-name (csk/->PascalCase (inf/plural table-name))]
    {:rsc rsc-name
     :query (str "Query " rsc-name ".")
     :clauses clause-desc
     :where where-desc
     :sort sort-desc
     :fields (str rsc-name "fields for aggregation.")
     :aggregate (str "Aggrecate " rsc-name ".")
     :pks pk-desc
     :pk-input pk-desc}))

;;; Objects

(def ^:no-doc filter-input-objects
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

(def ^:no-doc result-object {:Result {:fields {:result {:type 'Boolean}}}})

(def ^:no-doc result-true-object {:result true})

;;; Resource Object Fields

(def ^:private field-types
  {"int" 'Int
   "integer" 'Int
   "smallint" 'Int
   "bigint" 'Int
   "smallserial" 'Int
   "serial" 'Int
   "bigserial" 'Int
   "decimal" 'Float
   "numeric" 'Float
   "real" 'Float
   "double precision" 'Float
   "text" 'String
   "timestamp" 'String
   "character varying" 'String
   "varchar" 'String
   "character" 'String
   "char" 'String
   "timestamp without time zone" 'String
   "timestamp with time zone" 'String
   "interval" 'String
   "date" 'String
   "time without time zone" 'String
   "time with time zone" 'String
   "boolean" 'Boolean})

(defn- needs-non-null? [col]
  (and (= 1 (:notnull col)) (nil? (:dflt_value col))))

(defn- rsc-fields [table]
  (reduce (fn [m col]
            (let [col-name (:name col)
                  col-key (keyword col-name)
                  col-type (get field-types (:type col))
                  field (if (needs-non-null? col)
                          {:type `(~'non-null ~col-type)}
                          {:type col-type})]
              (assoc m col-key field)))
          {} (:columns table)))

;;; Input Object Fields

(defn- aggr-fields [rsc-obj-key]
  {:count {:type 'Int}
   :sum {:type rsc-obj-key}
   :avg {:type rsc-obj-key}
   :max {:type rsc-obj-key}
   :min {:type rsc-obj-key}})

;; Clause Fields

(def ^:private flt-input-types
  {"int" :IntWhere
   "integer" :IntWhere
   "smallint" :IntWhere
   "bigint" :IntWhere
   "text" :StrWhere
   "smallserial" :IntWhere
   "serial" :IntWhere
   "bigserial" :IntWhere
   "decimal" :FloatWhere
   "numeric" :FloatWhere
   "real" :FloatWhere
   "double precision" :FloatWhere
   "timestamp" :StrWhere
   "character varying" :StrWhere
   "varchar" :StrWhere
   "character" :StrWhere
   "char" :StrWhere
   "timestamp without time zone" :StrWhere
   "timestamp with time zone" :StrWhere
   "interval" :StrWhere
   "date" :StrWhere
   "time without time zone" :StrWhere
   "time with time zone" :StrWhere
   "boolean" :BoolWhere})

(defn- clause-fields [table]
  (reduce (fn [m col]
            (let [col-name (:name col)
                  col-key (keyword col-name)
                  input-type (get flt-input-types (:type col))
                  field {:type input-type}]
              (assoc m col-key field)))
          {} (:columns table)))

;; Where Fields

(defn- where-fields [table rsc-cls-key]
  (-> (clause-fields table)
      (assoc :and {:type `(~'list ~rsc-cls-key)})
      (assoc :or {:type `(~'list ~rsc-cls-key)})))

;; Sort Fields

(def ^:no-doc sort-op-enum
  {:SortOperator {:values [:asc :desc]}})

(defn- sort-fields [table]
  (reduce (fn [m col]
            (let [col-name (:name col)
                  col-key (keyword col-name)
                  field {:type :SortOperator}]
              (assoc m col-key field)))
          {} (:columns table)))

;; Primary Key Fields for mutations

(defn- pk-fields [pk-keys obj-fields]
  (reduce (fn [m k]
            (let [t (get-in obj-fields [k :type])]
              (assoc m k {:type `(~'non-null ~t)})))
          {} pk-keys))

(defn- update-fields [pk-keys obj-fields]
  (reduce (fn [m k] (dissoc m k)) obj-fields pk-keys))

(defn lcn-fields
  "Returns Lacinia fields for a table."
  [table lcn-keys pk-keys]
  (let [rsc-fields (rsc-fields table)
        pk-fields (pk-fields pk-keys rsc-fields)]
    {:rsc rsc-fields
     :clauses (clause-fields table)
     :where (where-fields table (:clauses lcn-keys))
     :sort (sort-fields table)
     :fields rsc-fields
     :aggregate (aggr-fields (:fields lcn-keys))
     :pks pk-fields
     :pk-input pk-fields
     :update (update-fields pk-keys rsc-fields)}))

;;; Object/Query/Mutation Keys

(defn- lcn-obj-key [rsc-name obj-name]
  (keyword (str rsc-name obj-name)))

(defn lcn-obj-keys
  "Returns Lacinia object keys for a table."
  [table-name]
  (let [sgl-pascal (csk/->PascalCase (inf/singular table-name))]
    {:rsc (keyword sgl-pascal)
     :clauses (lcn-obj-key sgl-pascal "Clauses")
     :where (lcn-obj-key sgl-pascal "Where")
     :sort (lcn-obj-key sgl-pascal "Sort")
     :fields (lcn-obj-key sgl-pascal "Fields")
     :aggregate (lcn-obj-key sgl-pascal "Aggregate")
     :pks (lcn-obj-key sgl-pascal "Pks")
     :pk-input (lcn-obj-key sgl-pascal "PkColumns")}))

(defn lcn-qry-keys
  "Returns Lacinia query keys for a table."
  [table-name]
  (let [plr-bare (csk/->snake_case (inf/plural table-name))]
    {:queries (keyword plr-bare)
     :aggregate (keyword (str plr-bare "_aggregate"))}))

(defn- lcn-mut-key [rsc-name verb]
  (keyword (str verb rsc-name)))

(defn lcn-mut-keys
  "Returns Lacinia mutation keys for a table."
  [table-name]
  (let [sgl-pascal (csk/->PascalCase (inf/singular table-name))]
    {:create (lcn-mut-key sgl-pascal "create")
     :update (lcn-mut-key sgl-pascal "update")
     :delete (lcn-mut-key sgl-pascal "delete")}))


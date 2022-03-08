(ns phrag.graphql
  (:require [phrag.table :as tbl]
            [phrag.logging :refer [log]]
            [phrag.resolver :as rslv]
            [phrag.field :as fld]
            [camel-snake-kebab.core :as csk]
            [com.walmartlabs.lacinia :as lcn]
            [com.walmartlabs.lacinia.schema :as schema]
            [clojure.pprint :as pp]
            [clojure.string :as s]
            [inflections.core :as inf]
            [superlifter.core :as sl-core]))

;;; Signal functions

(defn- conj-items [v]
  (reduce (fn [v fns]
            (if (coll? fns)
              (into v fns)
              (conj v fns)))
          [] v))

(defn- signal-per-op
  "Signal functions per resource and operation."
  [config table-key op]
  (let [signal-map (:signals config)
        all-tbl-fns (:all signal-map)
        all-op-fns (get-in signal-map [table-key :all])
        all-timing-fns (get-in signal-map [table-key op :all])
        pre-fns (get-in signal-map [table-key op :pre])
        post-fns (get-in signal-map [table-key op :post])]
    {:pre (filter fn? (conj-items [all-tbl-fns all-op-fns
                                   all-timing-fns pre-fns]))
     :post (filter fn? (conj-items [all-tbl-fns all-op-fns
                                    all-timing-fns post-fns]))}))

;;; Queries

(defn- rsc-names [table-name sgl-or-plr]
  (let [bare (if (= :singular sgl-or-plr)
               (inf/singular table-name)
               (inf/plural table-name))
        pascal (csk/->PascalCase bare)]
    {:bare bare
     :bare-key (keyword bare)
     :pascal pascal
     :pascal-key (keyword pascal)}))

(defn- rsc-obj-keys [bare]
  (let [obj-name (csk/->PascalCase (inf/singular bare))]
    {:clauses (keyword (str obj-name "Clauses"))
     :aggregate (keyword (str obj-name "Aggregate"))
     :where (keyword (str obj-name "Where"))
     :sort (keyword (str obj-name "Sort"))
     :fields (keyword (str obj-name "Fields"))}))

(defn- assoc-queries [schema table obj-fields rel-ctx config]
  (let [table-name (:name table)
        table-key (keyword table-name)
        sgl-names (rsc-names table-name :singular)
        plr-names (rsc-names table-name :plural)
        obj-keys (rsc-obj-keys (:bare sgl-names))
        entity-schema (-> schema
        (assoc-in [:objects (:pascal-key sgl-names)]
                  {:description (:pascal sgl-names)
                   :fields obj-fields})
        (assoc-in [:input-objects (:clauses obj-keys)]
                  {:description fld/clause-desc
                   :fields (fld/clause-fields table)})
        (assoc-in [:input-objects (:where obj-keys)]
                  {:description fld/where-desc
                   :fields (fld/where-fields table (:clauses obj-keys))})
        (assoc-in [:input-objects (:sort obj-keys)]
                  {:description fld/sort-desc
                   :fields (fld/sort-fields table)})

        (assoc-in [:queries (:bare-key plr-names)]
                  {:type `(~'list ~(:pascal-key sgl-names))
                   :description (str "Query " (:pascal plr-names) ".")
                   :args {:where {:type (:where obj-keys)}
                          :sort {:type (:sort obj-keys)}
                          :limit {:type 'Int}
                          :offset {:type 'Int}}
                   :resolve (partial rslv/list-query table-key
                                     (tbl/col-key-set table)
                                     (get-in rel-ctx [:columns table-name])
                                     (get-in rel-ctx [:fields table-name])
                                     (signal-per-op config table-key :query))}))]
    (if (:use-aggregation config)
      (-> entity-schema
          (assoc-in [:objects (:fields obj-keys)]
                    {:description (:pascal sgl-names)
                     :fields obj-fields})
          (assoc-in [:objects (:aggregate obj-keys)]
                    {:description (:pascal sgl-names)
                     :fields (fld/aggr-fields (:fields obj-keys))})
          (assoc-in [:queries (keyword (str (:bare plr-names) "_aggregate"))]
                    {:type (:aggregate obj-keys)
                     :description (str "Aggrecate " (:pascal plr-names) ".")
                     :args {:where {:type (:where obj-keys)}}
                     :resolve (partial rslv/aggregate-root
                                       table-key)}))
      entity-schema)))

;;; Mutations

(defn- assoc-mutations [schema table obj-fields config]
  (let [table-name (:name table)
        table-key (keyword table-name)
        sgl-names (rsc-names table-name :singular)
        rsc-pk-key (keyword (str (:pascal sgl-names) "Pks"))
        rsc-pk-input-key (keyword (str (:pascal sgl-names) "PkColumns"))
        pk-keys (tbl/pk-keys table)
        update-fields (fld/update-fields pk-keys obj-fields)
        pk-fields (fld/pk-fields pk-keys obj-fields)
        col-keys (tbl/col-key-set table)]
    (-> schema
        (assoc-in [:objects rsc-pk-key]
                  {:description fld/pk-desc
                   :fields pk-fields})
        (assoc-in [:input-objects rsc-pk-input-key]
                  {:description fld/pk-desc
                   :fields pk-fields})

        (assoc-in [:mutations (keyword (str "create" (:pascal sgl-names)))]
                  {:type rsc-pk-key
                   ;; Assumption: `id` column is auto-generated on DB side
                   :args (dissoc obj-fields :id)
                   :resolve (partial rslv/create-root table-key pk-keys col-keys
                                     (signal-per-op config table-key :create))})
        (assoc-in [:mutations (keyword (str "update" (:pascal sgl-names)))]
                  {:type :Result
                   :args (assoc update-fields :pk_columns
                                {:type `(~'non-null ~rsc-pk-input-key)})
                   :resolve (partial rslv/update-root table-key col-keys
                                     (signal-per-op config table-key :update))})
        (assoc-in [:mutations (keyword (str "delete" (:pascal sgl-names)))]
                  {:type :Result
                   :args {:pk_columns
                          {:type `(~'non-null ~rsc-pk-input-key)}}
                   :resolve
                   (partial rslv/delete-root table-key
                            (signal-per-op config table-key :delete))}))))

;;; GraphQL schema

(defn- root-schema [config rel-ctx]
  (reduce (fn [m table]
            (let [obj-fields (fld/rsc-fields table)]
              (-> m
                  (assoc-queries table obj-fields rel-ctx config)
                  (assoc-mutations table obj-fields config))))
          {:enums fld/sort-op-enum
           :input-objects fld/filter-input-objects
           :objects fld/result-object
           :queries {}}
          (:tables config)))

(defn- fk-context [fk]
  (let [to-table-name (:table fk)]
    {:to {:column-key (keyword (:to fk))
          :table-name to-table-name
          :table-key (keyword to-table-name)}
     :from {:column-key (keyword (:from fk))}}))

(defn- update-fk-schema [schema table rel-ctx config]
  (let [table-name (:name table)
        table-key (keyword table-name)
        sgl-names (rsc-names table-name :singular)
        input-keys (rsc-obj-keys (:bare sgl-names))
        rsc-sgnl-map (signal-per-op config table-key :query)]
    (reduce
     (fn [m fk]
       (let [fk-ctx (fk-context fk)
             fk-to-tbl-name (:table fk)
             fk-to-tbl-key (keyword fk-to-tbl-name)
             fk-sgl-names (rsc-names fk-to-tbl-name :singular)
             fk-q-keys (tbl/fk-query-keys table fk)
             fk-to-table (tbl/table-by-name fk-to-tbl-name (:tables config))]
         (cond-> m
           ;; has-many on linked tables
           true (assoc-in
                 [:objects (:pascal-key fk-sgl-names) :fields
                  (get-in fk-q-keys [:has-many :field-key])]
                 {:type `(~'list ~(:pascal-key sgl-names))
                  :args {:where {:type (:where input-keys)}
                         :sort {:type (:sort input-keys)}
                         :limit {:type 'Int}
                         :offset {:type 'Int}}
                  :resolve (partial
                            rslv/has-many table-key
                            (get-in fk-ctx [:from :column-key])
                            (get-in fk-ctx [:to :column-key])
                            (tbl/pk-keys table)
                            (get-in fk-q-keys [:has-many :field-key])
                            (tbl/col-key-set table)
                            (get-in rel-ctx [:columns table-name])
                            (get-in rel-ctx [:fields table-name])
                            rsc-sgnl-map)})
           ;; has-many aggregate on linked tables
           (:use-aggregation config)
           (assoc-in
            [:objects (:pascal-key fk-sgl-names) :fields
             (get-in fk-q-keys [:has-many :aggregate-key])]
            {:type (:aggregate input-keys)
             :args {:where {:type (:where input-keys)}}
             :resolve
             (partial rslv/aggregate-has-many
                      (get-in fk-ctx [:from :column-key]) table-key
                      (get-in fk-q-keys [:has-many :aggregate-key])
                      rsc-sgnl-map)})
           ;; has-one on fk origin tables
           true (assoc-in
                 [:objects (:pascal-key sgl-names) :fields
                  (get-in fk-q-keys [:has-one :field-key])]
                 {:type (:pascal-key fk-sgl-names)
                  :resolve
                  (partial
                   rslv/has-one fk-to-tbl-key
                   (get-in fk-ctx [:from :column-key])
                   (get-in fk-ctx [:to :column-key])
                   (get-in fk-q-keys [:has-one :field-key])
                   (tbl/col-key-set fk-to-table)
                   (get-in rel-ctx [:columns fk-to-tbl-name])
                   (get-in rel-ctx [:fields fk-to-tbl-name])
                   (signal-per-op config fk-to-tbl-key :query))}))))
     schema (:fks table))))

(defn- update-relationships [schema config rel-ctx]
  (reduce (fn [m table]
            (update-fk-schema m table rel-ctx config))
          schema (:tables config)))

(defn schema [config]
  (let [rel-ctx (tbl/relation-context config)
        scm-map (-> (root-schema config rel-ctx)
                    (update-relationships config rel-ctx))]
    ;; (pp/pprint scm-map)
    (log :info "Generated queries: " (sort (keys (:queries scm-map))))
    (log :info "Generated mutations: " (sort (keys (:mutations scm-map))))
    (schema/compile scm-map)))

;;; Execution

(defn sl-config [config]
  (let [buckets (reduce (fn [m bucket-name]
                          (assoc m (keyword bucket-name)
                                 {:triggers {:elastic {:threshold 0}}}))
                        {:default {:triggers {:elastic {:threshold 0}}}}
                        (tbl/relation-name-set config))]
    {:buckets buckets
     :urania-opts {:env {:db (:db config)}}}))

(defn- sl-start! [config]
  (sl-core/start! config))

(defn- sl-stop! [sl-ctx]
  (sl-core/stop! sl-ctx))

(defn exec [config sl-config schema query vars req]
  (let [sl-ctx (sl-start! sl-config)
        ctx (-> (:signal-ctx config {})
                (assoc :req req)
                (assoc :sl-ctx sl-ctx)
                (assoc :db (:db config)))
        res (lcn/execute schema query vars ctx)]
    (let [_ctx (sl-stop! sl-ctx)]
      res)))


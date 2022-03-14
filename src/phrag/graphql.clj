(ns phrag.graphql
  (:require [phrag.logging :refer [log]]
            [phrag.resolver :as rslv]
            [phrag.context :as ctx]
            [com.walmartlabs.lacinia :as lcn]
            [com.walmartlabs.lacinia.schema :as schema]
            [clojure.pprint :as pp]
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

(defn- assoc-object [scm table obj-type obj-key]
  (assoc-in scm [obj-type (get-in table [:lcn-obj-keys obj-key])]
            {:description (get-in table [:lcn-descs obj-key])
             :fields (get-in table [:lcn-fields obj-key])}))

(defn- assoc-queries [schema table-key table config]
  (assoc-in schema [:queries (get-in table [:lcn-qry-keys :queries])]
            {:type `(~'list ~(get-in table [:lcn-obj-keys :rsc]))
             :description (get-in table [:lcn-descs :query])
             :args {:where {:type (get-in table [:lcn-obj-keys :where])}
                    :sort {:type (get-in table [:lcn-obj-keys :sort])}
                    :limit {:type 'Int}
                    :offset {:type 'Int}}
             :resolve (partial
                       rslv/list-query table-key (:col-keys table)
                       (:rel-cols table) (:rel-flds table)
                       (signal-per-op config table-key :query))}))

(defn- assoc-aggregation [schema table-key table]
  (assoc-in schema [:queries (get-in table [:lcn-qry-keys :aggregate])]
            {:type (get-in table [:lcn-obj-keys :aggregate])
             :description (get-in table [:lcn-descs :aggregate])
             :args {:where {:type (get-in table [:lcn-obj-keys :where])}}
             :resolve (partial rslv/aggregate-root table-key)}))

(defn- assoc-query-objects [schema table-key table config]
  (let [entity-schema (-> schema
                          (assoc-object table :objects :rsc)
                          (assoc-object table :input-objects :clauses)
                          (assoc-object table :input-objects :where)
                          (assoc-object table :input-objects :sort)
                          (assoc-queries table-key table config))]
    (if (:use-aggregation config)
      (-> entity-schema
          (assoc-object table :objects :fields)
          (assoc-object table :objects :aggregate)
          (assoc-aggregation table-key table))
      entity-schema)))

;;; Mutations

(defn- assoc-create-mutation [schema table-key table config]
  (assoc-in schema [:mutations (get-in table [:lcn-mut-keys :create])]
            {:type (get-in table [:lcn-obj-keys :pks])
             ;; Assumption: `id` column is auto-generated on DB side
             :args (dissoc (get-in table [:lcn-fields :rsc]) :id)
             :resolve (partial rslv/create-root table-key (:pk-keys table)
                               (:col-keys table)
                               (signal-per-op config table-key :create))}))

(defn- assoc-update-mutation [schema table-key table config]
  (assoc-in schema [:mutations (get-in table [:lcn-mut-keys :update])]
            {:type :Result
             :args (assoc (get-in table [:lcn-fields :update])
                          :pk_columns
                          {:type `(~'non-null
                                   ~(get-in table [:lcn-obj-keys :pk-input]))})
             :resolve (partial rslv/update-root table-key (:col-keys table)
                               (signal-per-op config table-key :update))}))

(defn- assoc-delete-mutation [schema table-key table config]
  (assoc-in schema [:mutations (get-in table [:lcn-mut-keys :delete])]
            {:type :Result
             :args {:pk_columns
                    {:type `(~'non-null
                             ~(get-in table [:lcn-obj-keys :pk-input]))}}
             :resolve (partial rslv/delete-root table-key
                               (signal-per-op config table-key :delete))}))

(defn- assoc-mutation-objects [schema table-key table config]
  (-> schema
        (assoc-object table :objects :pks)
        (assoc-object table :input-objects :pk-input)
        (assoc-create-mutation table-key table config)
        (assoc-update-mutation table-key table config)
        (assoc-delete-mutation table-key table config)))

;;; Relationships

(defn- assoc-has-many
  "Updates fk-destination object with a has-many resource field."
  [schema table-key table fk config]
  (assoc-in schema [:objects (get-in fk [:field-keys :to])
                    :fields (get-in fk [:field-keys :has-many])]
            {:type `(~'list ~(get-in table [:lcn-obj-keys :rsc]))
             :args {:where {:type (get-in table [:lcn-obj-keys :where])}
                    :sort {:type (get-in table [:lcn-obj-keys :sort])}
                    :limit {:type 'Int}
                    :offset {:type 'Int}}
             :resolve
             (partial rslv/has-many table-key (:from-key fk) (:to-key fk)
                      (:pk-keys table) (get-in fk [:field-keys :has-many])
                      (:col-keys table) (:rel-cols table) (:rel-flds table)
                      (signal-per-op config table-key :query))}))

(defn- assoc-has-many-aggregate
  "Updates fk-destination object with a has-many aggregation field."
  [schema table-key table fk config]
  (assoc-in schema [:objects (get-in fk [:field-keys :to])
                    :fields (get-in fk [:field-keys :has-many-aggr])]
            {:type (get-in table [:lcn-obj-keys :aggregate])
             :args {:where {:type (get-in table [:lcn-obj-keys :where])}}
             :resolve
             (partial rslv/aggregate-has-many table-key (:from-key fk)
                      (:to-key fk) (get-in fk [:field-keys :has-many-aggr])
                      (signal-per-op config table-key :query))}))

(defn- assoc-has-one
  "Updates fk-origin object with a has-one object field."
  [schema table fk config]
  (assoc-in schema  [:objects (get-in table [:lcn-obj-keys :rsc])
                     :fields (get-in fk [:field-keys :has-one])]
            {:type (get-in fk [:field-keys :to])
             :resolve
             (partial rslv/has-one (:to-tbl-key fk) (:from-key fk) (:to-key fk)
                      (get-in fk [:field-keys :has-one]) (:to-tbl-col-keys fk)
                      (:to-tbl-rel-cols fk) (:to-tbl-rel-flds fk)
                      (signal-per-op config (:to-tbl-key fk) :query))}))

;;; GraphQL schema

(defn- root-schema [config]
  (reduce-kv (fn [m table-key table]
               (-> m
                   (assoc-query-objects table-key table config)
                   (assoc-mutation-objects table-key table config)))
          ctx/init-schema
          (:tables config)))

(defn- update-fk-schema [schema table-key table config]
  (reduce-kv (fn [m _from-key fk]
               (cond-> m
                 true (assoc-has-many table-key table fk config)
                 (:use-aggregation config)
                 (assoc-has-many-aggregate table-key table fk config)
                 true (assoc-has-one table fk config)))
             schema (:fks table)))

(defn- update-relationships [schema config]
  (reduce-kv (fn [m table-key table]
            (update-fk-schema m table-key table config))
          schema
          (:tables config)))

(defn schema [config]
  (let [scm-map (-> (root-schema config)
                    (update-relationships config))]
    (log :info "Generated queries: " (sort (keys (:queries scm-map))))
    (log :info "Generated mutations: " (sort (keys (:mutations scm-map))))
    (schema/compile scm-map)))

;;; Execution

(defn- sl-start! [config]
  (sl-core/start! config))

(defn- sl-stop! [sl-ctx]
  (sl-core/stop! sl-ctx))

(defn exec [config schema query vars req]
  (let [sl-ctx (sl-start! (:sl-config config))
        ctx (-> (:signal-ctx config {})
                (assoc :req req)
                (assoc :sl-ctx sl-ctx)
                (assoc :db (:db config)))
        res (lcn/execute schema query vars ctx)]
    (let [_ctx (sl-stop! sl-ctx)]
      res)))

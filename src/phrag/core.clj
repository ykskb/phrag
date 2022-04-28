(ns phrag.core
  "Creation and execution of Phrag's GraphQL schema through Lacinia."
  (:require [phrag.logging :refer [log]]
            [phrag.resolver :as rslv]
            [phrag.context :as ctx]
            [com.walmartlabs.lacinia :as lcn]
            [com.walmartlabs.lacinia.tracing :as trc]
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
  (let [{{:keys [queries]} :lcn-qry-keys
         {:keys [rsc where sort]} :lcn-obj-keys
         {:keys [query]} :lcn-descs} table]
    (assoc-in schema [:queries queries]
              {:type `(~'list ~rsc)
               :description query
               :args {:where {:type where}
                      :sort {:type sort}
                      :limit {:type 'Int}
                      :offset {:type 'Int}}
               :resolve (partial rslv/list-query table-key table
                                 (signal-per-op config table-key :query))})))

(defn- assoc-aggregation [schema table-key table]
  (let [{{:keys [aggregate where]} :lcn-obj-keys} table]
    (assoc-in schema [:queries (get-in table [:lcn-qry-keys :aggregate])]
              {:type aggregate
               :description (get-in table [:lcn-descs :aggregate])
               :args {:where {:type where}}
               :resolve (partial rslv/aggregate-root table-key)})))

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
  (let [{{:keys [create]} :lcn-mut-keys
         {:keys [pks]} :lcn-obj-keys
         {:keys [rsc]} :lcn-fields} table]
    (assoc-in schema [:mutations create]
              {:type pks
               ;; Assumption: `id` column is auto-generated on DB side
               :args (dissoc rsc :id)
               :resolve (partial rslv/create-root table-key table
                                 (signal-per-op config table-key :create))})))

(defn- assoc-update-mutation [schema table-key table config]
  (let [{{:keys [update]} :lcn-fields
         {:keys [pk-input]} :lcn-obj-keys} table]
    (assoc-in schema [:mutations (get-in table [:lcn-mut-keys :update])]
              {:type :Result
               :args (assoc update :pk_columns {:type `(~'non-null ~pk-input)})
               :resolve (partial rslv/update-root table-key (:col-keys table)
                                 (signal-per-op config table-key :update))})))

(defn- assoc-delete-mutation [schema table-key table config]
  (let [{{:keys [delete]} :lcn-mut-keys
         {:keys [pk-input]} :lcn-obj-keys} table]
    (assoc-in schema [:mutations delete]
              {:type :Result
               :args {:pk_columns {:type `(~'non-null ~pk-input)}}
               :resolve (partial rslv/delete-root table-key
                                 (signal-per-op config table-key :delete))})))

(defn- assoc-mutation-objects [schema table-key table config]
  (-> schema
      (assoc-object table :objects :pks)
      (assoc-object table :input-objects :pk-input)
      (assoc-create-mutation table-key table config)
      (assoc-update-mutation table-key table config)
      (assoc-delete-mutation table-key table config)))

;;; Relationships

(defn- assoc-has-one
  "Updates fk-origin object with a has-one object field."
  [schema table fk config]
  (let [{{:keys [has-one]} :field-keys} fk
        {{:keys [rsc]} :lcn-obj-keys} table]
    (assoc-in schema  [:objects rsc :fields has-one]
              {:type (get-in fk [:field-keys :to])
               :resolve
               (partial rslv/has-one fk
                        (signal-per-op config (:to-tbl-key fk) :query))})))

(defn- assoc-has-many
  "Updates fk-destination object with a has-many resource field."
  [schema table-key table fk config]
  (let [{{:keys [to has-many]} :field-keys} fk
        {{:keys [rsc where sort]} :lcn-obj-keys} table]
    (assoc-in schema [:objects to :fields has-many]
              {:type `(~'list ~rsc)
               :args {:where {:type where}
                      :sort {:type sort}
                      :limit {:type 'Int}
                      :offset {:type 'Int}}
               :resolve (partial rslv/has-many table-key table fk
                                 (signal-per-op config table-key :query))})))

(defn- assoc-has-many-aggregate
  "Updates fk-destination object with a has-many aggregation field."
  [schema table-key table fk config]
  (let [{{:keys [to has-many-aggr]} :field-keys} fk
        {{:keys [aggregate where]} :lcn-obj-keys} table]
    (assoc-in schema [:objects to :fields has-many-aggr]
              {:type aggregate
               :args {:where {:type where}}
               :resolve (partial rslv/aggregate-has-many table-key fk
                                 (signal-per-op config table-key :query))})))

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

(defn schema
  "Creates Phrag's GraphQL schema in Lacinia format."
  [config]
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

(defn exec
  "Executes Phrag's GraphQL."
  [config schema query vars req]
  (let [sl-ctx (sl-start! (:sl-config config))
        ctx (-> (:signal-ctx config {})
                (assoc :req req)
                (assoc :sl-ctx sl-ctx)
                (assoc :db (:db config))
                (assoc :default-limit (:default-limit config)))
        res (lcn/execute schema query vars ctx)]
        ;; res (lcn/execute schema query vars (trc/enable-tracing ctx))]
    (let [_ctx (sl-stop! sl-ctx)]
      res)))

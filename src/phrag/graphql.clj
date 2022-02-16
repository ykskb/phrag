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

(defn- signal-fn-map
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

;;; Queries

(defn- assoc-queries [schema table rsc-name table-key obj-fields
                      rel-map use-aggr sgnl-conf]
  (let [table-name (:name table)
        rscs (inf/plural table-name)
        rscs-name (csk/->PascalCase rscs)
        query-key (keyword rscs)
        rsc-name-key (keyword rsc-name)
        rsc-cls-key (csk/->PascalCaseKeyword (str rsc-name "Clauses"))
        rsc-whr-key (csk/->PascalCaseKeyword (str rsc-name "Where"))
        rsc-sort-key (csk/->PascalCaseKeyword (str rsc-name "Sort"))
        rels (get rel-map table-name)
        sgnl-map (signal-fn-map sgnl-conf table-key :query)
        entity-schema (-> schema
        (assoc-in [:objects rsc-name-key]
                  {:description rsc-name
                   :fields obj-fields})
        (assoc-in [:input-objects rsc-cls-key]
                  {:description fld/clause-desc
                   :fields (fld/clause-fields table)})
        (assoc-in [:input-objects rsc-whr-key]
                  {:description fld/where-desc
                   :fields (fld/where-fields table rsc-cls-key)})
        (assoc-in [:input-objects rsc-sort-key]
                  {:description fld/sort-desc
                   :fields (fld/sort-fields table)})

        (assoc-in [:queries query-key]
                  {:type `(~'list ~rsc-name-key)
                   :description (str "Query " rscs-name ".")
                   :args {:where {:type rsc-whr-key}
                          :sort {:type rsc-sort-key}
                          :limit {:type 'Int}
                          :offset {:type 'Int}}
                   :resolve (partial rslv/list-query
                                     table-key rels sgnl-map)}))]
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
                       :fields (fld/aggr-fields rsc-fields-key)})
            (assoc-in [:queries aggr-q-key]
                      {:type rsc-aggr-key
                       :description (str "Aggrecate " rscs-name ".")
                       :args {:where {:type rsc-whr-key}}
                       :resolve (partial rslv/aggregate-root
                                         table-key)})))
      entity-schema)))

;;; Mutations

(defn- assoc-mutations [schema table rsc-name table-key obj-fields sgnl-conf]
  (let [create-key (keyword (str "create" rsc-name))
        update-key (keyword (str "update" rsc-name))
        delete-key (keyword (str "delete" rsc-name))
        rsc-pk-key (csk/->PascalCaseKeyword (str rsc-name "Pks"))
        rsc-pk-input-key (csk/->PascalCaseKeyword (str rsc-name "PkColumns"))
        pk-keys (map #(keyword (:name %)) (:pks table))
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

            (assoc-in [:mutations create-key]
                      {:type rsc-pk-key
                       ;; Assumption: `id` column is auto-generated on DB side
                       :args (dissoc obj-fields :id)
                       :resolve (partial rslv/create-root table-key pk-keys
                                         col-keys (signal-fn-map
                                                   sgnl-conf table-key :create))})
            (assoc-in [:mutations update-key]
                      {:type :Result
                       :args
                       (assoc update-fields :pk_columns
                              {:type `(~'non-null ~rsc-pk-input-key)})
                       :resolve
                       (partial rslv/update-root table-key col-keys
                                (signal-fn-map sgnl-conf table-key :update))})
            (assoc-in [:mutations delete-key]
                      {:type :Result
                       :args {:pk_columns
                              {:type `(~'non-null ~rsc-pk-input-key)}}
                       :resolve
                       (partial rslv/delete-root table-key
                                (signal-fn-map sgnl-conf table-key :delete))}))))

;;; GraphQL schema

(defn- root-schema [config rel-map]
  (reduce (fn [m table]
            (let [table-name (:name table)
                  table-key (keyword table-name)
                  rsc (inf/singular table-name)
                  rsc-name (csk/->PascalCase rsc)
                  obj-fields (fld/rsc-fields table)
                  use-aggr (:use-aggregation config)
                  sgnl-conf (:signals config)]
              (-> m
                  (assoc-queries table rsc-name table-key obj-fields
                                 rel-map use-aggr sgnl-conf)
                  (assoc-mutations table rsc-name table-key
                                   obj-fields sgnl-conf))))
          {:enums fld/sort-op-enum
           :input-objects fld/filter-input-objects
           :objects {:Result {:fields {:result {:type 'Boolean}}}}
           :queries {}} (:tables config)))

(defn- update-fk-schema [schema table rel-map use-aggr sgnl-conf]
  (let [table-name (:name table)
        table-key (keyword table-name)
        rsc (inf/singular table-name)
        rsc-name (csk/->PascalCase rsc)
        rsc-name-key (keyword rsc-name)
        pk-keys (map #(keyword (:name %)) (:pks table))
        rsc-aggr-key (keyword (str rsc-name "Aggregate"))
        rsc-whr-key (keyword (str rsc-name "Where"))
        rsc-sort-key (keyword (str rsc-name "Sort"))
        rsc-rels (get rel-map table-name)
        rsc-sgnl-map (signal-fn-map sgnl-conf table-key :query)]
    (reduce (fn [m fk]
              (let [fk-to-tbl-name (:table fk)
                    fk-to-tbl-key (keyword fk-to-tbl-name)
                    fk-to-rsc (inf/singular fk-to-tbl-name)
                    fk-to-rsc-name (csk/->PascalCase fk-to-rsc)
                    fk-to-rsc-name-key (keyword fk-to-rsc-name)
                    fk-from-rsc-col (keyword (:from fk))
                    fk-to-rels (get rel-map fk-to-tbl-name)
                    has-many-fld-key (tbl/has-many-field-key table fk)
                    has-many-aggr-key (keyword (str (name has-many-fld-key)
                                                    "_aggregate"))
                    has-one-fld-key (tbl/has-one-field-key fk)]
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
                                           pk-keys table-key has-many-fld-key
                                           rsc-rels rsc-sgnl-map)})
                  ;; has-many aggregate on linked tables
                  use-aggr (assoc-in
                            [:objects fk-to-rsc-name-key :fields
                             has-many-aggr-key]
                            {:type rsc-aggr-key
                             :args {:where {:type rsc-whr-key}}
                             :resolve
                             (partial rslv/aggregate-has-many
                                      fk-from-rsc-col table-key has-many-aggr-key
                                      rsc-sgnl-map)})
                  ;; has-one on fk origin tables
                  true (assoc-in
                        [:objects rsc-name-key
                         :fields has-one-fld-key]
                        {:type fk-to-rsc-name-key
                         :resolve
                         (partial rslv/has-one fk-from-rsc-col
                                  fk-to-tbl-key has-one-fld-key fk-to-rels
                                  (signal-fn-map sgnl-conf fk-to-tbl-key :query))}))))
            schema (:fks table))))

(defn- update-relationships [schema config rel-map]
  (reduce (fn [m table]
            (update-fk-schema m table rel-map
                              (:use-aggregation config)
                              (:signals config)))
          schema (:tables config)))

(defn schema [config]
  (let [rel-map (tbl/relation-map config)
        scm-map (-> (root-schema config rel-map)
                    (update-relationships config rel-map))]
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


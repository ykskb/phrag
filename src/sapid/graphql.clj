(ns sapid.graphql
  (:require [sapid.table :as tbl]
            [sapid.handlers.core :as c]
            [com.walmartlabs.lacinia.util :as util]
            [com.walmartlabs.lacinia.schema :as schema]
            [clojure.pprint :as pp]
            [inflections.core :as inf]))

(def ^:private field-types
  {"int" 'Int
   "integer" 'Int
   "text" 'String
   "timestamp" 'String})

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

(defn- resolve-list-query [db table ctx args val]
  (c/list-root nil db table nil))

(defn- resolve-id-query [db table ctx args val]
  (c/fetch-root (:id args) nil db table nil))

(defn- resolve-has-many [id-key db table ctx args val]
  (c/list-root {id-key (:id val)} db table #{id-key}))

(defn resolve-has-one [id-key db table ctx args val]
  (c/fetch-root (id-key val) nil db table nil))

(defn resolve-nn [join-col p-col db nn-table table ctx args val]
  (c/list-n-n join-col p-col (:id val) nil db nn-table table nil))

(defn- root-schema [config]
  (reduce (fn [m table]
            (let [table-name (tbl/to-table-name (:name table) config)
                  rsc-name (inf/singular table-name)
                  rscs-name (inf/plural table-name)
                  rsc-key (keyword rsc-name)
                  id-q-key (keyword rsc-name)
                  list-q-key (keyword rscs-name)
                  db (:db config)]
              (if (has-rel-type? :root table)
                (-> m
                    (assoc-in [:objects rsc-key]
                              {:description rsc-name
                               :fields (root-fields table)})
                    (assoc-in [:queries list-q-key]
                              {:type `(~'list ~rsc-key)
                               :description (str "List " rsc-name ".")
                               :resolve (partial resolve-list-query db table-name)})
                    (assoc-in [:queries id-q-key]
                              {:type rsc-key
                               :description (str "Query " rsc-name " by id.")
                               :args {:id {:type '(non-null ID)}}
                               :resolve (partial resolve-id-query db table-name)}))
                m)))
          {:objects {} :queries {} :resolvers {}} (:tables config)))

(defn- add-one-n-schema [schema config table]
  (let [table-name (tbl/to-table-name (:name table) config)
        rsc-name (inf/singular table-name)
        rsc-key (keyword rsc-name)
        rscs-name (inf/plural table-name)
        rscs-key (keyword rscs-name)
        db (:db config)]
    (reduce (fn [m blg-to]
              (let [blg-to-rsc-name (inf/singular blg-to)
                    blg-to-rsc-key (keyword blg-to-rsc-name)
                    blg-to-rsc-id (keyword (str blg-to-rsc-name "_id"))
                    blg-to-rscs-name (inf/plural blg-to)
                    blg-to-rscs-key (keyword blg-to-rscs-name)
                    blg-to-table-name (tbl/to-table-name blg-to-rsc-name config)]
                (-> m
                    ;; has many
                    (assoc-in [:objects blg-to-rsc-key :fields rscs-key]
                              {:type `(~'list ~rsc-key)
                               :resolve (partial resolve-has-many blg-to-rsc-id db table-name)})
                    ;; has one
                    (assoc-in [:objects rsc-key :fields blg-to-rsc-key]
                              {:type blg-to-rsc-key
                               :resolve (partial resolve-has-one blg-to-rsc-id
                                                 db blg-to-table-name)}))))
            schema (:belongs-to table))))

(defn- add-n-n-schema [schema config table]
  (let [tbl-name (tbl/to-table-name (:name table) config)
        rsc-a-tbl-name (first (:belongs-to table))
        rsc-b-tbl-name (second (:belongs-to table))
        rsc-a-name (inf/singular rsc-a-tbl-name)
        rsc-b-name (inf/singular rsc-b-tbl-name)
        rsc-a-key (keyword rsc-a-name)
        rsc-b-key (keyword rsc-b-name)
        rsc-a-col (str rsc-a-name "_id")
        rsc-b-col (str rsc-b-name "_id")
        rscs-a-name (inf/plural rsc-a-tbl-name)
        rscs-b-name (inf/plural rsc-b-tbl-name)
        rscs-a-key (keyword rscs-a-name)
        rscs-b-key (keyword rscs-b-name)
        db (:db config)]
    (-> schema
        (assoc-in [:objects rsc-a-key :fields rscs-b-key]
                  {:type `(~'list ~rsc-b-key)
                   :resolve (partial resolve-nn rsc-b-col rsc-a-col db
                                     tbl-name rsc-b-tbl-name)})
        (assoc-in [:objects rsc-b-key :fields rscs-a-key]
                  {:type `(~'list ~rsc-a-key)
                   :resolve (partial resolve-nn rsc-a-col rsc-b-col
                                     db tbl-name rsc-a-tbl-name)}))))

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


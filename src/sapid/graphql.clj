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

(defn- resolve-id-query [db table ctx args val]
  (c/fetch-root (:id args) nil db table nil))

(defn- resolve-has-many [id-key db table ctx args val]
  (c/list-root {id-key (:id val)} db table #{id-key}))

(defn resolve-has-one [id-key db table ctx args val]
  (c/fetch-root (id-key val) nil db table nil))

(defn- root-schema [config]
  (reduce (fn [m table]
            (let [table-name (:name table)
                  rsc-name (inf/singular table-name)
                  rsc-key (keyword rsc-name)
                  id-q-key (keyword (str rsc-name "_by_id"))
                  id-q-rslv-key (keyword "query" (str rsc-name "-by-id"))
                  db (:db config)]
              (if (has-rel-type? :root table)
                (-> m
                    (assoc-in [:objects rsc-key]
                              {:description rsc-name
                               :fields (root-fields table)})
                    (assoc-in [:queries id-q-key]
                              {:type rsc-key
                               :description (str "Query " rsc-name " by id.")
                               :args {:id {:type '(non-null ID)}}
                               :resolve id-q-rslv-key})
                    (assoc-in [:resolvers id-q-rslv-key]
                              (partial resolve-id-query db table-name)))
                m)))
          {:objects {} :queries {} :resolvers {}} (:tables config)))

(defn- add-belongs-to [schema table db]
  (let [table-name (:name table)
        rsc-name (inf/singular table-name)
        rsc-key (keyword rsc-name)
        rscs-name (inf/plural table-name)
        rscs-key (keyword rscs-name)]
    (reduce (fn [m blg-to]
              (let [blg-to-rsc-name (inf/singular blg-to)
                    blg-to-rsc-key (keyword blg-to-rsc-name)
                    blg-to-rsc-id (keyword (str blg-to-rsc-name "_id"))
                    blg-to-rscs-name (inf/plural blg-to)
                    blg-to-rscs-key (keyword blg-to-rscs-name)
                    rsc-rslv-key (keyword rsc-name blg-to-rsc-name)
                    blg-to-rscs-rslv-key (keyword blg-to-rsc-name rscs-name)]
                (-> m
                    ;; has many
                    (assoc-in [:objects blg-to-rsc-key :fields rscs-key]
                              {:type `(~'non-null (~'list ~rsc-key))
                               :resolve blg-to-rscs-rslv-key})
                    (assoc-in [:resolvers blg-to-rscs-rslv-key]
                              (partial resolve-has-many blg-to-rsc-id db table-name))
                    ;; has one
                    (assoc-in [:objects rsc-key :fields blg-to-rsc-key]
                              {:type `(~'non-null ~blg-to-rsc-key)
                               :resolve rsc-rslv-key})
                    (assoc-in [:resolvers rsc-rslv-key]
                              (partial resolve-has-one blg-to-rsc-id db table-name)))))
            schema (:belongs-to table))))

(defn- add-one-n-schema [config schema]
  (reduce (fn [m table]
            (if (has-rel-type? :one-n table)
              (add-belongs-to m table (:db config))
              m))
          schema (:tables config)))

(defn- add-n-n-schema [config schema]
  schema)

(defn- schema-with-resolver [config]
  (->> (root-schema config)
       (add-one-n-schema config)
       (add-n-n-schema config)))

(defn schema [config]
  (let [schema-w-resolvers (schema-with-resolver config)]
    (pp/pprint schema-w-resolvers)
  (-> (dissoc schema-w-resolvers :resolvers)
      (util/attach-resolvers (:resolvers schema-w-resolvers))
      schema/compile)))


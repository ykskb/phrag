(ns sapid.table
  (:require [clojure.string :as s]
            [sapid.db :as db]
            [inflections.core :as inf]))

;; table-related utils

(defn to-table-name [rsc config]
  (if (:table-name-plural config) (inf/plural rsc) (inf/singular rsc)))

(defn to-path-rsc [rsc config]
  (if (:resource-path-plural config) (inf/plural rsc) (inf/singular rsc)))

(defn to-col-name [rsc]
  (str (inf/singular rsc) "_id"))

;;; table schema from database

(defn- is-relation-column? [name]
  (s/ends-with? (s/lower-case name) "_id"))

(defn- has-relation-column? [table]
  (some (fn [column] (is-relation-column? (:name column)))
        (:columns table)))

(defn- is-n-n-table? [table]
  (s/includes? (:name table) "_"))

(defn- n-n-belongs-to [table]
  (let [table-name (:name table)
        parts (s/split table-name #"_" 2)]
    [(first parts) (second parts)]))

(defn- belongs-to [table]
  (reduce (fn [v column]
            (let [name (:name column)]
              (if (is-relation-column? name)
                (conj v (s/replace name "_id" ""))
                v)))
          []
          (:columns table)))

(defn- relation-types [table]
  (if (is-n-n-table? table) [:n-n]
      (if (has-relation-column? table) [:one-n :root] [:root])))

(defn schema-from-db [db]
  (map (fn [table]
         (let [is-n-n (is-n-n-table? table)]
           (cond-> table
             true (assoc :relation-types (relation-types table))
             (not is-n-n) (assoc :belongs-to (belongs-to table))
             is-n-n (assoc :belongs-to (n-n-belongs-to table)))))
       (db/get-db-schema db)))


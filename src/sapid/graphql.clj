(ns sapid.graphql
  (:require [sapid.table :as tbl]))

(defn- has-rels [table config]
  (let [table-name (:name table)]
    (reduce (fn [m blg-to]
              (assoc m (tbl/to-table-name blg-to config) table-name)))))

(defn- has-rel-map [tables config]
  (reduce (fn [m table]
            (merge-with into m (has-rels table config)))
          {}
          tables))


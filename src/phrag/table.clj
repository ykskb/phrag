(ns phrag.table
  (:require [clojure.string :as s]
            [clojure.pprint :as pp]
            [phrag.db :as db]
            [phrag.logging :refer [log]]
            [inflections.core :as inf]))

;; Table utils

(defn to-table-name [rsc config]
  (if (:table-name-plural config) (inf/plural rsc) (inf/singular rsc)))

(defn to-col-name [rsc]
  (str (inf/singular rsc) "_id"))

(defn col-names [table]
  (set (map :name (:columns table))))

(defn primary-fks [table]
  (vals (select-keys (:fk-map table) (keys (:pk-map table)))))

;;; Table full relationship map

(defn- rels [table config]
  (let [table-name (:name table)
        rel-map {table-name (map (fn [blg-to]
                                   (to-table-name (:table blg-to) config))
                                 (:fks table))}]
    (reduce (fn [m blg-to]
              (assoc m (to-table-name (:table blg-to) config) [table-name]))
            rel-map (:fks table))))

(defn full-rel-map [config]
  (reduce (fn [m table]
            (let [rels (rels table config)]
              (merge-with into m rels)))
          {} (:tables config)))

;;; Table schema from database

(defn- fks-by-names [table config]
  (reduce (fn [v column]
            (let [col-name (:name column)]
              (if (s/ends-with? (s/lower-case col-name) "_id")
                (conj v
                      {:table (to-table-name (s/replace col-name "_id" "") config)
                       :from col-name :to "id"})
                v)))
          []
          (:columns table)))

(defn- update-fks-by-names [tables config]
  (map (fn [table]
         (let [fks (fks-by-names table config)]
           (println table)
           (-> table
               (assoc :fks fks)
               (assoc :fk-map (zipmap (map :from fks) fks)))))
       tables))

(defn- is-pivot-table? [table]
  (and (> (count (:pks table)) 1)
       (let [fk-names (set (keys (:fk-map table)))
             pk-names (keys (:pk-map table))]
         (every? #(contains? fk-names %) pk-names))))

(defn- table-type [table]
  (if (is-pivot-table? table) :pivot :root))

(defn- update-table-types [tables]
  (map #(assoc % :table-type (table-type %)) tables))

(defn- update-info-maps [tables]
  (map (fn [table]
         (let [cols (:columns table)
               fks (:fks table)
               pks (:pks table)]
           (-> table
               (assoc :col-map (zipmap (map :name cols) cols))
               (assoc :fk-map (zipmap (map :from fks) fks))
               (assoc :pk-map (zipmap (map :name pks) pks)))))
       tables))

(defn- merge-config-tables [tables config]
  (let [cfg-tables (:tables config)
        cfg-tbl-names (set (map :name cfg-tables))
        tbl-names (set (map :name tables))
        cfg-tbl-map (zipmap cfg-tbl-names cfg-tables)
        merged (map (fn [table]
                      (merge table (get cfg-tbl-map (:name table))))
                    tables)
        cfg-tbl-diff (filter (fn [table]
                               (not (contains? tbl-names (:name table))))
                             cfg-tables)]
    (concat merged cfg-tbl-diff)))

(defn schema-from-db
  "By default foreign keys constraints in a database are used for relationships.
  Alternatively, `:no-fk-on-db` can be used to detect relations by table/column names,
  but it has limitations as tables and columns need to be matching."
  [config]
  (let [scm (if (:scan-schema config)
              (cond-> (db/schema (:db config))
                true (update-info-maps)
                (:no-fk-on-db config) (update-fks-by-names config)
                true (update-table-types)
                true (merge-config-tables config))
              (cond-> (:tables config)
                true (update-info-maps)
                (:no-fk-on-db config) (update-fks-by-names config)))]
    (log :info "Origin DB schema:\n" (with-out-str (pp/pprint scm)))
    scm))


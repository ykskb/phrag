(ns phrag.db.sqlite
  (:require [cheshire.core :as json]
            [clojure.string :as s]
            [honey.sql :as sql]
            [honey.sql.helpers :as h]
            [phrag.db.core :as core]))

(defn- aggr-param [op table-key selection]
  (map (fn [slct]
         (let [col (name (:field-name slct))]
           (format "'%s', %s(%s.%s)" col op (name table-key) col)))
       (:selections selection)))

(defn- aggr-params [table-key selection]
  (reduce (fn [v slct]
            (let [field-key (get-in slct [:field-definition :field-name])]
              (cond
                (= :count field-key) (conj v "'count', count(*)")
                (contains? core/aggr-keys field-key)
                (let [op (name field-key)
                      params (aggr-param op table-key slct)]
                  (conj v (format "'%s', JSON_OBJECT(%s)" op (s/join ", " params))))
                :else v)))
          nil
          (:selections selection)))

(defn- compile-aggr [table-key selection]
  (format "JSON_OBJECT(%s)" (s/join ", " (aggr-params table-key selection))))

(defn- build-json-param
  "TODO: extend honeysql for formatting JSON functions in SQLite.
  Inline format for subquery is unsafe since parametarization is off."
  [field-key select]
  (let [fmt "'%s', %s"]
    (cond
      (string? select) (format fmt (name field-key) select)
      (keyword? select) (format fmt (name field-key) (name select))
      (map? select) (format "'%s', (%s)" (name field-key)
                            (first (sql/format select {:inline true})))
      :else nil)))

(defn- concat-json-param [current param]
  (str (subs current 0 (- (.length current) 1)) ", " param ")"))

(defn- json-select [query field-key select]
  (if-let [param (build-json-param field-key select)]
    (if-let [current (get-in query [:select 0 0 1])]
      (assoc query :select [[[:raw (concat-json-param current param)] :data]])
      (h/select query [[:raw (format "JSON_OBJECT(%s)" param)] :data]))
    query))

(defn- compile-query [nest-level table-key selection ctx]
  (let [nest-fks (get-in ctx [:relation-ctx :nest-fks table-key])
        max-nest (:max-nest-level ctx)
        selection (core/signal selection table-key :query :pre ctx)]
    (reduce
     (fn [q slct]
       (let [field-key (get-in slct [:field-definition :field-name])]
         (if (:leaf? slct)
           (let [table-column (core/column-path-key table-key field-key)]
             (json-select q field-key table-column))
           (if (and (number? max-nest) (> nest-level max-nest))
             (throw (Exception. "Exceeded maximum nest level."))
             (let [nest-fk (field-key nest-fks)
                   nest-type (:type nest-fk)
                   {:keys [to from from-table table]} nest-fk]
               (case nest-type
                 :has-one
                 (let [c (compile-query (+ nest-level 1) table slct ctx)
                       on-clause [:= (core/column-path-key from-table from)
                                  (core/column-path-key table to)]]
                   (json-select q field-key (h/where c on-clause)))
                 :has-many
                 (let [c (compile-query (+ nest-level 1) from-table slct ctx)
                       on-clause [:= (core/column-path-key from-table from)
                                  (core/column-path-key table to)]]
                   (json-select q field-key
                                (-> (h/select
                                     [[:raw "JSON_GROUP_ARRAY(JSON(temp.data))"]])
                                    (h/from [(h/where c on-clause) :temp]))))
                 :has-many-aggr
                 (let [on-clause [:= (core/column-path-key from-table from)
                                  (core/column-path-key table to)]]
                   (json-select q field-key
                                (-> (h/select
                                     [[:raw (compile-aggr from-table slct)]])
                                    (h/from from-table)
                                    (h/where on-clause)
                                    (core/apply-args (:arguments slct) ctx))))))))))
     (-> (h/from table-key)
         (core/apply-args (:arguments selection) ctx))
     (:selections selection))))

(defn- json-array-cast [q]
  (-> (h/select [[:raw "JSON_GROUP_ARRAY(JSON(res.data))"] :result])
      (h/from [q :res])))

(defn- compile-aggregation [table-key selection ctx]
  (-> (h/select [[:raw "JSON(temp.data)"] :result])
      (h/from [(->(h/select [[:raw (compile-aggr table-key selection)] :data])
                  (h/from table-key)
                  (core/apply-args (:arguments selection) ctx)) :temp])))

(defrecord SqliteAdapter [db]
  core/DbAdapter

  (table-names [adpt]
    (core/exec-query (:db adpt) (str "SELECT name FROM sqlite_master "
                                "WHERE type = 'table' "
                                "AND name NOT LIKE 'sqlite%' "
                                "AND name NOT LIKE '%migration%';")))

  (view-names [adpt]
    (core/exec-query (:db adpt) (str "SELECT name FROM sqlite_master "
                                "WHERE type = 'view';")))

  (column-info [adpt table-name]
    (core/exec-query (:db adpt) (format "pragma table_info(%s);" table-name)))

  (foreign-keys [adpt table-name]
    (core/exec-query (:db adpt)
                     (format "pragma foreign_key_list(%s);" table-name)))

  (primary-keys [adpt table-name]
    (reduce (fn [v col]
              (if (> (:pk col) 0) (conj v col) v))
            [] (core/column-info adpt table-name)))

  (resolve-query [adpt table-key selection ctx]
    (let [query (compile-query 1 table-key selection ctx)
          res (core/exec-query (:db adpt) (sql/format (json-array-cast query)))]
      (prn (json/parse-string (:result (first res)) true))
      (json/parse-string (:result (first res)) true)))

  (resolve-aggregation [adpt table-key selection ctx]
    (let [query (compile-aggregation table-key selection ctx)
          res (core/exec-query (:db adpt) (sql/format query))]
      (prn (json/parse-string (:result (first res)) true))
      (json/parse-string (:result (first res)) true))))

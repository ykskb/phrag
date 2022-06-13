(ns phrag.db.postgres
  (:require [clojure.string :as s]
            [phrag.db.core :as core]
            [honey.sql :as sql]
            [honey.sql.helpers :as h]))

(defn- current-schema [db]
  (cond
    (:connection db) (.getSchema (:connection db))
    (:datasource db) (-> (.getDataSourceProperties (:datasource db))
                         (.getProperty "currentSchema"))
    :else "public"))

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
                  (conj v (format "'%s', JSON_BUILD_OBJECT(%s)" op
                                  (s/join ", " params))))
                :else v)))
          nil
          (:selections selection)))

(defn- compile-aggr [table-key selection]
  (format "JSON_BUILD_OBJECT(%s)" (s/join ", " (aggr-params table-key selection))))

(defn- compile-query [nest-level table-key selection ctx]
  (let [nest-fks (get-in ctx [:relation-ctx :nest-fks table-key])
        max-nest (:max-nest-level ctx)]
    (reduce
     (fn [q slct]
       (let [field-key (get-in slct [:field-definition :field-name])]
         (if (:leaf? slct)
           (h/select q (core/column-path-key table-key field-key))
           (if (and (number? max-nest) (> nest-level max-nest))
             (throw (Exception. "Exceeded maximum nest level."))
             (let [nest-fk (field-key nest-fks)
                   nest-type (:type nest-fk)
                   {:keys [to from from-table table]} nest-fk]
               (case nest-type
                 :has-one
                 (let [sym (gensym)
                       c (compile-query (+ nest-level 1) table slct ctx)
                       on-clause [:= (core/column-path-key from-table from)
                                  (core/column-path-key table to)]]
                   (-> q
                       (h/select [[:raw (format "ROW_TO_JSON(%s)" sym)] field-key])
                       (h/left-join [[:lateral (h/where c on-clause)] (keyword sym)]
                                    true)))
                 :has-many
                 (let [sym (gensym)
                       sub-select (format "COALESCE(JSON_AGG(%s.*), '[]')" sym)
                       c (compile-query (+ nest-level 1) from-table slct ctx)
                       on-clause [:= (core/column-path-key from-table from)
                                  (core/column-path-key table to)]]
                   (h/select q [(-> (h/select [[:raw sub-select]])
                                    (h/from [(h/where c on-clause) (keyword sym)]))
                                field-key]))
                 :has-many-aggr
                 (let [on-clause [:= (core/column-path-key from-table from)
                                  (core/column-path-key table to)]]
                   (h/select q [(-> (h/select
                                     [[:raw (compile-aggr from-table slct)]])
                                    (h/from from-table)
                                    (h/where on-clause)
                                    (core/apply-args (:arguments slct) ctx))
                                field-key]))))))))
     (-> (h/from table-key)
         (core/apply-args (:arguments selection) ctx))
     (:selections selection))))

(defn- compile-aggregation [table-key selection ctx]
  (-> (h/select [[:raw (compile-aggr table-key selection)] :result])
      (h/from table-key)
      (core/apply-args (:arguments selection) ctx)))

(defrecord PostgresAdapter [db]
  core/DbAdapter

  (table-names [adpt]
    (let [schema-name (current-schema db)]
      (core/exec-query (:db adpt) (str "SELECT table_name AS name "
                                       "FROM information_schema.tables "
                                       "WHERE table_schema='" schema-name "' "
                                       "AND table_type='BASE TABLE' "
                                       "AND table_name not like '%migration%';"))))

  (view-names [adpt]
    (let [schema-name (current-schema db)]
      (core/exec-query (:db adpt) (str "SELECT table_name AS name "
                                       "FROM information_schema.tables "
                                       "WHERE table_schema='" schema-name "' "
                                       "AND table_type='VIEW';"))))

  (column-info [adpt table-name]
    (core/exec-query (:db adpt)
                     (str "SELECT column_name AS name, data_type AS type, "
                          "(is_nullable = 'NO') AS notnull, "
                          "column_default AS dflt_value "
                          "FROM information_schema.columns "
                          "WHERE table_name = '" table-name "';")))

  (foreign-keys [adpt table-name]
    (core/exec-query (:db adpt)
                     (str "SELECT kcu.column_name AS from, "
                          "ccu.table_name AS table, "
                          "ccu.column_name AS to "
                          "FROM information_schema.table_constraints as tc "
                          "JOIN information_schema.key_column_usage AS kcu "
                          "ON tc.constraint_name = kcu.constraint_name "
                          "AND tc.table_schema = kcu.table_schema "
                          "JOIN information_schema.constraint_column_usage AS ccu "
                          "ON ccu.constraint_name = tc.constraint_name "
                          "AND ccu.table_schema = tc.table_schema "
                          "WHERE tc.constraint_type = 'FOREIGN KEY' "
                          "AND tc.table_name='" table-name "';")))

  (primary-keys [adpt table-name]
    (core/exec-query (:db adpt)
                     (str "SELECT c.column_name AS name, c.data_type AS type "
                          "FROM information_schema.table_constraints tc "
                          "JOIN information_schema.constraint_column_usage AS ccu "
                          "USING (constraint_schema, constraint_name) "
                          "JOIN information_schema.columns AS c "
                          "ON c.table_schema = tc.constraint_schema "
                          "AND tc.table_name = c.table_name "
                          "AND ccu.column_name = c.column_name "
                          "WHERE constraint_type = 'PRIMARY KEY' "
                          "AND tc.table_name = '" table-name "';")))

  (resolve-query [adpt table-key selection ctx]
    (core/exec-query (:db adpt)
                     (sql/format (compile-query 1 table-key selection ctx))))

  (resolve-aggregation [adpt table-key selection ctx]
    (let [query (compile-aggregation table-key selection ctx)]
      (:result (first (core/exec-query (:db adpt) (sql/format query)))))))

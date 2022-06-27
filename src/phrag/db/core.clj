(ns phrag.db.core
  "DB operations to create and resolve GraphQL."
  (:require [clojure.java.jdbc :as jdbc]
            [jsonista.core :as j]
            [honey.sql :as sql]
            [honey.sql.helpers :as h])
  (:import  [org.postgresql.util PGobject]))

;; Postgres Object Handler

(defmulti read-pgobject
  "Convert returned PGobject to Clojure value."
  #(keyword (when (some? %) (.getType ^PGobject %))))

(defmethod read-pgobject :json
  [^PGobject x]
  (when-let [val (.getValue x)]
    (j/read-value val j/keyword-keys-object-mapper)))

(defmethod read-pgobject :jsonb
  [^PGobject x]
  (when-let [val (.getValue x)]
    (j/read-value val j/keyword-keys-object-mapper)))

(defmethod read-pgobject :default
  [^PGobject x]
  (.getValue x))

(extend-protocol jdbc/IResultSetReadColumn
  PGobject
  (result-set-read-column [val _ _]
    (read-pgobject val)))

;; Utilities

(def ^:no-doc aggr-keys #{:count :avg :max :min :sum})

(defn ^:no-doc column-path [table-key column-key]
  (str (name table-key) "." (name column-key)))

(defn ^:no-doc column-path-key [table-key column-key]
  (keyword (column-path table-key column-key)))

;; Argument Handler

(def ^:private where-ops
  {:eq  :=
   :gt  :>
   :lt  :<
   :gte :>=
   :lte :<=
   :ne  :!=
   :in :in
   :like :like})

(defn- format-where [table-key where-map]
  (reduce (fn [v [col entry]]
            (conj v (cond
                      (= col :and)
                      (into [:and] (map #(format-where table-key %) entry))
                      (= col :or)
                      (into [:or] (map #(format-where table-key %) entry))
                      :else
                      (let [entry (first entry) ;; to MapEntry
                            op ((key entry) where-ops)
                            col-path (column-path-key table-key col)]
                        [op col-path (val entry)]))))
          nil
          where-map))

(defn- apply-where [q table-key whr]
  (apply h/where q (format-where table-key whr)))

(defn- apply-sort [q arg]
  (apply h/order-by q (reduce-kv (fn [vec col direc]
                                   (conj vec [col direc]))
                                 nil arg)))

(defn apply-args
  "Applies filter, sort and pagination arguments."
  [q table-key args ctx]
  (let [def-lmt (:default-limit ctx)
        lmt (or (:limit args) (and (integer? def-lmt) def-lmt))]
    (cond-> (apply-where q table-key (:where args))
      (:sort args) (apply-sort (:sort args))
      lmt (h/limit lmt)
      (integer? (:offset args)) (h/offset (:offset args)))))

;; Interceptor signals

(defn signal
  "Calls all interceptor functions applicable."
  [args table-key op pre-post ctx]
  (reduce (fn [args sgnl-fn]
            (sgnl-fn args ctx))
          args
          (get-in ctx [:tables table-key :signals op pre-post])))

;; Query handling

(defn ^:no-doc exec-query [db q]
  (jdbc/with-db-connection [conn db]
    (jdbc/query conn q)))

(defn create!
  "Executes create statement with parameter map."
  [db rsc raw-map opts]
  ;; (prn rsc raw-map)
  (jdbc/with-db-connection [conn db]
    (jdbc/insert! conn rsc raw-map opts)))

(defn update!
  "Executes update statement with primary key map and parameter map."
  [db table pk-map raw-map]
  (let [whr (map (fn [[k v]] [:= k v]) pk-map)
        q (-> (h/update table)
              (h/set raw-map))]
    ;; (prn (sql/format (apply h/where q whr)))
    (jdbc/with-db-connection [conn db]
      (->> (apply h/where q whr)
           sql/format
           (jdbc/execute! conn)))))

(defn delete!
  "Executes delete statement with primary key map."
  [db table pk-map]
  (let [whr (map (fn [[k v]] [:= k v]) pk-map)
        q (apply h/where (h/delete-from table) whr)]
    ;; (prn (sql/format q))
    (jdbc/with-db-connection [conn db]
      (->> (sql/format q)
           (jdbc/execute! conn)))))

;; DB adapter protocol

(defprotocol DbAdapter
  "Protocol for executing DB-specific operations."
  (table-names [adpt] "Retrieves a list of table names.")
  (view-names [adpt] "Retrieves a list of view names.")
  (column-info [adpt table-name] "Retrieves a list of column maps.")
  (foreign-keys [adpt table-name] "Retrieves a list of foreign key maps.")
  (primary-keys [adpt table-name] "Retrieves a list of primary key maps.")
  (resolve-query [adpt table-key selection ctx]
    "Resolves a GraphQL query which possibly has nested query objects.")
  (resolve-aggregation [adpt table-key selection ctx]
    "Resolves a root-level aggregation query."))

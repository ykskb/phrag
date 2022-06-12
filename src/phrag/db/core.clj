(ns phrag.db.core
  (:require [cheshire.core :as json]
            [clojure.java.jdbc :as jdbc]
            [honey.sql.helpers :as h])
  (:import  [org.postgresql.util PGobject]))

(defmulti read-pgobject
  "Convert returned PGobject to Clojure value."
  #(keyword (when (some? %) (.getType ^PGobject %))))

(defmethod read-pgobject :json
  [^PGobject x]
  (when-let [val (.getValue x)]
    (json/parse-string val true)))

(defmethod read-pgobject :jsonb
  [^PGobject x]
  (when-let [val (.getValue x)]
    (json/parse-string val true)))

(defmethod read-pgobject :default
  [^PGobject x]
  (.getValue x))

(extend-protocol jdbc/IResultSetReadColumn
  PGobject
  (result-set-read-column [val _ _]
    (read-pgobject val)))

(defprotocol DbAdapter
  (table-names [adpt])
  (view-names [adpt])
  (column-info [adpt table-name])
  (foreign-keys [adpt table-name])
  (primary-keys [adpt table-name])
  (resolve-query [adpt table-key selection ctx])
  (resolve-aggregation [adpt table-key selection ctx]))

;; Interceptor signals

(defn signal [args table-key op pre-post ctx]
  (reduce (fn [args sgnl-fn]
            (sgnl-fn args ctx))
          args
          (get-in ctx [:tables table-key :signals op pre-post])))

;; Query Handling

(defn exec-query [db q]
  (prn q)
  (jdbc/with-db-connection [conn db]
    (prn (jdbc/query conn q))
    (jdbc/query conn q)))

(def aggr-keys #{:count :avg :max :min :sum})

(def ^:private where-ops
  {:eq  :=
   :gt  :>
   :lt  :<
   :gte :>=
   :lte :<=
   :ne  :!=
   :in :in
   :like :like})

(defn- parse-rsc-where [rsc-where]
  (map (fn [[col v]]
         (let [entry (first v)
               op ((key entry) where-ops)]
           [op col (val entry)]))
       rsc-where))

(defn- parse-and-or [op where-list]
  (concat [op] (reduce (fn [v whr]
                         (concat v (parse-rsc-where whr)))
                       []
                       where-list)))

(defn- apply-where [q whr]
  (apply h/where q (cond-> (parse-rsc-where (dissoc whr :and :or))
                     (some? (:or whr)) (conj (parse-and-or :or (:or whr)))
                     (some? (:and whr)) (conj (parse-and-or :and (:and whr))))))

(defn- apply-sort [q arg]
  (apply h/order-by q (reduce-kv (fn [vec col direc]
                                   (conj vec [col direc]))
                                 nil arg)))

(defn apply-args [q args ctx]
  (let [def-lmt (:default-limit ctx)
        lmt (or (:limit args) (and (integer? def-lmt) def-lmt))]
    (cond-> (apply-where q (:where args))
      (:sort args) (apply-sort (:sort args))
      lmt (h/limit lmt)
      (integer? (:offset args)) (h/offset (:offset args)))))

(defn column-path [table-key column-key]
  (str (name table-key) "." (name column-key)))

(defn column-path-key [table-key column-key]
  (keyword (column-path table-key column-key)))

(ns phrag.resolver
  "Resolvers for Phrag's GraphQL schema. Queries are executed at each nest level
  to batch nested queries and avoid N+1 problem while allowing use of `limit`."
  (:require [clojure.pprint :as pp]
            [clojure.walk :as w]
            [clojure.set :as clj-set]
            [phrag.logging :refer [log]]
            [phrag.field :as fld]
            [phrag.db.core :as db]
            [com.walmartlabs.lacinia.resolve :as resolve]))

;;; Resolvers

(defmacro resolve-error [body]
  `(try ~body
        (catch Throwable e#
          (log :error e#)
          (resolve/resolve-as nil {:message (ex-message e#)}))))

;; Queries

(defn resolve-query
  "Resolves query recursively for nests if there's any."
  [table-key ctx _args _val]
  (resolve-error
   (let [selection (:com.walmartlabs.lacinia/selection ctx)]
     (-> (db/resolve-query (:db-adapter ctx) table-key selection ctx)
         (db/signal table-key :query :post ctx)))))

;; Aggregates

(defn aggregate-root
  "Resolves aggregation query at root level."
  [table-key ctx _args _val]
  (resolve-error
   (let [selection (:com.walmartlabs.lacinia/selection ctx)]
     (db/resolve-aggregation (:db-adapter ctx) table-key selection ctx))))

;; Mutations

(def ^:private sqlite-last-id
  (keyword "last_insert_rowid()"))

(defn- update-sqlite-pk [res-map pks]
  (if (= (count pks) 1) ; only update single pk
    (assoc res-map (first pks) (sqlite-last-id res-map))
    res-map))

(defn create-root
  "Creates root object and attempts to return primary keys. In case of SQLite,
  `last_insert_rowid` is checked and replaced with a primary key."
  [table-key table ctx args _val]
  (resolve-error
   (let [{:keys [pk-keys col-keys]} table
         params (-> (select-keys args col-keys)
                    (db/signal table-key :create :pre ctx)
                    (w/stringify-keys))
         opts {:return-keys pk-keys}
         sql-res (first (db/create! (:db ctx) table-key params opts))
         id-res (if (contains? sql-res sqlite-last-id)
                  (update-sqlite-pk sql-res pk-keys)
                  sql-res)
         res (merge (w/keywordize-keys params) id-res)]
     (db/signal res table-key :create :post ctx))))

(defn update-root
  "Resolves update mutation. Takes `pk_columns` parameter as a record identifier."
  [table-key table ctx args _val]
  (resolve-error
   (let [{:keys [col-keys]} table
         sql-args (-> (select-keys args col-keys)
                      (assoc :pk_columns (:pk_columns args))
                      (db/signal table-key :update :pre ctx))
         params (-> (dissoc sql-args :pk_columns)
                    (w/stringify-keys))]
     (db/update! (:db ctx) table-key (:pk_columns sql-args) params)
     (db/signal fld/result-true-object table-key :update :post ctx))))

(defn delete-root
  "Resolves delete mutation. Takes `pk_columns` parameter as a record identifier."
  [table-key ctx args _val]
  (resolve-error
   (let [sql-args (db/signal args table-key :delete :pre ctx)]
     (db/delete! (:db ctx) table-key (:pk_columns sql-args))
     (db/signal fld/result-true-object table-key :delete :post ctx))))

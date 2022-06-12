(ns phrag.resolver
  "Resolvers for Phrag's GraphQL schema. Queries are executed at each nest level
  to batch nested queries and avoid N+1 problem while allowing use of `limit`."
  (:require [clojure.pprint :as pp]
            [clojure.walk :as w]
            [clojure.set :as clj-set]
            [phrag.logging :refer [log]]
            [phrag.field :as fld]
            [phrag.db.core :as db]
            [phrag.query :as query]
            [com.walmartlabs.lacinia.resolve :as resolve]))

;; GraphQL args to SQL params

(def ^:private where-ops
  {:eq  :=
   :gt  :>
   :lt  :<
   :gte :>=
   :lte :<=
   :ne  :!=
   :in :in
   :like :like})

(defn- parse-selects [col-keys selection]
  (let [q-fields (set (map :field-name selection))]
    (clj-set/intersection q-fields col-keys)))

(defn- parse-rsc-where [rsc-where]
  (map (fn [[k v]]
         (let [entry (first v)
               op ((key entry) where-ops)]
           [op k (val entry)]))
       rsc-where))

(defn- parse-and-or [op rsc-where-list]
  (concat [op] (reduce (fn [v rsc-where]
                         (concat v (parse-rsc-where rsc-where)))
                       [] rsc-where-list)))

(defn- parse-where [args]
  (let [whr (:where args)]
    (cond-> (parse-rsc-where (dissoc whr :and :or))
      (some? (:or whr)) (conj (parse-and-or :or (:or whr)))
      (some? (:and whr)) (conj (parse-and-or :and (:and whr))))))

(defn- update-sort [m v]
  (assoc m :sort (reduce-kv (fn [vec k v]
                              (conj vec [k v]))
                            [] v)))

(defn- args->sql-params [col-keys args selections default-limit]
  (reduce (fn [m [k v]]
            (cond
              (= k :sort) (update-sort m v)
              (= k :limit) (assoc m :limit v)
              (= k :offset) (assoc m :offset v)
              :else m))
          (cond-> {:select (parse-selects col-keys selections)
                   :where (parse-where args)}
            (integer? default-limit) (assoc :limit default-limit))
          args))

;; Aggregation field to SQL

(defn- aggr-fields [selections]
  (reduce (fn [m selected]
            (let [aggr (:field-name selected)]
              (if (= :count aggr)
                (assoc m :count nil)
                (assoc m aggr (map :field-name (:selections selected))))))
          {} selections))

(defn- aggr-key [aggr-type col]
  (keyword (str (name aggr-type) "_" (name col))))

(defn- aggr-selects [fields]
  (reduce (fn [v [aggr cols]]
            (if (= :count aggr)
              (conj v [[:count :*] :count])
              (concat v (map (fn [c] [[aggr c] (aggr-key aggr c)]) cols))))
          [] fields))

(defn- aggr-result [fields sql-res & [id-key id]]
  (reduce (fn [m [aggr cols]]
            (if (= :count aggr)
              (assoc m aggr (:count sql-res))
              (assoc m aggr (reduce (fn [m col]
                                      (assoc m col ((aggr-key aggr col) sql-res)))
                             {} cols))))
          (or sql-res {id-key id})
          fields))

;; Nest resolution

;;; Resolvers

(defmacro resolve-error [body]
  `(try ~body
        (catch Throwable e#
          (log :error e#)
          (resolve/resolve-as nil {:message (ex-message e#)}))))

;; Queries

(defn resolve-query
  "Resolves query recursively for nests if there's any."
  [table-key table ctx args _val]
  (resolve-error
   (let [{:keys [col-keys rel-cols query-signals]} table
         selection (:com.walmartlabs.lacinia/selection ctx)]
     (-> (db/resolve-query (:db-adapter ctx) table-key selection ctx)
         (db/signal table-key :query :post ctx)))))

;; Aggregates

(defn aggregate-root
  "Resolves aggregation query at root level."
  [table-key ctx args _val]
  (resolve-error
   (let [sql-args (args->sql-params nil args nil nil)
         selections (get-in ctx [:com.walmartlabs.lacinia/selection :selections])
         fields (aggr-fields selections)
         selects (aggr-selects fields)
         res (first (query/aggregate (:db ctx) table-key selects sql-args))]
     (aggr-result fields res))))

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
         sql-res (first (query/create! (:db ctx) table-key params opts))
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
     (query/update! (:db ctx) table-key (:pk_columns sql-args) params)
     (db/signal fld/result-true-object table-key :update :post ctx))))

(defn delete-root
  "Resolves delete mutation. Takes `pk_columns` parameter as a record identifier."
  [table-key table ctx args _val]
  (resolve-error
   (let [sql-args (db/signal args table-key :delete :pre ctx)]
     (query/delete! (:db ctx) table-key (:pk_columns sql-args))
     (db/signal fld/result-true-object table-key :delete :post ctx))))

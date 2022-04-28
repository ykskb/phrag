(ns phrag.handler
  "Handles arguments and DB operations for Phrag's GraphQL resolvers."
  (:require [clojure.set :as clj-set]
            [clojure.walk :as w]
            [phrag.field :as fld]
            [phrag.db :as db]
            [com.walmartlabs.lacinia.resolve :as resolve]))


;; GraphQL args to SQL params

(defn lacinia-selections [ctx]
  (get-in ctx [:com.walmartlabs.lacinia/selection :selections]))

(defn- query-fields [ctx]
  (let [selections (lacinia-selections ctx)]
    (set (map :field-name selections))))

(defn- parse-selects [col-keys ctx]
  (let [q-fields (query-fields ctx)]
    (clj-set/intersection q-fields col-keys)))

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

(defn args->sql-params [col-keys args ctx]
  (let [default-limit (:default-limit ctx)]
    (reduce (fn [m [k v]]
              (cond
                (= k :sort) (update-sort m v)
                (= k :limit) (assoc m :limit v)
                (= k :offset) (assoc m :offset v)
                :else m))
            (cond-> {:select (parse-selects col-keys ctx)
                     :where (parse-where args)}
              (integer? default-limit) (assoc :limit default-limit))
            args)))

;; Interceptor Signal

(defn signal [args sgnl-fns ctx]
  (if (satisfies? resolve/ResolverResult args) args
      (reduce (fn [args sgnl-fn]
                (sgnl-fn args ctx))
              args sgnl-fns)))

;;; Resolver handlers

;; Query

(defn list-partitioned-query [db table p-col-key pk-keys params]
  (let [sort-params (:sort params [[(first pk-keys) :asc]])]
    (db/list-partitioned db table p-col-key
                         (assoc params :sort sort-params))))

(defn list-root [db handler-ctx]
  (let [{:keys [table-key table sgnl-fn-map ctx args]} handler-ctx
        {:keys [col-keys rel-cols]} table
        sql-params (-> (args->sql-params col-keys args ctx)
                       (update :select into rel-cols)
                       (signal (:pre sgnl-fn-map) ctx))
        res (db/list-up db table-key sql-params)]
    (signal res (:post sgnl-fn-map) ctx)))

(defn has-one [u-ids db handler-ctx]
  (let [{:keys [fk sgnl-fn-map ctx]} handler-ctx
        {:keys [to-tbl-key to-key to-tbl-col-keys
                to-tbl-rel-cols]} fk
        sql-params (-> (args->sql-params to-tbl-col-keys nil ctx)
                     (update :select into to-tbl-rel-cols)
                     (signal (:pre sgnl-fn-map) ctx)
                     (assoc :where [[:in to-key u-ids]]))
        res (db/list-up db to-tbl-key sql-params)]
    (signal res (:post sgnl-fn-map) ctx)))

(defn has-many [u-ids db handler-ctx]
  (let [{:keys [table-key table fk sgnl-fn-map ctx args]} handler-ctx
        {:keys [from-key]} fk
        {:keys [pk-keys col-keys rel-cols]} table
        sql-params (-> (args->sql-params col-keys args ctx)
                     (update :select into rel-cols)
                     (signal (:pre sgnl-fn-map) ctx)
                     (update :where conj [:in from-key u-ids]))
        res (if (or (> (:limit sql-params 0) 0)
                    (> (:offset sql-params 0) 0))
              (list-partitioned-query db table-key from-key pk-keys
                                      sql-params)
              (db/list-up db table-key sql-params))]
    (signal res (:post sgnl-fn-map) ctx)))

;; Aggregates

(defn- aggr-fields [ctx]
  (let [selections (lacinia-selections ctx)]
    (reduce (fn [m selected]
              (let [aggr (:field-name selected)]
                (if (= :count aggr)
                  (assoc m :count nil)
                  (assoc m aggr (map :field-name (:selections selected))))))
            {} selections)))

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

(defn- aggr-many-result [fields sql-multi-res id-key ids]
  (let [multi-res-map (zipmap (map #(id-key %) sql-multi-res) sql-multi-res)]
    (map #(aggr-result fields (get multi-res-map %) id-key %) ids)))

(defn aggregate-root [db table-key ctx args]
  (let [sql-args (args->sql-params nil args nil)
        fields (aggr-fields ctx)
        selects (aggr-selects fields)
        res (first (db/aggregate db table-key selects sql-args))]
    (aggr-result fields res)))

(defn aggregate-has-many [u-ids db handler-ctx]
  (let [{:keys [table-key fk sgnl-fn-map ctx args]} handler-ctx
        {:keys [from-key]} fk
        fields (aggr-fields ctx)
        selects (aggr-selects fields)
        sql-params (-> (args->sql-params nil args nil)
                       (signal (:pre sgnl-fn-map) ctx)
                       (update :where conj [:in from-key u-ids]))
        sql-res (db/aggregate-grp-by db table-key selects from-key
                                     sql-params)
        res (aggr-many-result fields sql-res from-key u-ids)]
    (signal res (:post sgnl-fn-map) ctx)))

;;; Mutation

(def ^:private sqlite-last-id
  (keyword "last_insert_rowid()"))

(defn- update-sqlite-pk [res-map pks]
  (if (= (count pks) 1) ; only update single pk
    (assoc res-map (first pks) (sqlite-last-id res-map))
    res-map))

(defn create-root
  "Creates root object and attempts to return primary keys. `last_insert_rowid`
  is checked and replaced with first primary key in case of SQLite."
  [db table-key table sgnl-fn-map ctx args]
  (let [{:keys [pk-keys col-keys]} table
        params (-> (select-keys args col-keys)
                   (signal (:pre sgnl-fn-map) ctx)
                   (w/stringify-keys))
        opts {:return-keys pk-keys}
        sql-res (first (db/create! db table-key params opts))
        id-res (if (contains? sql-res sqlite-last-id)
                 (update-sqlite-pk sql-res pk-keys)
                 sql-res)
        res (merge (w/keywordize-keys params) id-res)]
    (signal res (:post sgnl-fn-map) ctx)))

(defn update-root [db table-key col-keys sgnl-fn-map ctx args]
  (let [sql-args (-> (select-keys args col-keys)
                     (assoc :pk_columns (:pk_columns args))
                     (signal (:pre sgnl-fn-map) ctx))
        params (-> (dissoc sql-args :pk_columns)
                   (w/stringify-keys))]
    (db/update! db table-key (:pk_columns sql-args) params)
    (signal fld/result-true-object (:post sgnl-fn-map) ctx)))

(defn delete-root [db table-key sgnl-fn-map ctx args]
  (let [sql-args (signal args (:pre sgnl-fn-map) ctx)]
    (db/delete! db table-key (:pk_columns sql-args))
    (signal fld/result-true-object (:post sgnl-fn-map) ctx)))

(ns phrag.resolver
  "Resolvers for Phrag's GraphQL schema. Queries are executed with Superlifter
  and Urania to batch nested queries and avoid N+1 problem.."
  (:require [clojure.walk :as w]
            [clojure.pprint :as pp]
            [phrag.logging :refer [log]]
            [phrag.handler :as hd]
            [phrag.field :as fld]
            [urania.core :as u]
            [com.walmartlabs.lacinia.resolve :as resolve]
            [promesa.core :as prom]
            [superlifter.api :as sl-api]
            [superlifter.core :as sl-core]))

(defmacro resolve-error [body]
  `(try ~body
        (catch Throwable e#
          (log :error e#)
          (resolve/resolve-as nil {:message (ex-message e#)}))))

;; Urania / Super Lifter

(defrecord FetchDataSource [fetch-fn]
  u/DataSource
  (-identity [this] (:u-id this))
  (-fetch [this env]
    (resolve-error (sl-api/unwrap ((:fetch-fn this) this env)))))

(defrecord BatchDataSource [u-id batch-fn map-1-fn map-n-fn]
  u/DataSource
  (-identity [this] (:u-id this))
  (-fetch [this env]
    (resolve-error (let [responses ((:batch-fn this) [this] env)]
                     (sl-api/unwrap (:map-1-fn this) responses))))

  u/BatchedSource
  (-fetch-multi [muse muses env]
    (resolve-error (let [muses (cons muse muses)
                         responses ((:batch-fn muse) muses env)
                         map-fn (partial (:map-n-fn muse) muses)]
                     (sl-api/unwrap map-fn responses)))))

(defn- ->lacinia-promise [sl-result]
  (let [l-prom (resolve/resolve-promise)]
    (sl-api/unwrap #(resolve/deliver! l-prom %)
                   (prom/catch sl-result prom/resolved))
    l-prom))

(defmacro with-superlifter [ctx body]
  `(sl-api/with-superlifter ~ctx
     (->lacinia-promise ~body)))

(defn- update-count-threshold [rel trigger-opts ctx]
  (log :debug "Incrementing" rel "queue by" (count ctx))
  (update trigger-opts :threshold + (count ctx)))

(defn- update-n-threshold [rel num trigger-opts]
  (log :debug "Updating" rel "queue with" num)
  (assoc trigger-opts :threshold num))

(defn- update-triggers-by-count! [res-p rels]
  (reduce (fn [p rel]
            (sl-api/update-trigger! p (keyword rel) :elastic
                                    (partial update-count-threshold rel)))
          res-p rels))

(defn- do-update-triggers! [ctx rels c]
  (doseq [rel rels]
    (sl-core/update-trigger! ctx (keyword rel) :elastic
                             (partial update-n-threshold rel c))))

;; Interceptor Signal

(defn- signal [args sgnl-fns ctx]
  (if (satisfies? resolve/ResolverResult args) args
      (reduce (fn [args sgnl-fn]
                (sgnl-fn args ctx))
              args sgnl-fns)))

;;; Resolvers

;; Queries

(defn list-query
  "Resolves root-level query. Superlifter queue is always :default."
  [table-key table sgnl-map ctx args _val]
  (resolve-error
   (with-superlifter (:sl-ctx ctx)
     (let [{:keys [col-keys rel-cols rel-flds]} table
           sql-params (-> (hd/args->sql-params col-keys args ctx)
                          (update :select into rel-cols)
                          (signal (:pre sgnl-map) ctx))
           fetch-fn (fn [_this _env]
                      (hd/list-root (:db ctx) table-key sql-params))
           res-p (-> (sl-api/enqueue! (->FetchDataSource fetch-fn))
                     (update-triggers-by-count! rel-flds))]
       (prom/then res-p (fn [v]
                          (resolve-error (signal v (:post sgnl-map) ctx))))))))

(defn has-one
  "Resolves has-one relationship.
  e.g. (shopping_cart.user_id fk=> user.id)
  Parent: [shopping_cart].user_id => [user].id"
  [fk sgnl-fn-map ctx _args val]
  (resolve-error
   (with-superlifter (:sl-ctx ctx)
     (let [{:keys [to-tbl-key from-key to-key to-tbl-col-keys
                   to-tbl-rel-cols to-tbl-rel-flds]
            {:keys [has-one]} :field-keys} fk
           sql-args (-> (hd/args->sql-params to-tbl-col-keys nil ctx)
                        (update :select into to-tbl-rel-cols)
                        (signal (:pre sgnl-fn-map) ctx))
           batch-fn (fn [many _env]
                      (let [ids (map :u-id many)
                            sql-params (assoc sql-args :where [[:in to-key ids]])
                            res (hd/list-root (:db ctx) to-tbl-key sql-params)]
                        (do-update-triggers! (:sl-ctx ctx) to-tbl-rel-flds
                                             (count res))
                        res))
           map-n-fn (fn [muses batch-res]
                      (let [m (zipmap (map :u-id muses) (repeat nil))
                            vals (zipmap (map to-key batch-res) batch-res)]
                        (merge m vals)))
           res-p (sl-api/enqueue! has-one
                                  (->BatchDataSource (from-key val) batch-fn
                                                     first map-n-fn))]
       (prom/then res-p (fn [v]
                          (resolve-error (signal v (:post sgnl-fn-map) ctx))))))))

(defn has-many
  "Resolves has-many relationship.
  e.g. (shopping_cart.user_id fk=> user.id)
  Parent values: [user].id => [shopping_cart].user_id"
  [table-key table fk sgnl-fn-map ctx args val]
  (resolve-error
   (with-superlifter (:sl-ctx ctx)
     (let [{:keys [from-key to-key]
            {:keys [has-many]} :field-keys} fk
           {:keys [pk-keys col-keys rel-cols rel-flds]} table
           sql-args (-> (hd/args->sql-params col-keys args ctx)
                        (update :select into rel-cols)
                        (signal (:pre sgnl-fn-map) ctx))
           batch-fn (fn [many _env]
                      (let [ids (map :u-id many)
                            sql-args (update sql-args :where
                                             conj [:in from-key ids])
                            res (if (or (> (:limit sql-args 0) 0)
                                        (> (:offset sql-args 0) 0))
                                  (hd/list-partitioned (:db ctx) table-key
                                                       from-key pk-keys sql-args)
                                  (hd/list-root (:db ctx) table-key sql-args))]
                        (do-update-triggers! (:sl-ctx ctx) rel-flds (count res))
                        res))
           map-n-fn (fn [muses batch-res]
                      (let [m (zipmap (map :u-id muses) (repeat []))
                            vals (group-by from-key batch-res)]
                        (merge-with concat m vals)))
           res-p (sl-api/enqueue! has-many
                                  (->BatchDataSource (to-key val) batch-fn
                                                     identity map-n-fn))]
       (prom/then res-p (fn [v]
                          (resolve-error (signal v (:post sgnl-fn-map) ctx))))))))

;; Aggregates

(defn- aggr-fields [ctx]
  (let [selections (hd/lacinia-selections ctx)]
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

(defn aggregate-root
  "Resolves aggregation query at root level."
  [table-key ctx args _val]
  (resolve-error
   (let [sql-args (hd/args->sql-params nil args nil)
         fields (aggr-fields ctx)
         selects (aggr-selects fields)
         res (first (hd/aggregate-root (:db ctx) table-key selects sql-args))]
     (aggr-result fields res))))

(defn aggregate-has-many
  "Resolves aggregation query for has-many relationship."
  [table-key fk-from fk-to sl-key sgnl-fn-map ctx args val]
  (resolve-error
   (with-superlifter (:sl-ctx ctx)
     (let [sql-args (-> (hd/args->sql-params nil args nil)
                        (signal (:pre sgnl-fn-map) ctx))
           fields (aggr-fields ctx)
           selects (aggr-selects fields)
           batch-fn (fn [many _env]
                      (let [ids (map :u-id many)
                            sql-args (update sql-args :where
                                             conj [:in fk-from ids])
                            sql-res (hd/aggregate-grp-by (:db ctx) table-key
                                                         selects fk-from sql-args)]
                        (aggr-many-result fields sql-res fk-from ids)))
           map-n-fn (fn [_muses batch-res]
                      (zipmap (map fk-from batch-res) batch-res))
           res-p (sl-api/enqueue! sl-key (->BatchDataSource (fk-to val) batch-fn
                                                            first map-n-fn))]
       (prom/then res-p (fn [v]
                          (resolve-error (signal v (:post sgnl-fn-map) ctx))))))))

;; Mutations

(defn create-root
  "Resolves create mutation."
  [table-key pk-keys col-keys sgnl-fn-map ctx args _val]
  (resolve-error
   (let [sql-args (-> (select-keys args col-keys)
                      (signal (:pre sgnl-fn-map) ctx)
                      (w/stringify-keys))
         res (hd/create-root sql-args (:db ctx) table-key pk-keys)
         created (merge args res)]
     (signal created (:post sgnl-fn-map) ctx))))

(defn update-root
  "Resolves update mutation. Takes `pk_columns` parameter as a record identifier."
  [table-key col-keys sgnl-fn-map ctx args _val]
  (resolve-error
   (let [sql-args (-> (select-keys args col-keys)
                      (assoc :pk_columns (:pk_columns args))
                      (signal (:pre sgnl-fn-map) ctx))
         params (-> (dissoc sql-args :pk_columns)
                    (w/stringify-keys))]
     (hd/patch-root (:pk_columns sql-args) params (:db ctx) table-key)
     (signal fld/result-true-object (:post sgnl-fn-map) ctx))))

(defn delete-root
  "Resolves delete mutation. Takes `pk_columns` parameter as a record identifier."
  [table-key sgnl-fn-map ctx args _val]
  (resolve-error
   (let [sql-args (signal args (:pre sgnl-fn-map) ctx)]
     (hd/delete-root (:pk_columns sql-args)(:db ctx) table-key)
     (signal fld/result-true-object (:post sgnl-fn-map) ctx))))

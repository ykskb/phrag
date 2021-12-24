(ns phrag.resolver
  (:require [clojure.walk :as w]
            [clojure.pprint :as pp]
            [phrag.logging :refer [log]]
            [phrag.handler :as hd]
            [urania.core :as u]
            [com.walmartlabs.lacinia.resolve :as resolve]
            [promesa.core :as prom]
            [superlifter.api :as sl-api]
            [superlifter.core :as sl-core]))

;; Workaround as error/reject in fetch method make promises stuck in pending
;; state without being caught in subsequent codes. Fallback is to return nil
;; after error log output.
(defn- exec-sql [sql-fn records env]
  (try (sql-fn records env)
       (catch Throwable e
         (log :error e)
         nil)))

(defrecord FetchDataSource [fetch-fn]
  u/DataSource
  (-identity [this] (:id this))
  (-fetch [this env]
    (sl-api/unwrap (exec-sql (:fetch-fn this) this env))))

(defrecord BatchDataSource [id batch-fn map-1-fn map-n-fn]
  u/DataSource
  (-identity [this] (:id this))
  (-fetch [this env]
    (let [responses (exec-sql (:batch-fn this) [this] env)]
      (sl-api/unwrap (:map-1-fn this) responses)))

  u/BatchedSource
  (-fetch-multi [muse muses env]
    (let [muses (cons muse muses)
          responses (exec-sql (:batch-fn muse) muses env)
          map-fn (partial (:map-n-fn muse) muses)]
      (sl-api/unwrap map-fn responses))))

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
  (update trigger-opts :threshold + num))

(defn- update-triggers-by-count! [res-p rels]
  (reduce (fn [p rel]
            (sl-api/update-trigger! p (keyword rel) :elastic
                                    (partial update-count-threshold rel)))
          res-p rels))

(defn- do-update-triggers! [ctx rels c]
  (doseq [rel rels]
    (sl-core/update-trigger! ctx (keyword rel) :elastic
                             (partial update-n-threshold rel c))))

(defn- signal [args ctx tbl-key op type]
  (if-let [sgnl-fn (get-in ctx [:signals tbl-key op type])]
    (sgnl-fn args ctx)
    args))

;;; Resolvers

;; Queries

(defn list-query [table rels ctx args _val]
  (with-superlifter (:sl-ctx ctx)
    (let [sql-args (-> (hd/args->sql-params args)
                       (signal ctx table :query :pre))
          fetch-fn (fn [_this _env]
                     (hd/list-root (:db ctx) table sql-args))
          res-p (-> (sl-api/enqueue! (->FetchDataSource fetch-fn))
                    (update-triggers-by-count! rels))]
      (prom/then res-p (fn [v] (signal v ctx table :query :post))))))


(defn has-one [id-key table rels ctx _args val]
  (with-superlifter (:sl-ctx ctx)
    (let [sql-args (signal nil ctx table :query :pre)
          batch-fn (fn [many _env]
                     (let [ids (map :id many)
                           whr (-> {:where [[:in :id ids]]}
                                   (update :where conj sql-args))
                           res (hd/list-root (:db ctx) table whr)]
                       (do-update-triggers! (:sl-ctx ctx) rels (count res))
                       res))
          map-n-fn (fn [muses batch-res]
                     (let [m (zipmap (map :id muses) (repeat nil))
                           vals (zipmap (map :id batch-res) batch-res)]
                       (merge m vals)))
          res-p (sl-api/enqueue! table
                                 (->BatchDataSource (id-key val) batch-fn
                                                    first map-n-fn))]
      (prom/then res-p (fn [v] (signal v ctx table :query :post))))))

(defn has-many [id-key table rels ctx args val]
  (with-superlifter (:sl-ctx ctx)
    (let [sql-args (-> (hd/args->sql-params args)
                       (signal ctx table :query :pre))
          batch-fn (fn [many _env]
                     (let [ids (map :id many)
                           sql-args (update sql-args :where
                                           conj [:in id-key ids])
                           res (hd/list-root (:db ctx) table sql-args)]
                      (do-update-triggers! (:sl-ctx ctx) rels (count res))
                      res))
          map-n-fn (fn [muses batch-res]
                     (let [m (zipmap (map :id muses) (repeat []))
                           vals (group-by id-key batch-res)]
                       (merge-with concat m vals)))
          res-p (sl-api/enqueue! table
                                 (->BatchDataSource (:id val) batch-fn
                                                    identity map-n-fn))]
      (prom/then res-p (fn [v] (signal v ctx table :query :post))))))

;; Aggregates

(defn- aggr-fields [ctx]
  (let [selections (-> ctx
                   (:com.walmartlabs.lacinia/selection)
                   (:selections))]
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
           (concat v (map (fn [col]
                            [[aggr col] (aggr-key aggr col)])
                          cols))))
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

(defn aggregate-root [table ctx args _val]
  (let [sql-args (hd/args->sql-params args)
        fields (aggr-fields ctx)
        selects (aggr-selects fields)
        res (first (hd/aggregate-root (:db ctx) table selects sql-args))]
    (pp/pprint (-> ctx
             (:com.walmartlabs.lacinia/selection)
             (:selections)))
    (aggr-result fields res)))

(defn aggregate-has-many [id-key table ctx args val]
  (with-superlifter (:sl-ctx ctx)
    (let [sql-args (-> (hd/args->sql-params args)
                       (signal ctx table :query :pre))
          fields (aggr-fields ctx)
          selects (aggr-selects fields )
          batch-fn (fn [many _env]
                     (let [ids (map :id many)
                           sql-args (update sql-args :where
                                            conj [:in id-key ids])
                           sql-res (hd/aggregate-grp-by (:db ctx) table selects
                                                   id-key sql-args)]
                       (aggr-many-result fields sql-res id-key ids)))
          map-n-fn (fn [_muses batch-res]
                     (zipmap (map id-key batch-res) batch-res))
          res-p (sl-api/enqueue! (keyword (str (name table)"_aggregate"))
                                 (->BatchDataSource (:id val) batch-fn
                                                    first map-n-fn))]
      (prom/then res-p (fn [v] (signal v ctx table :query :post))))))

;; Mutations

(def ^:private res-true
  {:result true})

(defn create-root [table-key pk-keys cols ctx args _val]
  (prn args cols (select-keys args cols))
  (let [sql-args (-> (select-keys args cols)
                     (signal ctx table-key :create :pre)
                     (w/stringify-keys))
        res (if (not sql-args)
              nil (hd/create-root sql-args (:db ctx) table-key pk-keys))
        created (merge args res)]
    (signal created ctx table-key :create :post)))

(defn update-root [table cols ctx args _val]
  (let [params (-> (select-keys args cols)
                   (signal ctx table :update :pre)
                   (w/stringify-keys))]
    (when (not-empty params)
      (hd/patch-root (:pk_columns args) params (:db ctx) table cols))
    (signal res-true ctx table :update :post)))

(defn delete-root [table ctx args _val]
  (let [sql-args (signal args ctx table :delete :pre)]
    (when (not-empty sql-args)
      (hd/delete-root (:pk_columns args) (:db ctx) table))
    (signal res-true ctx table :delete :post)))

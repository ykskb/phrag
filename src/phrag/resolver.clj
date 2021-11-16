(ns phrag.resolver
  (:require [clojure.walk :as w]
            [clojure.pprint :as pp]
            [phrag.logging :refer [log]]
            [phrag.handlers.core :as c]
            [urania.core :as u]
            [com.walmartlabs.lacinia.resolve :as resolve]
            [promesa.core :as prom]
            [superlifter.api :as sl-api]
            [superlifter.core :as sl-core]))

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
  (let [whr-list (reduce (fn [v rsc-where]
                           (concat v (parse-rsc-where rsc-where)))
                         [] rsc-where-list)]
    (concat [op] whr-list)))

(defn- parse-where [args]
  (let [whr (:where args)]
    (cond-> (parse-rsc-where (dissoc whr :and :or))
      (some? (:or whr)) (conj (parse-and-or :or (:or whr)))
      (some? (:and whr)) (conj (parse-and-or :and (:and whr))))))

(defn- parse-sort [m v]
  (let [col (first (keys v))
        direc (col v :desc)]
    (if (and col direc)
      (-> m
          (assoc :order-col col)
          (assoc :direc direc))
      m)))

(defn- args->sql-args [args]
  (reduce (fn [m [k v]]
            (cond
              (= k :sort) (parse-sort m v)
              (= k :limit) (assoc m :limit v)
              (= k :offset) (assoc m :offset v)
              :else m))
          {:where (parse-where args):limit 100 :offset 0}
          args))

;; Workaround as error/reject in fetch method make promises stuck in pending state without
;; being caught in subsequent codes. Fallback is to return nil after error log output.
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

(defrecord HasOneDataSource [id batch-fn]
  u/DataSource
  (-identity [this] (:id this))
  (-fetch [this env]
    (let [responses (exec-sql (:batch-fn this) [this] env)]
      (sl-api/unwrap first responses)))

  u/BatchedSource
  (-fetch-multi [muse muses env]
    (let [muses (cons muse muses)
          responses (exec-sql (:batch-fn muse) muses env)
          map-fn (fn [responses]
                   (zipmap (map u/-identity muses)
                           responses))]
      (sl-api/unwrap map-fn responses))))

(defrecord HasManyDataSource [id batch-fn rel-key]
  u/DataSource
  (-identity [this] (:id this))
  (-fetch [this env]
    (let [results (exec-sql (:batch-fn this) [this] env)]
      (sl-api/unwrap :res results)))

  u/BatchedSource
  (-fetch-multi [muse muses env]
    (let [muses (cons muse muses)
          responses (exec-sql (:batch-fn muse) muses env)
          map-fn (fn [result]
                   (let [m (zipmap (:ids result) (repeat []))
                         vals (group-by rel-key (:res result))]
                     (merge-with concat m vals)))]
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

(defn- signal [args ctx tbl-name op type]
  (if-let [sgnl-fn (get-in ctx [:signals (keyword tbl-name) op type])]
    (sgnl-fn args ctx)
    args))

;;; Resolvers

;; Queries

(defn list-query [table rels ctx args _val]
  (with-superlifter (:sl-ctx ctx)
    (let [sql-args (-> (args->sql-args args)
                       (signal ctx table :query :pre))
          fetch-fn (fn [_this _env]
                     (c/list-root (:db ctx) table sql-args))
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
                           res (c/list-root (:db ctx) table whr)]
                       (do-update-triggers! (:sl-ctx ctx) rels (count res))
                       res))
          res-p (sl-api/enqueue! (keyword table)
                                 (->HasOneDataSource (id-key val) batch-fn))]
      (prom/then res-p (fn [v] (signal v ctx table :query :post))))))


(defn has-many [id-key table rels ctx args val]
  (with-superlifter (:sl-ctx ctx)
    (let [sql-args (-> (args->sql-args args)
                       (signal ctx table :query :pre))
          batch-fn (fn [many _env]
                     (let [ids (map :id many)
                           sql-args (update sql-args :where
                                           conj [:in id-key ids])
                           res (c/list-root (:db ctx) table sql-args)]
                      (do-update-triggers! (:sl-ctx ctx) rels (count res))
                      {:ids ids :res res}))
          res-p (sl-api/enqueue! (keyword table)
                                 (->HasManyDataSource (:id val) batch-fn id-key))]
      (prom/then res-p (fn [v] (signal v ctx table :query :post))))))

;; Mutations

(def ^:private res-true
  {:result true})

(def ^:private sqlite-last-id
  (keyword "last_insert_rowid()"))

(defn- created-id [result]
  (some #(% (first result)) [sqlite-last-id :id]))

(defn create-root [table cols ctx args _val]
  (let [sql-args (-> (signal args ctx table :create :pre)
                     (w/stringify-keys))
        res (c/create-root sql-args (:db ctx) table cols)
        created (assoc args :id (created-id res))
        sgnl-res (signal created ctx table :create :post)]
    {:id (:id sgnl-res)}))

(defn update-root [table cols ctx args _val]
  (let [sql-args (-> (signal args ctx table :update :pre)
                     (w/stringify-keys))]
    (c/patch-root (:id args) sql-args (:db ctx) table cols)
    (signal res-true ctx table :update :post)))

(defn delete-root [table ctx args _val]
  (let [sql-args (-> (signal args ctx table :delete :pre)
                     (w/stringify-keys))]
    (c/delete-root (:id args) (:db ctx) table)
    (signal res-true ctx table :delete :post)))

(defn create-n-n [col-a col-b table cols ctx args _val]
  (let [sql-args (-> (signal args ctx table :create :pre)
                     (w/stringify-keys))
        col-a-val (get sql-args col-a)
        col-b-val (get sql-args col-b)]
    (c/create-n-n col-a col-a-val col-b col-b-val sql-args (:db ctx) table cols)
    (signal res-true ctx table :create :post)))

(defn delete-n-n [col-a col-b table ctx args _val]
  (let [sql-args (-> (signal args ctx table :delete :pre)
                     (w/stringify-keys))
        col-a-val (get sql-args col-a)
        col-b-val (get sql-args col-b)]
    (c/delete-n-n col-a col-a-val col-b col-b-val (:db ctx) table)
    (signal res-true ctx table :delete :post)))



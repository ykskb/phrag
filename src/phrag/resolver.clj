(ns phrag.resolver
  (:require [clojure.walk :as w]
            [clojure.pprint :as pp]
            [phrag.handlers.core :as c]
            [urania.core :as u]
            [com.walmartlabs.lacinia.resolve :as resolve]
            [promesa.core :as prom]
            [superlifter.api :as sl-api]
            [superlifter.core :as sl-core]))

(def ^:private sort-ops
  {:eq  :=
   :lt  :<
   :le  :<=
   :lte :<=
   :gt  :>
   :ge  :>=
   :gte :>=
   :ne  :!=})

(defn- parse-filter [fltr]
  (map (fn [[k v]]
         (let [op ((:operator v) sort-ops)]
           [op k (:value v)]))
       fltr))

(defn- parse-sort [m v]
  (let [col (first (keys v))
        direc (col v :desc)]
    (if (and col direc)
      (-> m
          (assoc :order-col col)
          (assoc :direc direc))
      m)))

(defn- args->filters [args]
  (reduce (fn [m [k v]]
            (cond
              (= k :filter) (assoc m :filters (parse-filter v))
              (= k :sort) (parse-sort m v)
              (= k :limit) (assoc m :limit v)
              (= k :offset) (assoc m :offset v)
              :else m))
          {:limit 100 :offset 0}
          args))

(defrecord FetchDataSource [fetch-fn]
  u/DataSource
  (-identity [this] (:id this))
  (-fetch [this env]
    (sl-api/unwrap ((:fetch-fn this) this env))))

(defrecord HasOneDataSource [id batch-fn]
  u/DataSource
  (-identity [this] (:id this))
  (-fetch [this env]
    (let [responses ((:batch-fn this) [this] env)]
      (sl-api/unwrap first responses)))

  u/BatchedSource
  (-fetch-multi [muse muses env]
    (let [muses (cons muse muses)
          responses ((:batch-fn muse) muses env)
          map-fn (fn [responses]
                   (zipmap (map u/-identity muses)
                           responses))]
      (sl-api/unwrap map-fn responses))))

(defrecord HasManyDataSource [id batch-fn rel-key]
  u/DataSource
  (-identity [this] (:id this))
  (-fetch [this env]
    (let [results ((:batch-fn this) [this] env)]
      (sl-api/unwrap :res results)))

  u/BatchedSource
  (-fetch-multi [muse muses env]
    (let [muses (cons muse muses)
          responses ((:batch-fn muse) muses env)
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
  (println "Incrementing" rel "queue by" (count ctx))
  (update trigger-opts :threshold + (count ctx)))

(defn- update-num-threshold [rel num trigger-opts]
  (println "Updating" rel "queue with" num)
  (update trigger-opts :threshold + num))

(defn- update-1-threshold [rel trigger-opts _ctx]
  (println "Incrementing" rel "queue by 1")
  (update trigger-opts :threshold + 1))

(defn- update-triggers-by-count [res-p rels]
  (reduce (fn [p rel]
            (sl-api/update-trigger! p (keyword rel) :elastic
                                    (partial update-count-threshold rel)))
          res-p rels))

(defn- update-triggers-by-1 [res-p rels]
  (reduce (fn [p rel]
            (sl-api/update-trigger! p (keyword rel) :elastic
                                    (partial update-1-threshold rel)))
          res-p rels))

(defn list-query [table rels ctx args _val]
  (with-superlifter (:sl-ctx ctx)
    (let [filters (args->filters args)
          fetch-fn (fn [_this _env] (c/list-root (:db ctx) table filters))
          res-p (sl-api/enqueue! (->FetchDataSource fetch-fn))]
      (update-triggers-by-count res-p rels))))

(defn id-query [table rels ctx args _val]
  (with-superlifter (:sl-ctx ctx)
    (let [filters (args->filters args)
          fetch-fn (fn [_this _env] (c/fetch-root (:id args) (:db ctx)
                                                  table filters))
          res-p (sl-api/enqueue! (->FetchDataSource fetch-fn))]
      (update-triggers-by-1 res-p rels))))

(defn has-one [id-key table rels ctx _args val]
  (with-superlifter (:sl-ctx ctx)
    (let [batch-fn (fn [many _env]
                     (let [ids (map :id many)
                           res (c/list-root (:db ctx) table
                                            {:filters [[:in :id ids]]})]
                       (doseq [rel rels]
                         (sl-core/update-trigger!
                          (:sl-ctx ctx) (keyword rel) :elastic
                          (partial update-num-threshold rel (count res))))
                       res))]
      (sl-api/enqueue! (keyword table)
                       (->HasOneDataSource (id-key val) batch-fn)))))

(defn has-many [id-key table rels ctx args val]
  (with-superlifter (:sl-ctx ctx)
    (let [arg-fltrs (args->filters args)
          batch-fn (fn [many _env]
                     (let [ids (map :id many)
                           filters (update arg-fltrs :filters
                                           conj [:in id-key ids])
                           res (c/list-root (:db ctx) table filters)]
                       (doseq [rel rels]
                         (sl-core/update-trigger!
                          (:sl-ctx ctx) (keyword rel) :elastic
                          (partial update-num-threshold rel (count res))))
                       {:ids ids :res res}))]
      (sl-api/enqueue! (keyword table)
                       (->HasManyDataSource (:id val) batch-fn id-key)))))

(defn n-to-n [join-col p-col nn-table table rels ctx args val]
  (with-superlifter (:sl-ctx ctx)
    (let [filters (args->filters args)
          p-col-key (keyword p-col)
          batch-fn (fn [many _env]
                     (let [ids (map :id many)
                           res (c/list-n-n join-col p-col ids (:db ctx)
                                           nn-table table filters)]
                       (doseq [rel rels]
                         (sl-core/update-trigger!
                          (:sl-ctx ctx) (keyword rel) :elastic
                          (partial update-num-threshold rel (count res))))
                       {:ids ids :res res}))]
      (sl-api/enqueue! (keyword nn-table)
                       (->HasManyDataSource (:id val) batch-fn p-col-key)))))

(def ^:private res-true
  {:result true})

(defn create-root [table cols ctx args _val]
  (c/create-root (w/stringify-keys args) (:db ctx) table cols)
  res-true)

(defn update-root [table cols ctx args _val]
  (c/patch-root (:id args) (w/stringify-keys args)
                (:db ctx) table cols)
  res-true)

(defn delete-root [table ctx args _val]
  (c/delete-root (:id args) (:db ctx) table)
  res-true)

(defn create-n-n [col-a col-b table cols ctx args _val]
  (let [col-a-key (keyword col-a)
        col-b-key (keyword col-b)]
    (c/create-n-n col-a (col-a-key args) col-b (col-b-key args) args
                  (:db ctx) table cols)
    res-true))

(defn delete-n-n [col-a col-b table ctx args _val]
  (let [col-a-key (keyword col-a)
        col-b-key (keyword col-b)]
    (c/delete-n-n col-a (col-a-key args) col-b (col-b-key args)
                  (:db ctx) table)
    res-true))



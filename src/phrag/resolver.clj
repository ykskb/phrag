(ns phrag.resolver
  (:require [clojure.walk :as w]
            [clojure.pprint :as pp]
            [phrag.handlers.core :as c]
            [urania.core :as u]
            [com.walmartlabs.lacinia.resolve :as resolve]
            [promesa.core :as prom]
            [superlifter.api :as sl-api]))

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
    (println "fetch triggg")
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

(defn list-query [sl-ctx db table rels _ctx args _val]
  (let [filters (args->filters args)
        fetch-fn (fn [_this _env] (c/list-root db table filters))]
    (with-superlifter sl-ctx
      (let [res-p (sl-api/enqueue! (->FetchDataSource fetch-fn))
            trgr-update-fn (fn [trgr-opts ctx]
                            (println "updated from" trgr-opts)
                            (println (count ctx))
                            (update trgr-opts :threshold + (count ctx)))]
        (doseq [rel rels]
          (println "queueing " rel)
          (sl-api/update-trigger! res-p (keyword rel) :elastic trgr-update-fn))
        res-p))))

(defn id-query [sl-ctx db table rels _ctx args _val]
  (let [filters (args->filters args)
        fetch-fn (fn [_this _env] (c/fetch-root (:id args) db table filters))
        trgr-update-fn (fn [trgr-opts _ctx]
                         (update trgr-opts :threshold + 1))]
    (with-superlifter sl-ctx
      (let [res-p (sl-api/enqueue! (->FetchDataSource fetch-fn))]
        (doseq [rel rels]
          (sl-api/update-trigger! res-p (keyword rel) :elastic trgr-update-fn))
        res-p))))

(defn has-many [sl-ctx id-key db table _ctx args val]
  (let [arg-fltrs (args->filters args)
        batch-fn (fn [many _env]
                   (let [ids (map :id many)
                         filters (update arg-fltrs :filters conj [:in id-key ids])
                         res (c/list-root db table filters)]
                     {:ids ids :res res}))]
    (with-superlifter sl-ctx
      (let [p (sl-api/enqueue! (keyword table)
                               (->HasManyDataSource (:id val) batch-fn id-key))]
        p))))

(defn has-one [sl-ctx id-key db table _ctx _args val]
  (let [batch-fn (fn [many _env]
                   (let [ids (map :id many)
                         res (c/list-root db table {:filters [[:in :id ids]]})]
                     (println res)
                     res))]
    (with-superlifter sl-ctx
      (sl-api/enqueue! (keyword table)
                       (->HasOneDataSource (id-key val) batch-fn)))))

(defn n-to-n [sl-ctx join-col p-col db nn-table table _ctx args val]
  (let [filters (args->filters args)
        p-col-key (keyword p-col)
        batch-fn (fn [many _env]
                   (let [ids (map :id many)
                         res (c/list-n-n join-col p-col ids db
                                         nn-table table filters)]
                    {:ids ids :res res}))]
    (println "nn" nn-table "tbl" table)
    (with-superlifter sl-ctx
      (sl-api/enqueue! (keyword nn-table)
                       (->HasManyDataSource (:id val) batch-fn p-col-key)))))

(def ^:private res-true
  {:result true})

(defn create-root [db table cols _ctx args _val]
  (c/create-root (w/stringify-keys args) db table cols)
  res-true)

(defn update-root [db table cols _ctx args _val]
  (c/patch-root (:id args) (w/stringify-keys args) db table cols)
  res-true)

(defn delete-root [db table _ctx args _val]
  (c/delete-root (:id args) db table)
  res-true)

(defn create-n-n [col-a col-b db table cols _ctx args _val]
  (let [col-a-key (keyword col-a)
        col-b-key (keyword col-b)]
    (c/create-n-n col-a (col-a-key args) col-b (col-b-key args) args
                  db table cols)
    res-true))

(defn delete-n-n [col-a col-b db table _ctx args _val]
  (let [col-a-key (keyword col-a)
        col-b-key (keyword col-b)]
    (c/delete-n-n col-a (col-a-key args) col-b (col-b-key args)
                  db table)
    res-true))



(ns phrag.resolver
  "Resolvers for Phrag's GraphQL schema. Queries are executed with Superlifter
  and Urania to batch nested queries and avoid N+1 problem.."
  (:require [clojure.pprint :as pp]
            [phrag.logging :refer [log]]
            [phrag.handler :as hd]
            [urania.core :as u]
            [com.walmartlabs.lacinia.resolve :as resolve]
            [promesa.core :as prom]
            [superlifter.api :as sl-api]
            [superlifter.core :as sl-core]))

(defmacro resolve-error-promise [body]
  `(try ~body
        (catch Throwable e#
          (log :error e#)
          (sl-api/unwrap (resolve/resolve-as nil {:message (ex-message e#)})))))

(defmacro resolve-error [body]
  `(try ~body
        (catch Throwable e#
          (log :error e#)
          (resolve/resolve-as nil {:message (ex-message e#)}))))

;; Urania / Super Lifter

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

;;; Resolvers

;; Queries

(defrecord RootDataSource [table-key table sgnl-fn-map ctx args]
  u/DataSource
  (-identity [this] (:u-id this))
  (-fetch [this env]
    (resolve-error-promise
     (sl-api/unwrap (hd/list-root (:db env) this)))))

(defn list-query
  "Resolves root-level query. Superlifter queue is always :default."
  [table-key table sgnl-fn-map ctx args _val]
  (resolve-error
   (with-superlifter (:sl-ctx ctx)
     (-> (sl-api/enqueue! (->RootDataSource table-key table sgnl-fn-map
                                            ctx args))
         (update-triggers-by-count! (:rel-flds table))))))

(defrecord HasOneDataSource [u-id fk sgnl-fn-map ctx]
  u/DataSource
  (-identity [this] (:u-id this))
  (-fetch [this env]
    (resolve-error-promise
     (let [responses (hd/has-one [(:u-id this)] (:db env) this)]
       (sl-api/unwrap first responses))))

  u/BatchedSource
  (-fetch-multi [muse muses env]
    (resolve-error-promise
     (let [{:keys [to-key to-tbl-rel-flds]} fk
           u-ids (map :u-id (cons muse muses))
           responses (hd/has-one u-ids (:db env) muse)
           map-fn (fn [batch-res]
                    (let [m (zipmap u-ids (repeat nil))
                          vals (zipmap (map to-key batch-res) batch-res)]
                      (merge m vals)))]
       (do-update-triggers! (get-in muse [:ctx :sl-ctx]) to-tbl-rel-flds
                            (count responses))
       (sl-api/unwrap map-fn responses)))))

(defn has-one
  "Resolves has-one relationship.
  e.g. (shopping_cart.user_id fk=> user.id)
  Parent: [shopping_cart].user_id => [user].id"
  [fk sgnl-fn-map ctx _args val]
  (resolve-error
   (with-superlifter (:sl-ctx ctx)
     (let [{:keys [from-key]
            {:keys [has-one]} :field-keys} fk]
       (sl-api/enqueue! has-one
                        (->HasOneDataSource (from-key val) fk sgnl-fn-map ctx))))))

(defrecord HasManyDataSource [u-id table-key table fk sgnl-fn-map ctx args]
  u/DataSource
  (-identity [this] (:u-id this))
  (-fetch [this env]
    (resolve-error-promise
     (let [responses (hd/has-many [(:u-id this)] (:db env) this)]
       (sl-api/unwrap identity responses))))

  u/BatchedSource
  (-fetch-multi [muse muses env]
    (resolve-error-promise
     (let [{:keys [from-key]} (:fk muse)
           {:keys [rel-flds]} (:table muse)
           u-ids (map :u-id (cons muse muses))
           responses (hd/has-many u-ids (:db env) muse)
           map-n-fn (fn [batch-res]
                      (let [m (zipmap u-ids (repeat []))
                            vals (group-by from-key batch-res)]
                        (merge-with concat m vals)))]
       (do-update-triggers! (get-in muse [:ctx :sl-ctx]) rel-flds
                            (count responses))
       (sl-api/unwrap map-n-fn responses)))))

(defn has-many
  "Resolves has-many relationship.
  e.g. (shopping_cart.user_id fk=> user.id)
  Parent values: [user].id => [shopping_cart].user_id"
  [table-key table fk sgnl-fn-map ctx args val]
  (resolve-error
   (with-superlifter (:sl-ctx ctx)
     (let [{:keys [to-key] {:keys [has-many]} :field-keys} fk]
       (sl-api/enqueue! has-many
                        (->HasManyDataSource (to-key val) table-key table
                                             fk sgnl-fn-map ctx args))))))

;; Aggregates

(defn aggregate-root
  "Resolves aggregation query at root level."
  [table-key ctx args _val]
  (resolve-error (hd/aggregate-root (:db ctx) table-key ctx args)))

(defrecord BatchAggregateDataSource [u-id table-key fk sgnl-fn-map ctx args]
  u/DataSource
  (-identity [this] (:u-id this))
  (-fetch [this env]
    (resolve-error-promise
     (let [responses (hd/aggregate-has-many [(:u-id this)] (:db env) this)]
       (sl-api/unwrap first responses))))

  u/BatchedSource
  (-fetch-multi [muse muses env]
    (resolve-error-promise
     (let [{:keys [from-key]} (:fk muse)
           u-ids (map :u-id (cons muse muses))
           responses (hd/aggregate-has-many u-ids (:db env) muse)
           map-n-fn (fn [batch-res]
                      (zipmap (map from-key batch-res) batch-res))]
       (sl-api/unwrap map-n-fn responses)))))

(defn aggregate-has-many
  "Resolves aggregation query for has-many relationship."
  [table-key fk sgnl-fn-map ctx args val]
  (resolve-error
   (with-superlifter (:sl-ctx ctx)
     (let [{:keys [to-key]
            {:keys [has-many-aggr]} :field-keys} fk]
       (sl-api/enqueue! has-many-aggr
                        (->BatchAggregateDataSource (to-key val) table-key fk
                                                    sgnl-fn-map ctx args))))))

;; Mutations

(defn create-root
  "Resolves create mutation."
  [table-key table sgnl-fn-map ctx args _val]
  (resolve-error
   (hd/create-root (:db ctx) table-key table sgnl-fn-map ctx args)))

(defn update-root
  "Resolves update mutation. Takes `pk_columns` parameter as a record identifier."
  [table-key col-keys sgnl-fn-map ctx args _val]
  (resolve-error
   (hd/update-root (:db ctx) table-key col-keys sgnl-fn-map ctx args)))

(defn delete-root
  "Resolves delete mutation. Takes `pk_columns` parameter as a record identifier."
  [table-key sgnl-fn-map ctx args _val]
  (resolve-error
   (hd/delete-root (:db ctx) table-key sgnl-fn-map ctx args)))

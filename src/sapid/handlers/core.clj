(ns sapid.handlers.core
  (:require [clojure.string :as s]
            [sapid.db :as db]
            [ring.middleware.params :as prm]))

(def ^:private operator-map
  {"eq"  :=
   "lt"  :<
   "le"  :<=
   "lte" :<=
   "gt"  :>
   "ge"  :>=
   "gte" :>=
   "ne"  :!=})

(defn- parse-filter-val [k v]
  (let [parts (s/split (str v) #":" 2)
        c (count parts)
        op (get operator-map (first parts))]
    (if (or (nil? op) (< c 2))
      [:= (keyword k) v]
      [op (keyword k) (second parts)])))

(defn- parse-order-by [m v]
  (let [parts (s/split v #":" 2)
        c (count parts)
        direc (second parts)]
    (-> m
        (assoc :order-col (keyword (first parts)))
        (assoc :direc (if (nil? direc) :desc (keyword direc))))))

(defn- query->filters [query cols]
  (reduce (fn [m [k v]]
            (cond
              (contains? cols k) (update m :filters conj (parse-filter-val k v))
              (= k "order-by") (parse-order-by m v)
              (= k "limit") (assoc m :limit v)
              (= k "offset") (assoc m :offset v)
              :else m))
          {:filters [] :limit 100 :offset 0}
          query))

(defn ring-query [req]
  (:query-params (prm/params-request req)))

;;; root

(defn list-root [query db-con table cols]
  (db/list-up db-con table (query->filters query cols)))

(defn create-root [params db-con table cols]
  (db/create! db-con table (select-keys params cols))
  nil)

(defn fetch-root [id query db-con table cols]
  (db/fetch db-con table id (query->filters query cols)))

(defn delete-root [id db-con table]
  (db/delete! db-con table id)
  nil)

(defn put-root [id params db-con table cols]
  (db/update! db-con table id (select-keys params cols))
  nil)

(defn patch-root [id params db-con table cols]
  (db/update! db-con table id (select-keys params cols))
  nil)

;;; one-n

(defn list-one-n [p-col p-id query db-con table cols]
  (let [filters (query->filters (assoc query p-col p-id) cols)]
    (db/list-up db-con table filters)))

(defn create-one-n [p-col p-id params db-con table cols]
  (let [params (-> (assoc params p-col p-id)
                   (select-keys cols))]
    (db/create! db-con table params)
    nil))

(defn fetch-one-n [id p-col p-id query db-con table cols]
  (let [filters (query->filters (assoc query p-col p-id) cols)]
    (db/fetch db-con table id filters)))

(defn delete-one-n [id p-col p-id db-con table]
  (db/delete! db-con table id p-col p-id)
  nil)

(defn put-one-n [id p-col p-id params db-con table cols]
  (db/update! db-con table id (select-keys params cols) p-col p-id)
  nil)

(defn patch-one-n [id p-col p-id params db-con table cols]
  (db/update! db-con table id (select-keys params cols) p-col p-id)
  nil)

;;; n-n

(defn list-n-n [nn-join-col nn-p-col p-id query db-con nn-table table cols]
  (let [nn-link-col (str "nn." nn-p-col)
        cols (conj cols nn-link-col)
        filters (query->filters (assoc query nn-link-col p-id) cols)]
    (db/list-through db-con table nn-table nn-join-col filters)))

(defn create-n-n [col-a id-a col-b id-b params db-con table cols]
  (let [params (-> params (assoc col-a id-a) (assoc col-b id-b) (select-keys cols))]
    (db/create! db-con table params)
    nil))

(defn delete-n-n [col-a id-a col-b id-b db-con table cols]
  (let [filters (query->filters {col-a id-a col-b id-b} cols)]
    (db/delete-where! db-con table filters)
    nil))


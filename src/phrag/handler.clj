(ns phrag.handler
  (:require [phrag.db :as db]))

;;; GraphQL args to SQL params

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

(defn args->sql-params [args]
  (reduce (fn [m [k v]]
            (cond
              (= k :sort) (parse-sort m v)
              (= k :limit) (assoc m :limit v)
              (= k :offset) (assoc m :offset v)
              :else m))
          {:where (parse-where args):limit 100 :offset 0}
          args))

;;; DB handlers

(defn list-root [db-con table params]
  (db/list-up db-con table params))

(defn list-partitioned [db-con table p-col-key pk-keys params]
  (db/list-partitioned db-con table p-col-key
                       (:order-col params (first pk-keys))
                       (:direc params :asc) params))

(def ^:private sqlite-last-id
  (keyword "last_insert_rowid()"))

(defn- created-id [res-map]
  (some #(% res-map) [sqlite-last-id :id]))

(defn- return-keys [result pks]
  (let [res-map (first result)]
    (prn result (set pks))
    (if (contains? (set pks) :id)
      (assoc res-map :id (created-id res-map))
      res-map)))

(defn create-root [params db-con table-key pk-keys]
  (let [opts {:return-keys pk-keys}
        result (db/create! db-con table-key params opts)]
   (return-keys result pk-keys)))

(defn delete-root [pk-map db-con table]
  (db/delete! db-con table pk-map)
  nil)

(defn patch-root [pk-map params db-con table]
  (db/update! db-con table pk-map params)
  nil)

(defn aggregate-root [db-con table aggrs params]
  (db/aggregate db-con table aggrs params))

(defn aggregate-grp-by [db-con table aggrs grp-by params]
  (db/aggregate-grp-by db-con table aggrs grp-by params))

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

(defn create-root [params db-con table cols]
  (let [opts {:return-keys (if (contains? cols :id) ["id"] nil)}]
    (db/create! db-con table params opts)))

(defn delete-root [id db-con table]
  (db/delete! db-con table id)
  nil)

(defn patch-root [id params db-con table _cols]
  (db/update! db-con table id params)
  nil)

(defn aggregate-root [db-con table aggrs params]
  (db/aggregate db-con table aggrs params))

(defn aggregate-grp-by [db-con table aggrs grp-by params]
  (db/aggregate-grp-by db-con table aggrs grp-by params))

;; n-n

(defn create-n-n [col-a id-a col-b id-b params db-con table cols]
  (let [params (-> params (assoc col-a id-a) (assoc col-b id-b))
        opts {:return-keys (if (contains? cols :id) ["id"] nil)}]
    (db/create! db-con table params opts)
    nil))

(defn delete-n-n [col-a id-a col-b id-b db-con table]
  (let [where-a [:= col-a id-a]
        where-b [:= col-b id-b]
        params {:where [where-a where-b]}]
    (db/delete-where! db-con table params)
    nil))

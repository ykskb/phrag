(ns phrag.handlers.core
  (:require [phrag.db :as db]))

;;; root

(defn list-root [db-con table filters]
  (db/list-up db-con table filters))

(defn create-root [params db-con table cols]
  (db/create! db-con table (select-keys params cols)))

(defn fetch-root [id db-con table filters]
  (db/fetch db-con table id filters))

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

(defn list-one-n [p-col p-id db-con table filters]
  (let [filters (update filters :where conj [:= (keyword p-col) p-id])]
    (db/list-up db-con table filters)))

(defn create-one-n [p-col p-id params db-con table cols]
  (let [params (-> (assoc params p-col p-id)
                   (select-keys cols))]
    (db/create! db-con table params)
    nil))

(defn fetch-one-n [id p-col p-id db-con table filters]
  (let [filters (update filters :where conj [:= (keyword p-col) p-id])]
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

(defn list-n-n [nn-join-col nn-p-col p-ids db-con nn-table table filters]
  (let [nn-link-col (str "nn." nn-p-col)
        filters (update filters :where conj [:in (keyword nn-link-col) p-ids])]
    (db/list-through db-con table nn-table nn-join-col filters)))

(defn create-n-n [col-a id-a col-b id-b params db-con table cols]
  (let [params (-> params (assoc col-a id-a) (assoc col-b id-b)
                   (select-keys cols))]
    (db/create! db-con table params)
    nil))

(defn delete-n-n [col-a id-a col-b id-b db-con table]
  (let [where-a [:= col-a id-a]
        where-b [:= col-b id-b]
        filters {:where [where-a where-b]}]
    (db/delete-where! db-con table filters)
    nil))

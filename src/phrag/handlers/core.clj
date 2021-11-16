(ns phrag.handlers.core
  (:require [phrag.db :as db]))

;;; root

(defn list-root [db-con table filters]
  (db/list-up db-con table filters))

(defn create-root [params db-con table cols]
  (let [opts {:return-keys (if (contains? cols "id") ["id"] nil)}]
    (db/create! db-con table (select-keys params cols) opts)))

(defn delete-root [id db-con table]
  (db/delete! db-con table id)
  nil)

(defn patch-root [id params db-con table cols]
  (db/update! db-con table id (select-keys params cols))
  nil)

;;; n-n

(defn create-n-n [col-a id-a col-b id-b params db-con table cols]
  (let [params (-> params (assoc col-a id-a) (assoc col-b id-b)
                   (select-keys cols))
        opts {:return-keys (if (contains? cols "id") ["id"] nil)}]
    (db/create! db-con table params opts)
    nil))

(defn delete-n-n [col-a id-a col-b id-b db-con table]
  (let [where-a [:= col-a id-a]
        where-b [:= col-b id-b]
        filters {:where [where-a where-b]}]
    (db/delete-where! db-con table filters)
    nil))

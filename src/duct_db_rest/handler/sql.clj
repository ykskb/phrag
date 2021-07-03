(ns duct-db-rest.handler.sql
  (:require [ataraxy.response :as response]
            [duct-db-rest.boundary.db.core :as db]
            [integrant.core :as ig]))

(defn- rsc->id-key [rsc]
  (keyword (str rsc "_id")))

(defmethod ig/init-key ::list-root [_ {:keys [db table cols]}]
  (fn [{[_ query] :ataraxy/result}]
    (println query)
    (let [res (db/list-resource db table)]
      [::response/ok res])))

(defmethod ig/init-key ::create-root [_ {:keys [db table cols]}]
  (fn [{[_ params] :ataraxy/result}]
    (println (db/create db table params))
    [::response/ok {:result "CREATED"}]))

(defmethod ig/init-key ::list-one-n [_ {:keys [db table p-col cols]}]
  (fn [{[_ p-id {:as query}] :ataraxy/result}]
    (println query p-id)
    (let [res (db/list-resource db table)]
      [::response/ok res])))

(defmethod ig/init-key ::create-one-n [_ {:keys [db table p-col cols]}]
  (fn [{[_ p-id {:as params}] :ataraxy/result}]
    (println params)
    (let [params (assoc params p-col p-id)
          res (db/create db table params)]
      [::response/ok res])))

(defmethod ig/init-key ::create-n-n [_ {:keys [db table col-a col-b cols]}]
  (fn [{[_ id-a id-b {:as params}] :ataraxy/result}]
    (println params)
    (let [params (-> params (assoc col-a id-a) (assoc col-b id-b))]
      (println params table)
      [::response/ok (db/create db table params)])))

(defmethod ig/init-key ::static [_ {:keys [db]}]
  (fn [{[c] :ataraxy/result}]
    (let [res (db/get-db-schema db)]
      [::response/ok res])))

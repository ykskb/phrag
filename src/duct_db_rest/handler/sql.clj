(ns duct-db-rest.handler.sql
  (:require [ataraxy.response :as response]
            [duct-db-rest.boundary.db.core :as db]
            [integrant.core :as ig]))

(defmethod ig/init-key ::list-root [_ {:keys [db rsc cols]}]
  (fn [{[_ query] :ataraxy/result}]
    (println query)
    (let [res (db/list-resource db rsc)]
      [::response/ok res])))

(defmethod ig/init-key ::create-root [_ {:keys [db rsc cols]}]
  (fn [{[_ body] :ataraxy/result}]
    (println (db/create db rsc body))
    [::response/ok {:result "CREATED"}]))

(defmethod ig/init-key ::list-one-n [_ {:keys [db rsc cols p-rsc]}]
  (fn [{[_ p-id {:as query}] :ataraxy/result}]
    (println query p-id)
    (let [res (db/list-resource db rsc)]
      [::response/ok res])))

(defmethod ig/init-key ::create-one-n [_ {:keys [db rsc cols p-rsc]}]
  (fn [{[_ p-id {:as body}] :ataraxy/result}]
    (println body)
    (let [p-key (keyword (str p-rsc "_id"))
          params (assoc body p-key p-id)
          res (db/create db rsc params)]
      [::response/ok res])))

(defmethod ig/init-key ::static [_ {:keys [db]}]
  (fn [{[c] :ataraxy/result}]
    (let [res (db/get-db-schema db)]
      [::response/ok res])))

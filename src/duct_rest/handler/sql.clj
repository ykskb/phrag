(ns duct-rest.handler.sql
  (:require [ataraxy.core :as ataraxy]
            [ataraxy.response :as response]
            [duct-rest.boundary.db.core :as db]
            [integrant.core :as ig]))

(defmethod ig/init-key ::list [_ {:keys [db rsc cols]}]
    (fn [{[_ query] :ataraxy/result}]
      (println query)
      (let [res (db/list-resource db rsc)]
        [::response/ok res])))

(defmethod ig/init-key ::create [_ {:keys [db rsc cols]}]
  (fn [{[_ body] :ataraxy/ersult}]
    (println body)
    [::response/ok {:result "CREATED"}]))

(defmethod ig/init-key ::static [_ {:keys [db]}]
  (fn [{[c] :ataraxy/result}]
    (let [res (db/get-db-schema db)]
      [::response/ok res])))

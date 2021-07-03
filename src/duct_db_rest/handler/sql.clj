(ns duct-db-rest.handler.sql
  (:require [ataraxy.response :as response]
            [duct-db-rest.boundary.db.core :as db]
            [integrant.core :as ig]))

(defn- rsc->id-key [rsc]
  (keyword (str rsc "_id")))

(defmethod ig/init-key ::list-root [_ {:keys [db rsc cols]}]
  (fn [{[_ query] :ataraxy/result}]
    (println query)
    (let [res (db/list-resource db rsc)]
      [::response/ok res])))

(defmethod ig/init-key ::create-root [_ {:keys [db rsc cols]}]
  (fn [{[_ params] :ataraxy/result}]
    (println (db/create db rsc params))
    [::response/ok {:result "CREATED"}]))

(defmethod ig/init-key ::list-one-n [_ {:keys [db rsc cols p-rsc]}]
  (fn [{[_ p-id {:as query}] :ataraxy/result}]
    (println query p-id)
    (let [res (db/list-resource db rsc)]
      [::response/ok res])))

(defmethod ig/init-key ::create-one-n [_ {:keys [db rsc cols p-rsc]}]
  (fn [{[_ p-id {:as params}] :ataraxy/result}]
    (println params)
    (let [params (assoc params (rsc->id-key p-rsc) p-id)
          res (db/create db rsc params)]
      [::response/ok res])))

(defmethod ig/init-key ::create-n-n [_ {:keys [db rsc cols rsc-a rsc-b]}]
  (fn [{[_ id-a id-b {:as params}] :ataraxy/result}]
    (println params)
    (let [params (-> params
                     (assoc (rsc->id-key rsc-a) id-a)
                     (assoc (rsc->id-key rsc-b) id-b))
          rsc (str rsc-a "_" rsc-b)]
      (println params rsc)
      [::response/ok (db/create db rsc params)])))

(defmethod ig/init-key ::static [_ {:keys [db]}]
  (fn [{[c] :ataraxy/result}]
    (let [res (db/get-db-schema db)]
      [::response/ok res])))

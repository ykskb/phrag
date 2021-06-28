(ns duct-rest.handler.sql
  (:require [ataraxy.core :as ataraxy]
            [ataraxy.response :as response]
            [duct-rest.module.sql :as duct-rest]
            [integrant.core :as ig]))


(defmethod ig/init-key ::list [_ {:keys [db]}]
  (println "list built")
    (fn [{[_ query] :ataraxy/result}]
      (let [res (duct-rest/get-db-schema db)]
        (println "query")
        (println query)
        (println "db:" db)
        [::response/ok res])))

(defmethod ig/init-key ::static [_ {:keys [db]}]
  (println "a built")
  (fn [{[_] :ataraxy/result}]
    (let [res (duct-rest/get-db-schema db)]
      [::response/ok res])))

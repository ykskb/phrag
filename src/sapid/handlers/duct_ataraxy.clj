(ns sapid.handlers.duct-ataraxy
  (:require [ataraxy.response :as atr-res]
            [clojure.walk :as w]
            [sapid.handlers.core :as c]
            [sapid.qs :as qs]
            [integrant.core :as ig]))

;;; root

(defmethod ig/init-key ::list-root [_ {:keys [db db-keys table cols]}]
  (let [db-con (get-in db db-keys)]
    (fn [{[_ query] :ataraxy/result}]
      (let [filters (qs/query->filters query cols)]
        [::atr-res/ok (c/list-root db-con table filters)]))))

(defmethod ig/init-key ::create-root [_ {:keys [db db-keys table cols]}]
  (let [db-con (get-in db db-keys)]
    (fn [{[_ params] :ataraxy/result}]
      [::atr-res/ok (c/create-root (w/stringify-keys params)
                                 db-con table cols)])))

(defmethod ig/init-key ::fetch-root [_ {:keys [db db-keys table cols]}]
  (let [db-con (get-in db db-keys)]
    (fn [{[_ id {:as query}] :ataraxy/result}]
      (let [filters (qs/query->filters query cols)]
      [::atr-res/ok (c/fetch-root id db-con table filters)]))))

(defmethod ig/init-key ::delete-root [_ {:keys [db db-keys table _cols]}]
  (let [db-con (get-in db db-keys)]
    (fn [{[_ id] :ataraxy/result}]
      [::atr-res/ok (c/delete-root id db-con table)])))

(defmethod ig/init-key ::put-root [_ {:keys [db db-keys table cols]}]
  (let [db-con (get-in db db-keys)]
    (fn [{[_ id {:as params}] :ataraxy/result}]
      [::atr-res/ok (c/put-root id (w/stringify-keys params)
                              db-con table cols)])))

(defmethod ig/init-key ::patch-root [_ {:keys [db db-keys table cols]}]
  (let [db-con (get-in db db-keys)]
    (fn [{[_ id {:as params}] :ataraxy/result}]
      [::atr-res/ok (c/patch-root id (w/stringify-keys params)
                                db-con table cols)])))

;;; one-n

(defmethod ig/init-key ::list-one-n [_ {:keys [db db-keys table p-col cols]}]
  (let [db-con (get-in db db-keys)]
    (fn [{[_ p-id {:as query}] :ataraxy/result}]
      (let [filters (qs/query->filters query cols)]
        [::atr-res/ok (c/list-one-n p-col p-id db-con table filters)]))))

(defmethod ig/init-key ::create-one-n [_ {:keys [db db-keys table p-col cols]}]
  (let [db-con (get-in db db-keys)]
    (fn [{[_ p-id {:as params}] :ataraxy/result}]
      [::atr-res/ok (c/create-one-n p-col p-id (w/stringify-keys params)
                                  db-con table cols)])))

(defmethod ig/init-key ::fetch-one-n [_ {:keys [db db-keys table p-col cols]}]
  (let [db-con (get-in db db-keys)]
    (fn [{[_ p-id id {:as query}] :ataraxy/result}]
      (let [filters (qs/query->filters query cols)]
        [::atr-res/ok (c/fetch-one-n id p-col p-id db-con table filters)]))))

(defmethod ig/init-key ::delete-one-n [_ {:keys [db db-keys table p-col cols]}]
  (let [db-con (get-in db db-keys)]
    (fn [{[_ p-id id] :ataraxy/result}]
      [::atr-res/ok (c/delete-one-n id p-col p-id db-con table)])))

(defmethod ig/init-key ::put-one-n [_ {:keys [db db-keys table p-col cols]}]
  (let [db-con (get-in db db-keys)]
    (fn [{[_ p-id id {:as params}] :ataraxy/result}]
      [::atr-res/ok (c/put-one-n id p-col p-id (w/stringify-keys params)
                               db-con table cols)])))

(defmethod ig/init-key ::patch-one-n [_ {:keys [db db-keys table p-col cols]}]
  (let [db-con (get-in db db-keys)]
    (fn [{[_ p-id id {:as params}] :ataraxy/result}]
      [::atr-res/ok (c/patch-one-n id p-col p-id (w/stringify-keys params)
                                 db-con table cols)])))

;;; n-n

(defmethod ig/init-key ::list-n-n
  [_ {:keys [db db-keys table nn-table nn-join-col nn-p-col cols]}]
  (let [db-con (get-in db db-keys)]
    (fn [{[_ p-id {:as query}] :ataraxy/result}]
      (let [filters (qs/query->filters query cols)]
        [::atr-res/ok (c/list-n-n nn-join-col nn-p-col p-id
                              db-con nn-table table filters)]))))

(defmethod ig/init-key ::create-n-n
  [_ {:keys [db db-keys table col-a col-b cols]}]
  (let [db-con (get-in db db-keys)]
    (fn [{[_ id-a id-b {:as params}] :ataraxy/result}]
      [::atr-res/ok (c/create-n-n col-a id-a col-b id-b (w/stringify-keys params)
                                db-con table cols)])))

(defmethod ig/init-key ::delete-n-n
  [_ {:keys [db db-keys table col-a col-b _cols]}]
  (let [db-con (get-in db db-keys)]
    (fn [{[_ id-a id-b] :ataraxy/result}]
        [::atr-res/ok (c/delete-n-n col-a id-a col-b id-b db-con table)])))

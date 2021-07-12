(ns lapis.handler
  (:require [ataraxy.response :as response]
            [clojure.string :as s]
            [lapis.db :as db]
            [integrant.core :as ig]))

(def ^:private operator-map
  {"eq"  :=
   "lt"  :<
   "le"  :<=
   "lte" :<=
   "gt"  :>
   "ge"  :>=
   "gte" :>=
   "ne"  :!=})

(defn- parse-filter-val [k v]
  (let [parts (s/split v #":" 2)
        op (get operator-map (first parts))]
    (if (or (nil? op) (< (count parts) 2))
      [:= (keyword k) v]
      [op (keyword k) (second parts)])))

(defn- query->filters [query cols]
  (reduce (fn [vec [k v]]
            (if (contains? cols k)
              (conj vec (parse-filter-val k v))
              vec))
          []
          query))

;; root

(defmethod ig/init-key ::list-root [_ {:keys [db db-keys table cols]}]
  (let [db-con (get-in db db-keys)]
  (fn [{[_ query] :ataraxy/result}]
    (let [res (db/list db-con table (query->filters query cols))]
      [::response/ok res]))))

(defmethod ig/init-key ::create-root [_ {:keys [db db-keys table cols]}]
  (let [db-con (get-in db db-keys)]
    (fn [{[_ params] :ataraxy/result}]
      (db/create! db-con table params)
      [::response/ok])))

(defmethod ig/init-key ::fetch-root [_ {:keys [db db-keys table cols]}]
  (let [db-con (get-in db db-keys)]
    (fn [{[_ id {:as query}] :ataraxy/result}]
      (let [res (db/fetch db-con table id (query->filters query cols))]
        [::response/ok (first res)]))))

(defmethod ig/init-key ::delete-root [_ {:keys [db db-keys table cols]}]
  (let [db-con (get-in db db-keys)]
    (fn [{[_ id] :ataraxy/result}]
      (db/delete! db-con table id)
      [::response/ok])))

(defmethod ig/init-key ::put-root [_ {:keys [db db-keys table cols]}]
  (let [db-con (get-in db db-keys)]
    (fn [{[_ id {:as params}] :ataraxy/result}]
      ; TODO: params to be cover all the attributes as PUT updates all attributes
      (db/update! db table id params)
      [::response/ok])))

(defmethod ig/init-key ::patch-root [_ {:keys [db db-keys table cols]}]
  (let [db-con (get-in db db-keys)]
    (fn [{[_ id {:as params}] :ataraxy/result}]
      (db/update! db-con table id params)
      [::response/ok])))

;; one-n

(defmethod ig/init-key ::list-one-n [_ {:keys [db db-keys table p-col cols]}]
  (let [db-con (get-in db db-keys)]
    (fn [{[_ p-id {:as query}] :ataraxy/result}]
      (println query p-id)
      (let [filters (query->filters (assoc query p-col p-id) cols)
            res (db/list db-con table filters)]
        [::response/ok res]))))

(defmethod ig/init-key ::create-one-n [_ {:keys [db db-keys table p-col cols]}]
  (let [db-con (get-in db db-keys)]
    (fn [{[_ p-id {:as params}] :ataraxy/result}]
      (println params)
      (let [params (assoc params p-col p-id)
            res (db/create! db-con table params)]
        [::response/ok]))))

(defmethod ig/init-key ::fetch-one-n [_ {:keys [db db-keys table p-col cols]}]
  (let [db-con (get-in db db-keys)]
    (fn [{[_ p-id id {:as query}] :ataraxy/result}]
      (let [filters (query->filters (assoc query p-col p-id) cols)
            res (db/fetch db-con table id filters)]
        [::response/ok (first res)]))))

(defmethod ig/init-key ::delete-one-n [_ {:keys [db db-keys table p-col cols]}]
  (let [db-con (get-in db db-keys)]
    (fn [{[_ p-id id] :ataraxy/result}]
      ; TODO: handle p-id check
      (let [res (db/delete! db-con table id p-col p-id)]
        [::response/ok]))))

(defmethod ig/init-key ::put-one-n [_ {:keys [db db-keys table p-col cols]}]
  (let [db-con (get-in db db-keys)]
    (fn [{[_ p-id id {:as params}] :ataraxy/result}]
      ; TODO: params to be cover all the attributes for PUT 
      (println "ID: " id "DATA:" params)
      (println (db/update! db-con table id params p-col p-id))
      [::response/ok])))

(defmethod ig/init-key ::patch-one-n [_ {:keys [db db-keys table p-col cols]}]
  (let [db-con (get-in db db-keys)]
    (fn [{[_ p-id id {:as params}] :ataraxy/result}]
      (println (db/update! db-con table id params p-col p-id))
      [::response/ok])))

;; n-n

(defmethod ig/init-key ::create-n-n
  [_ {:keys [db db-keys table col-a col-b cols]}]
  (let [db-con (get-in db db-keys)]
    (fn [{[_ id-a id-b {:as params}] :ataraxy/result}]
      (println params)
      (let [params (-> params (assoc col-a id-a) (assoc col-b id-b))]
        (println params table)
        [::response/ok (db/create! db-con table params)]))))

(defmethod ig/init-key ::delete-n-n
  [_ {:keys [db db-keys table col-a col-b cols]}]
  (let [db-con (get-in db db-keys)]
    (fn [{[_ id-a id-b] :ataraxy/result}]
      (let [filters (query->filters {col-a id-a col-b id-b} cols)]
        [::response/ok (db/delete-where! db-con table filters)]))))

(defmethod ig/init-key ::static [_ {:keys [db]}]
  (fn [{[c] :ataraxy/result}]
    (let [res (db/get-db-schema db)]
      [::response/ok res])))

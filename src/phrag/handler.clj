(ns phrag.handler
  "Handles arguments and DB operations for Phrag's GraphQL resolvers."
  (:require [clojure.set :as clj-set]
            [phrag.db :as db]))


;; GraphQL args to SQL params

(defn lacinia-selections [ctx]
  (get-in ctx [:com.walmartlabs.lacinia/selection :selections]))

(defn- query-fields [ctx]
  (let [selections (lacinia-selections ctx)]
    (set (map :field-name selections))))

(defn- parse-selects [col-keys ctx]
  (let [q-fields (query-fields ctx)]
    (clj-set/intersection q-fields col-keys)))

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
  (concat [op] (reduce (fn [v rsc-where]
                         (concat v (parse-rsc-where rsc-where)))
                       [] rsc-where-list)))

(defn- parse-where [args]
  (let [whr (:where args)]
    (cond-> (parse-rsc-where (dissoc whr :and :or))
      (some? (:or whr)) (conj (parse-and-or :or (:or whr)))
      (some? (:and whr)) (conj (parse-and-or :and (:and whr))))))

(defn- update-sort [m v]
  (assoc m :sort (reduce-kv (fn [vec k v]
                              (conj vec [k v]))
                            [] v)))

(defn args->sql-params [col-keys args ctx]
  (let [default-limit (:default-limit ctx)]
    (reduce (fn [m [k v]]
              (cond
                (= k :sort) (update-sort m v)
                (= k :limit) (assoc m :limit v)
                (= k :offset) (assoc m :offset v)
                :else m))
            (cond-> {:select (parse-selects col-keys ctx)
                     :where (parse-where args)
                     :offset 0}
              (integer? default-limit) (assoc :limit default-limit))
            args)))

;; DB handlers

(defn list-root [db-con table params]
  (db/list-up db-con table params))

(defn list-partitioned [db-con table p-col-key pk-keys params]
  (let [sort-params (:sort params [[(first pk-keys) :asc]])]
    (db/list-partitioned db-con table p-col-key
                         (assoc params :sort sort-params))))

(def ^:private sqlite-last-id
  (keyword "last_insert_rowid()"))

(defn- update-sqlite-pk [res-map pks]
  (if (= (count pks) 1)
    (assoc res-map (first pks) (sqlite-last-id res-map))
    res-map))

(defn create-root
  "Creates root object and attempts to return primary keys. `last_insert_rowid`
  is checked and replaced with first primary key in case of SQLite."
  [params db-con table-key pk-keys]
  (let [opts {:return-keys pk-keys}
        result (first (db/create! db-con table-key params opts))]
    (if (contains? result sqlite-last-id)
      (update-sqlite-pk result pk-keys)
      result)))

(defn delete-root [pk-map db-con table]
  (db/delete! db-con table pk-map)
  nil)

(defn patch-root [pk-map params db-con table]
  (db/update! db-con table pk-map params)
  nil)

(defn aggregate-root [db-con table aggrs params]
  (db/aggregate db-con table aggrs params))

(defn aggregate-grp-by [db-con table aggrs grp-by params]
  (db/aggregate-grp-by db-con table aggrs grp-by params))

(ns phrag.handler
  "Handles arguments and DB operations for Phrag's GraphQL resolvers."
  (:require [clojure.set :as clj-set]
            [clojure.walk :as w]
            [clojure.pprint :as pp]
            [phrag.field :as fld]
            [phrag.db :as db]))

;; GraphQL args to SQL params

(def ^:private where-ops
  {:eq  :=
   :gt  :>
   :lt  :<
   :gte :>=
   :lte :<=
   :ne  :!=
   :in :in
   :like :like})

(defn- parse-selects [col-keys selection]
  (let [q-fields (set (map :field-name selection))]
    (clj-set/intersection q-fields col-keys)))

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

(defn args->sql-params [col-keys args selections default-limit]
  (reduce (fn [m [k v]]
            (cond
              (= k :sort) (update-sort m v)
              (= k :limit) (assoc m :limit v)
              (= k :offset) (assoc m :offset v)
              :else m))
          (cond-> {:select (parse-selects col-keys selections)
                   :where (parse-where args)}
            (integer? default-limit) (assoc :limit default-limit))
          args))

;; Aggregates

(defn- aggr-fields [selections]
  (reduce (fn [m selected]
            (let [aggr (:field-name selected)]
              (if (= :count aggr)
                (assoc m :count nil)
                (assoc m aggr (map :field-name (:selections selected))))))
          {} selections))

(defn- aggr-key [aggr-type col]
  (keyword (str (name aggr-type) "_" (name col))))

(defn- aggr-selects [fields]
  (reduce (fn [v [aggr cols]]
            (if (= :count aggr)
              (conj v [[:count :*] :count])
              (concat v (map (fn [c] [[aggr c] (aggr-key aggr c)]) cols))))
          [] fields))

(defn- aggr-result [fields sql-res & [id-key id]]
  (reduce (fn [m [aggr cols]]
            (if (= :count aggr)
              (assoc m aggr (:count sql-res))
              (assoc m aggr (reduce (fn [m col]
                                      (assoc m col ((aggr-key aggr col) sql-res)))
                             {} cols))))
          (or sql-res {id-key id})
          fields))

(defn- aggr-many-result [fields sql-multi-res id-key ids]
  (let [multi-res-map (zipmap (map #(id-key %) sql-multi-res) sql-multi-res)]
    (map #(aggr-result fields (get multi-res-map %) id-key %) ids)))

;; Interceptor Signal

(defn signal [args sgnl-fns ctx]
  (reduce (fn [args sgnl-fn]
            (sgnl-fn args ctx))
          args sgnl-fns))

;;; Resolve handlers

;; Query

(defn list-partitioned-query [db table p-col-key pk-keys params]
  (let [sort-params (:sort params [[(first pk-keys) :asc]])]
    (db/list-partitioned db table p-col-key
                         (assoc params :sort sort-params))))

(defn- query-has-many [parents nest-fk table ctx args selection]
  (let [{:keys [to from from-table]} nest-fk
        {:keys [pk-keys col-keys rel-cols query-signals]} table
        p-ids (map to parents)
        sql-params (-> (args->sql-params col-keys args (:selections selection)
                                         (:default-limit ctx))
                       (signal (:pre query-signals) ctx)
                       (update :select into rel-cols)
                       (update :where conj [:in from p-ids]))]
    (-> (cond
          (< (count p-ids) 1) nil
          (or (> (:limit sql-params 0) 0)
              (> (:offset sql-params 0) 0))
          (list-partitioned-query (:db ctx) from-table from pk-keys sql-params)
          :else (db/list-up (:db ctx) from-table sql-params))
        (signal (:post query-signals) ctx))))

(defn- query-has-many-aggr [parents nest-fk table ctx args selection]
  (let [{:keys [query-signals]} table
        {:keys [to from from-table]} nest-fk
        p-ids (map to parents)
        fields (aggr-fields (:selections selection))
        selects (aggr-selects fields)
        sql-params (-> (args->sql-params nil args nil nil)
                       (signal (:pre query-signals) ctx)
                       (update :where conj [:in from p-ids]))
        sql-res (-> (db/aggregate-grp-by (:db ctx) from-table selects from
                                         sql-params)
                    (signal (:post query-signals) ctx))]
    (aggr-many-result fields sql-res from p-ids)))

(defn- query-has-one [parents nest-fk table ctx selection]
  (let [{:keys [from to]} nest-fk
        {:keys [col-keys rel-cols query-signals]} table
        p-ids (map from parents)
        sql-params (-> (args->sql-params col-keys nil (:selections selection)
                                         (:default-limit ctx))
                       (signal (:pre query-signals) ctx)
                       (update :select into rel-cols)
                       (assoc :where [[:in to p-ids]]))]
    (-> (db/list-up (:db ctx) (:table nest-fk) sql-params)
        (signal (:post query-signals) ctx))))

(defn- query-nest [parents table-key selection nest-fk ctx]
  (let [nest-type (:type nest-fk)
        nest-table (get-in ctx [:tables table-key])
        args (:arguments selection)]
    (cond
      (= nest-type :has-one) (query-has-one parents nest-fk nest-table ctx
                                            selection)
      (= nest-type :has-many) (query-has-many parents nest-fk nest-table
                                            ctx args selection)
      (= nest-type :has-many-aggr) (query-has-many-aggr parents nest-fk nest-table
                                                        ctx args selection))))

(defn- map-has-one [parents field-key nest-fk query-res]
  (let [{:keys [from to]} nest-fk
        nest-map (zipmap (map to query-res) query-res)]
    (map #(assoc % field-key (get nest-map (from %))) parents)))

(defn- map-has-many [parents field-key nest-fk query-res]
  (let [{:keys [from to]} nest-fk
        p-ids (map to parents)
        nest-def (zipmap p-ids (repeat []))
        vals (group-by from query-res)
        nest-map (merge-with concat nest-def vals)]
    (map #(assoc % field-key (get nest-map (to %))) parents)))

(defn- map-has-many-aggr [parents field-key nest-fk query-res]
  (let [{:keys [from to]} nest-fk
        res-map (zipmap (map from query-res) query-res)]
    (map #(assoc % field-key (get res-map (to %))) parents)))

(defn- map-nest [parents field-key nest-fk query-res]
  (let [nest-type (:type nest-fk)
        map-fn (cond (= nest-type :has-one) map-has-one
                     (= nest-type :has-many) map-has-many
                     (= nest-type :has-many-aggr) map-has-many-aggr)]
    (map-fn parents field-key nest-fk query-res)))

(def ^:private aggr-keys
  #{:avg :count :max :min :sum})

(defn- resolve-nests [table-key res selection ctx]
  (let [nest-fks (get-in ctx [:relation-ctx :nest-fks table-key])]
    (reduce (fn [r slct]
              (let [field-key (get-in slct [:field-definition :field-name])]
                (if (or (:leaf? slct) (contains? aggr-keys field-key))
                  r
                  (let [nest-fk (field-key nest-fks)
                        nest-table-key (if (= :has-one (:type nest-fk))
                                         (:table nest-fk)
                                         (:from-table nest-fk))
                        query-res (query-nest r nest-table-key slct nest-fk ctx)
                        nested (resolve-nests nest-table-key query-res slct ctx)]
                    (map-nest r field-key nest-fk nested)))))
            res
            (:selections selection))))

(defn resolve-query [table-key table ctx args]
  (let [{:keys [col-keys rel-cols query-signals]} table
        selection (:com.walmartlabs.lacinia/selection ctx)
        sql-params (-> (args->sql-params col-keys args (:selections selection)
                                         (:default-limit ctx))
                       (signal (:pre query-signals) ctx)
                       (update :select into rel-cols))
        res (-> (db/list-up (:db ctx) table-key sql-params)
                (signal (:post query-signals) ctx))]
    (resolve-nests table-key res selection ctx)))

(defn aggregate-root [table-key ctx args]
  (let [sql-args (args->sql-params nil args nil nil)
        selections (get-in ctx [:com.walmartlabs.lacinia/selection :selections])
        fields (aggr-fields selections)
        selects (aggr-selects fields)
        res (first (db/aggregate (:db ctx) table-key selects sql-args))]
    (aggr-result fields res)))

;;; Mutation

(def ^:private sqlite-last-id
  (keyword "last_insert_rowid()"))

(defn- update-sqlite-pk [res-map pks]
  (if (= (count pks) 1) ; only update single pk
    (assoc res-map (first pks) (sqlite-last-id res-map))
    res-map))

(defn create-root
  "Creates root object and attempts to return primary keys. `last_insert_rowid`
  is checked and replaced with first primary key in case of SQLite."
  [table-key table ctx args]
  (let [{:keys [pk-keys col-keys create-signals]} table
        params (-> (select-keys args col-keys)
                   (signal (:pre create-signals) ctx)
                   (w/stringify-keys))
        opts {:return-keys pk-keys}
        sql-res (first (db/create! (:db ctx) table-key params opts))
        id-res (if (contains? sql-res sqlite-last-id)
                 (update-sqlite-pk sql-res pk-keys)
                 sql-res)
        res (merge (w/keywordize-keys params) id-res)]
    (signal res (:post create-signals) ctx)))

(defn update-root [table-key table ctx args]
  (let [{:keys [col-keys update-signals]} table
        sql-args (-> (select-keys args col-keys)
                     (assoc :pk_columns (:pk_columns args))
                     (signal (:pre update-signals) ctx))
        params (-> (dissoc sql-args :pk_columns)
                   (w/stringify-keys))]
    (db/update! (:db ctx) table-key (:pk_columns sql-args) params)
    (signal fld/result-true-object (:post update-signals) ctx)))

(defn delete-root [table-key table ctx args]
  (let [{:keys [delete-signals]} table
        sql-args (signal args (:pre delete-signals) ctx)]
    (db/delete! (:db ctx) table-key (:pk_columns sql-args))
    (signal fld/result-true-object (:post delete-signals) ctx)))

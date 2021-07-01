(ns duct-db-rest.module.sql
  (:require [clojure.string :as s]
            [duct.core :as core]
            [duct-db-rest.boundary.db.core :as db]
            [integrant.core :as ig]
            [clojure.pprint :as pp]))

(defn resource-handler-key [project-ns action]
  (let [ns (str project-ns ".handler.sql")]
    (keyword ns action)))

(defn resource-route-key [project-ns resource action]
  (let [ns (str project-ns ".handler.sql." resource)]
    (keyword ns action)))

(defn resource-handler-map [handler-key route-key opts]
  (derive route-key handler-key)
  {[handler-key route-key] opts})

(defn resource-route-map [path route-key param-names]
  (if (coll? param-names)
    {path (into [] (concat [route-key] param-names))}
    {path [route-key]}))

(defn root-config [table ns db-ref]
  (let [rsc (:name table)
        opts {:db db-ref :rsc rsc}]
    (reduce
     (fn [m [action path param-names]]
       (let [route-key (resource-route-key ns rsc action)
             handler-key (resource-handler-key ns action)] 
         (-> m
             (update :routes conj
                     (resource-route-map
                      path route-key param-names))
             (update :handlers conj
                     (resource-handler-map
                      handler-key route-key opts)))))
     {:routes [] :handlers []}
     [["list-root" [:get (str "/" rsc) {'q :query-params}] ['q]]
      ["create-root" [:post (str "/" rsc) {'b :params}] ['b]]])))

(defn belongs-to-config [ns p-rsc rsc db-ref]
  (reduce (fn [m [action path param-names]]
            (let [rscs (str p-rsc "." rsc)
                  route-key (resource-route-key ns rscs action)
                  handler-key (resource-handler-key ns action)
                  opts {:db db-ref :rsc rsc :p-rsc p-rsc}] 
              (-> m
                  (update :routes conj
                          (resource-route-map
                           path route-key param-names))
                  (update :handlers conj
                          (resource-handler-map
                           handler-key route-key opts)))))
          {:routes [] :handlers []}
          [["list-one-n"
            [:get (str "/" p-rsc "/") 'id (str "/" rsc)
             {'q :query-params}] [^int 'id 'q]]
           ["create-one-n"
            [:post (str "/" p-rsc "/") 'id (str "/" rsc)
             {'b :params}] [^int 'id 'b]]]))

(defn one-n-config [table ns db-ref]
  (let [rsc (:name table)]
    (reduce
     (fn [m p-rsc]
       (let [p-rsc-config (belongs-to-config ns p-rsc rsc db-ref)]
         (-> m
             (update :routes concat (:routes p-rsc-config))
             (update :handlers concat (:handlers p-rsc-config)))))
     {:routes [] :handlers []}
     (:belongs-to table))))

(defn n-n-config [tables ns db-ref]
  nil)

(defn resource-config [table ns db-ref]
  (reduce
   (fn [m rel-type]
     (let [conf
           (case rel-type
             :root (root-config table ns db-ref)
             :one-n (one-n-config table ns db-ref)
             :n-n (n-n-config table ns db-ref))]
       (-> m
           (update :routes concat (:routes conf))
           (update :handlers concat (:handlers conf)))))
   {:routes [] :handlers []}
   (:relation-types table)))

(defn is-relation-column? [name]
  (s/ends-with? (s/lower-case name) "_id"))

(defn has-relation-column? [table]
  (some (fn [column] (is-relation-column? (:name column)))
        (:columns table)))

(defn belongs-to [table]
  (reduce (fn [v column]
            (let [name (:name column)]
              (if (is-relation-column? name)
                (conj v (s/replace name "_id" ""))
                v)))
          []
          (:columns table)))

(defn- relation-types [table]
  (filter some?
          (-> [:root]
              (conj (if (s/includes? (:name table) "_") :n-n))
              (conj (if (has-relation-column? table) :one-n)))))

(defn default-schema-map [db]
  (map (fn [table]
         (-> table
             (assoc :relation-types (relation-types table))
             (assoc :belongs-to (belongs-to table))))
       (db/get-db-schema db)))

(defn rest-config
  "Makes routes and handlers from database schema map."
  ([ns opts db-ref db]
   (rest-config ns opts db-ref db (default-schema-map db)))
  ([ns opts db-ref db db-schema]
   (reduce (fn [m table]
             (let [rsc-config (resource-config table ns db-ref)]
               (-> m
                   (update :routes concat (:routes rsc-config))
                   (update :handlers concat (:handlers rsc-config)))))
           {:routes [] :handlers []}
           db-schema)))

(defn- get-project-ns [config options]
  (:project-ns options (:duct.core/project-ns config)))

(defn- get-db
  "Builds database config and returns database map by keys.
  Required as ig/ref to :duct.database/sql is only built in
  :duct/profile namespace."
  [config opts db-config-key db-key]
  (ig/load-namespaces config)
  (db-key (ig/init config [db-config-key])))

(defn- merge-rest-config [config rest-config]
  (let [routes (apply merge (:routes rest-config))
        route-config {:duct.router/ataraxy
                      {:routes routes}}
        handler-config (apply merge (:handlers rest-config))]
    (-> config
        (core/merge-configs route-config)
        (core/merge-configs handler-config))))

(defmethod ig/init-key ::register [_ options]
  (fn [config]
    (let [project-ns (get-project-ns config options)
          db-config-key (:db-config-key options :duct.database/sql)
          db-key (:db-key options :duct.database.sql/hikaricp)
          db-ref (ig/ref db-config-key)
          db (get-db config options db-config-key db-key)
          r-config (rest-config project-ns options db-ref db)]
                                        ;(pp/pprint r-config)
      (merge-rest-config config r-config))))

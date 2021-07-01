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

(defn resource-route-map [path route-key param-name]
  (if (nil? param-name)
    {path [route-key]}
    {path [route-key param-name]}))

(defn path-config [path route-key handler-key param-name opt]
    (list (resource-route-map path route-key param-name)
          (resource-handler-map handler-key route-key opt)))

(defn root-config [tables ns db-ref]
  (mapcat
   (fn [table]
     (let [rsc (:name table)
           opt {:db db-ref :rsc rsc}]
       (map (fn [[action path param-name]]
              (let [route-key (resource-route-key ns rsc action)
                    handler-key (resource-handler-key ns action)] 
                (path-config path route-key handler-key
                             param-name opt)))
            [["list" [:get (str "/" rsc) {'q :query-params}] 'q]
             ["create" [:post (str "/" rsc) {'b :params}] 'b]])))
   tables))

(defn one-n-config [tables ns db-ref]
  nil)

(defn n-n-config [tables ns db-ref]
  nil)

(defn is-relation-column? [name]
  (s/ends-with? (s/lower-case name) "_id"))

(defn has-relation-column? [table]
  (some (fn [column] (is-relation-column? (:name column)))
        (:columns table)))

(defn relation-columns [table]
  (reduce (fn [v column]
            (let [name (:name column)]
              (if (is-relation-column? name)
                (conj v (s/replace name "_id" ""))
                v)))
          []
          (:columns table)))

(defn root-schema [table]
  table)

(defn one-n-schema [table]
  (assoc table :relation-columns (relation-columns table)))

(defn n-n-schema [table]
  (assoc table :relation-columns (relation-columns table)))

(defn- table-type [table]
  (if (s/includes? (:name table) "_") :n-n
      (if (has-relation-column? table) :one-n :root)))

(defn default-schema-map [db]
  (reduce (fn [m table]
            (let [t (table-type table)]
              (update m t conj
                      (cond (= t :root) (root-schema table)
                            (= t :one-n) (one-n-schema table)
                            (= t :n-n) (n-n-schema table)))))
          {:root [] :one-n [] :n-n []}
          (db/get-db-schema db)))
          
(defn rest-config
  "Makes config maps of resource handlers and routes from DB.
  Each endpoint contains a handler config map and a route map."
  ([ns db db-ref]
   (rest-config ns db db-ref (default-schema-map db)))
  ([ns db db-ref db-schema]
   (apply map list
          (concat (root-config (:root db-schema) ns db-ref)
                  (one-n-config (:one-n db-schema) ns db-ref)
                  (n-n-config (:n-n db-schema) ns db-ref)))))

(defn- get-project-ns [config options]
  (:project-ns options (:duct.core/project-ns config)))

(defn- get-db
  "Builds database config and returns database map by keys.
  Required as ig/ref to :duct.database/sql is only built in
  :duct/profile namespace."
  [config db-config-key db-key]
  (ig/load-namespaces config)
  (db-key (ig/init config [db-config-key])))

(defn- merge-rest-config [config rest-config]
  (let [route-config {:duct.router/ataraxy
                      {:routes (apply merge (first rest-config))}}
        handler-config (apply merge (second rest-config))]
    (core/merge-configs config (merge route-config handler-config))))

(defmethod ig/init-key ::register [_ options]
  (fn [config]
    (let [project-ns (get-project-ns config options)
          db-config-key (:db-config-key options :duct.database/sql)
          db-key (:db-key options :duct.database.sql/hikaricp)
          db-ref (ig/ref db-config-key)
          db (get-db config db-config-key db-key)
          r-config (rest-config project-ns db db-ref)]
      (pp/pprint r-config)
      (merge-rest-config config r-config))))

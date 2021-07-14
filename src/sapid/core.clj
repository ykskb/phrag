(ns sapid.core
  (:require [clojure.string :as s]
            [duct.core :as core]
            [sapid.db :as db]
            [sapid.route :as rt]
            [inflections.core :as inf]
            [integrant.core :as ig]
            [clojure.pprint :as pp]))

(defn one-n-routes [config table]
  (let [table-name (:name table)]
    (reduce (fn [m p-rsc]
              (let [routes (rt/one-n-link-routes config table table-name p-rsc)]
                (-> m
                    (update :routes concat (:routes routes))
                    (update :handlers concat (:handlers routes)))))
            {:routes [] :handlers []}
            (:belongs-to table))))

(defn n-n-routes [config table]
  (merge-with
   into
   (rt/n-n-create-routes config table)
   (let [table-name (:name table)
         rsc-a (first (:belongs-to table))
         rsc-b (second (:belongs-to table))]
     (reduce (fn [m [p-rsc rsc]]
               (let [routes (rt/n-n-link-routes config table table-name
                                                p-rsc rsc)]
                 (-> m
                     (update :routes concat (:routes routes))
                     (update :handlers concat (:handlers routes)))))
             {:routes [] :handlers []}
             [[rsc-a rsc-b] [rsc-b rsc-a]]))))

(defn table-routes [table config]
  (reduce (fn [m relation-type]
            (let [routes (case relation-type
                           :root (rt/root-routes config table)
                           :one-n (one-n-routes config table)
                           :n-n (n-n-routes config table))]
              (-> m
                  (update :routes concat (:routes routes))
                  (update :handlers concat (:handlers routes)))))
          {:routes [] :handlers []}
          (:relation-types table)))

(defn rest-routes
  "Makes routes and handlers from database schema map."
  [config]
  (reduce (fn [m table]
            (let [routes (table-routes table config)]
              (-> m
                  (update :routes concat (:routes routes))
                  (update :handlers concat (:handlers routes)))))
          {:routes [] :handlers []}
          (:tables config)))

(defn is-relation-column? [name]
  (s/ends-with? (s/lower-case name) "_id"))

(defn has-relation-column? [table]
  (some (fn [column] (is-relation-column? (:name column)))
        (:columns table)))

(defn n-n-belongs-to [table]
  (let [table-name (:name table)
        parts (s/split table-name #"_" 2)]
    [(first parts) (second parts)]))

(defn links-to [table]
  (reduce (fn [v column]
            (let [name (:name column)]
              (if (is-relation-column? name)
                (conj v (s/replace name "_id" ""))
                v)))
          []
          (:columns table)))

(defn- is-n-n-table? [table]
  (s/includes? (:name table) "_"))

(defn- relation-types [table]
  (if (is-n-n-table? table) [:n-n]
      (if (has-relation-column? table) [:one-n :root] [:root])))

(defn schema-from-db [db]
  (map (fn [table]
         (let [is-n-n (is-n-n-table? table)]
           (cond-> table
             true (assoc :relation-types (relation-types table))
             (not is-n-n) (assoc :belongs-to (links-to table))
             is-n-n (assoc :belongs-to (n-n-belongs-to table)))))
       (db/get-db-schema db)))

(defn- get-project-ns [config options]
  (:project-ns options (:duct.core/project-ns config)))

(defn- get-db
  "Builds database config and returns database map by keys. Required as
  ig/ref to :duct.database/sql is only built in :duct/profile namespace."
  [config db-config-key db-keys]
  (ig/load-namespaces config)
  (let [init-config (ig/init config [db-config-key])
        db (or (db-config-key init-config)
               (second (first (ig/find-derived init-config db-config-key))))]
    (get-in db db-keys)))

(defmulti merge-rest-routes (fn [config & _] (:router config)))

(defmethod merge-rest-routes :ataraxy [config duct-config routes]
  (let [flat-routes (apply merge (:routes routes))
        route-config {:duct.router/ataraxy {:routes flat-routes}}
        handler-config (apply merge (:handlers routes))]
    (-> duct-config
        (core/merge-configs route-config)
        (core/merge-configs handler-config))))

(defn make-rest-config [config options]
  (let [db-config-key (:db-config-key options :duct.database/sql)
        db-keys (if (contains? options :db-keys) (:db-keys options) [:spec])
        db-ref (or (:db-ref options) (ig/ref db-config-key))
        db (or (:db options) (get-db config db-config-key db-keys))]
    (println "database: ")
    (println db)
    (-> {}
        (assoc :project-ns (get-project-ns config options))
        (assoc :router (:router options :ataraxy))
        (assoc :db-config-key db-config-key)
        (assoc :db-keys db-keys)
        (assoc :db-ref db-ref)
        (assoc :db db)
        (assoc :tables (or (:tables options) (schema-from-db db)))
        (assoc :table-name-plural (:table-name-plural options true))
        (assoc :resource-path-plural (:resource-path-plural options true)))))

(defmethod ig/init-key ::register [_ options]
  (fn [config]
    (let [rest-config (make-rest-config config options)
          routes (rest-routes rest-config)]
;      (pp/pprint rest-config)
;      (pp/pprint routes)
      (merge-rest-routes rest-config config routes))))

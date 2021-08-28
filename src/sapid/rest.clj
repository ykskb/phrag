(ns sapid.rest
  (:require [sapid.route :as rt]
            [sapid.swagger :as sw]
            [sapid.table :as tbl]))

;;; routes per relationship types

(defn- concat-routes [m routes swagger]
  (-> m
      (update :routes concat (:routes routes))
      (update :handlers concat (:handlers routes))
      (update :swag-paths concat (:swag-paths swagger))
      (update :swag-defs concat (:swag-defs swagger))))

(defn- root-routes [config table]
  (let [swagger (sw/root config table)]
    (-> (rt/root-routes config table)
        (assoc :swag-paths (:swag-paths swagger))
        (assoc :swag-defs (:swag-defs swagger)))))

(defn- one-n-routes [config table]
  (reduce (fn [m p-rsc]
            (let [routes (rt/one-n-link-routes config table p-rsc)
                  swagger (sw/one-n config table p-rsc)]
              (concat-routes m routes swagger)))
          {:routes [] :handlers [] :swag-paths [] :swag-defs []}
          (:belongs-to table)))

(defn- n-n-create-routes [config table]
  (let [create-routes (rt/n-n-create-routes config table)
        create-swagger (sw/n-n-create config table)]
     (-> create-routes
         (assoc :swag-paths (:swag-paths create-swagger))
         (assoc :swag-defs (:swag-defs create-swagger)))))

(defn- n-n-link-routes [config table]
  (let [table-name (:name table)
        rsc-a (first (:belongs-to table))
        rsc-b (second (:belongs-to table))]
    (reduce (fn [m [p-rsc c-rsc]]
              (let [link-routes (rt/n-n-link-routes config table p-rsc c-rsc)
                    link-swagger (sw/n-n-link config table p-rsc c-rsc)]
                (concat-routes m link-routes link-swagger)))
            {:routes [] :handlers [] :swag-paths [] :swag-defs []}
            [[rsc-a rsc-b] [rsc-b rsc-a]])))

(defn- n-n-routes [config table]
  (merge-with into
              (n-n-create-routes config table)
              (n-n-link-routes config table)))

;;; routes

(defn- table-routes [table config]
  (reduce (fn [m relation-type]
            (let [routes (case relation-type
                           :root (root-routes config table)
                           :one-n (one-n-routes config table)
                           :n-n (n-n-routes config table))]
              (concat-routes m routes routes)))
          {:routes [] :handlers [] :swag-paths [] :swag-defs []}
          (:relation-types table)))

(defn rest-routes
  "Makes routes and handlers from database schema map."
  [config]
  (reduce (fn [m table]
            (let [routes (table-routes table config)]
              (concat-routes m routes routes)))
          {:routes [] :handlers [] :swag-paths [] :swag-defs []}
          (:tables config)))

(defn make-rest-config [options]
  (let [db (:db options)
        config {:router (:router options)
                :db db
                :table-name-plural (:table-name-plural options true)
                :resource-path-plural (:resource-path-plural options true)
                :project-ns (:project-ns options)
                :db-keys (:db-keys options)
                :db-ref (:db-ref options)}]
    (assoc config :tables (or (:tables options) (tbl/schema-from-db config db)))))

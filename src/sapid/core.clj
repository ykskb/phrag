(ns sapid.core
  (:require [clojure.string :as s]
            [duct.core :as core]
            [sapid.route :as rt]
            [sapid.rest :as rest]
            [integrant.core :as ig]
            [clojure.pprint :as pp]))

(defn- get-ig-db [config db-ig-key db-keys]
  (ig/load-namespaces config)
  (let [init-config (ig/init config [db-ig-key])
        db (or (db-ig-key init-config)
               (second (first (ig/find-derived init-config db-ig-key))))]
    (get-in db db-keys)))

;;; reitit

(defn make-reitit-routes [options]
  (let [db (or (:db options) nil)
        rest-config (rest/make-rest-config (-> options
                                               (assoc :router :reitit)
                                               (assoc :db db)))
        routes (rest/rest-routes rest-config)]
    (pp/pprint rest-config)
    (->> (rt/add-swag-route rest-config routes)
         (rt/add-graphql-route rest-config))))

(defmethod ig/init-key ::reitit-routes [_ options]
  (make-reitit-routes options))

;;; bidi

(defn make-bidi-routes [options]
  (let [db (or (:db options) nil)
        rest-config (rest/make-rest-config (-> options
                                               (assoc :router :bidi)
                                               (assoc :db db)))
        routes (rest/rest-routes rest-config)]
    (println (apply merge (:routes routes)))
    ["" (apply merge (:routes routes))]))

(defmethod ig/init-key ::bidi-routes [_ options]
  (make-bidi-routes options))

;;; Duct Ataraxy

(defn- get-duct-project-ns [config options]
  (:project-ns options (:duct.core/project-ns config)))

(defn merge-rest-routes [config duct-config routes]
  (let [flat-routes (apply merge (:routes routes))
        route-config {:duct.router/ataraxy {:routes flat-routes}}
        handler-config (apply merge (:handlers routes))]
    (-> duct-config
        (core/merge-configs route-config)
        (core/merge-configs handler-config))))

(defmethod ig/init-key ::duct-routes [_ options]
  (fn [config]
    (let [project-ns (get-duct-project-ns config options)
          db-ig-key (:db-ig-key options :duct.database/sql)
          db-keys (if (contains? options :db-keys) (:db-keys options) [:spec])
          db-ref (or (:db-ref options) (ig/ref db-ig-key))
          db (or (:db options) (get-ig-db config db-ig-key db-keys))
          rest-config (rest/make-rest-config (-> options
                                                 (assoc :router :ataraxy)
                                                 (assoc :project-ns project-ns)
                                                 (assoc :db-keys db-keys)
                                                 (assoc :db-ref db-ref)
                                                 (assoc :db db)))
          routes (rest/rest-routes rest-config)]
      (merge-rest-routes rest-config config routes))))

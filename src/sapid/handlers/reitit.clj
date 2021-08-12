(ns sapid.handlers.reitit
  (:require [clojure.string :as s]
            [clojure.walk :as w]
            [sapid.handlers.core :as c]
            [ring.util.response :as ring-res]))

(defn- param-data [req]
  (w/stringify-keys (or (:body-params req) (:form-params req))))

;;; root

(defn list-root [db-con table cols]
  (fn [req]
    (let [query (:query-params req)]
      {:status 200
       :body (c/list-root query db-con table cols)})))

(defn create-root [db-con table cols]
  (fn [req]
    (println (param-data req))
    {:status 200
     :body (c/create-root (param-data req) db-con table cols)}))

(defn fetch-root [db-con table cols]
  (fn [req]
    (let [id (-> (:path-params req) :id)
          query (:query-params req)]
      {:status 200
       :body (c/fetch-root id query db-con table cols)})))

(defn delete-root [db-con table cols]
  (fn [req]
    (let [id (-> (:path-params req) :id)]
      {:status 200
       :body (c/delete-root id db-con table)})))

(defn put-root [db-con table cols]
  (fn [req]
    (let [id (-> (:path-params req) :id)]
      {:status 200
       :body (c/put-root id (param-data req) db-con table cols)})))

(defn patch-root [db-con table cols]
  (fn [req]
    (let [id (-> (:path-params req) :id)]
      {:status 200
       :body (c/patch-root id (param-data req) db-con table cols)})))

;;; one-n

(defn list-one-n [db-con table p-col cols]
  (fn [req]
    (let [p-id (-> (:path-params req) :p-id)
          query (:query-params req)]
      {:status 200
       :body (c/list-one-n p-col p-id query db-con table cols)})))

(defn create-one-n [db-con table p-col cols]
  (fn [req]
    (let [p-id (-> (:path-params req) :p-id)
          params (param-data req)]
      {:status 200
       :body (c/create-one-n p-col p-id params db-con table cols)})))

(defn fetch-one-n [db-con table p-col cols]
  (fn [req]
    (let [id (-> (:path-params req) :id)
          p-id (-> (:path-params req) :p-id)
          query (:query-params req)]
      {:status 200
       :body (c/fetch-one-n id p-col p-id query db-con table cols)})))

(defn delete-one-n [db-con table p-col cols]
  (fn [req]
    (let [id (-> (:path-params req) :id)
          p-id (-> (:path-params req) :p-id)]
      {:status 200
       :body (c/delete-one-n id p-col p-id db-con table)})))

(defn put-one-n [db-con table p-col cols]
  (fn [req]
    (let [id (-> (:path-params req) :id)
          p-id (-> (:path-params req) :p-id)
          params (param-data req)]
      {:status 200
       :body (c/put-one-n id p-col p-id params db-con table cols)})))

(defn patch-one-n [db-con table p-col cols]
  (fn [req]
    (let [id (-> (:path-params req) :id)
          p-id (-> (:path-params req) :p-id)
          params (param-data req)]
      {:status 200
       :body (c/patch-one-n id p-col p-id params
                            db-con table cols)})))

;;; n-n

(defn list-n-n [db-con table nn-table nn-join-col nn-p-col cols]
  (fn [req]
    (let [p-id (-> (:path-params req) :p-id)
          query (:query-params req)]
      {:status 200
       :body (c/list-n-n nn-join-col nn-p-col p-id query
                         db-con nn-table table cols)})))

(defn create-n-n [db-con table col-a col-b cols]
  (fn [req]
    (let [id-a (-> (:path-params req) :id-a)
          id-b (-> (:path-params req) :id-b)
          params (param-data req)]
      {:status 200
       :body (c/create-n-n col-a id-a col-b id-b params
                           db-con table cols)})))

(defn delete-n-n [db-con table col-a col-b cols]
  (fn [req]
    (let [id-a (-> (:path-params req) :id-a)
          id-b (-> (:path-params req) :id-b)]
      {:status 200
       :body (c/delete-n-n col-a id-a col-b id-b db-con
                           table cols)})))

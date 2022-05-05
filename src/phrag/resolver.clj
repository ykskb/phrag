(ns phrag.resolver
  "Resolvers for Phrag's GraphQL schema. Queries are executed with Superlifter
  and Urania to batch nested queries and avoid N+1 problem.."
  (:require [clojure.pprint :as pp]
            [phrag.logging :refer [log]]
            [phrag.handler :as hd]
            [urania.core :as u]
            [com.walmartlabs.lacinia.resolve :as resolve]))

(defmacro resolve-error [body]
  `(try ~body
        (catch Throwable e#
          (log :error e#)
          (resolve/resolve-as nil {:message (ex-message e#)}))))

;;; Resolvers

;; Queries

(defn resolve-query
  "Resolves root-level query. Superlifter queue is always :default."
  [table-key table ctx args _val]
  (resolve-error (hd/resolve-query table-key table ctx args)))

;; Aggregates

(defn aggregate-root
  "Resolves aggregation query at root level."
  [table-key ctx args _val]
  (resolve-error (hd/aggregate-root table-key ctx args)))

;; Mutations

(defn create-root
  "Resolves create mutation."
  [table-key table ctx args _val]
  (resolve-error
   (hd/create-root table-key table ctx args)))

(defn update-root
  "Resolves update mutation. Takes `pk_columns` parameter as a record identifier."
  [table-key table ctx args _val]
  (resolve-error
   (hd/update-root  table-key table ctx args)))

(defn delete-root
  "Resolves delete mutation. Takes `pk_columns` parameter as a record identifier."
  [table-key table ctx args _val]
  (resolve-error
   (hd/delete-root table-key table ctx args)))

(ns phrag.handlers.reitit
  (:require [clojure.walk :as w]
            [com.walmartlabs.lacinia :as lcn]))

(defn- param-data [req]
  (w/stringify-keys (or (:body-params req) (:form-params req))))

;;; graphQL

(defn graphql [schema]
  (fn [req]
    (let [params (param-data req)
          query (get params "query")
          vars (w/keywordize-keys (get params "variables"))
          result (lcn/execute schema query vars nil)]
      {:status 200
       :body result})))

(ns dev
  (:refer-clojure :exclude [test])
  (:require [clojure.repl :refer :all]
            [fipp.edn :refer [pprint]]
            [clojure.tools.namespace.repl :refer [refresh]]
            [clojure.java.io :as io]
            [duct.core :as duct]
            [duct.core.repl :as duct-repl :refer [auto-reset]]
            [eftest.runner :as eftest]
            [integrant.core :as ig]
            [integrant.repl :refer [clear halt go init prep reset]]
            [integrant.repl.state :refer [config system]]
            [ring.adapter.jetty :as jetty]
            [ring.util.response :as response]))

(defn test []
  (eftest/run-tests (eftest/find-tests "test")))

(clojure.tools.namespace.repl/set-refresh-dirs "dev/src" "src" "test")

;;; handlers

(defn hello-world [request]
  (response/response "Hello, World!"))

(defmethod ig/init-key ::app [_ _]
  hello-world)

;;; routes

(defmethod ig/init-key ::routes [_ _]
  ["/" {"todos" {:get
                 :post create-todo}
        ["todos/" :todo-id] {:get fetch-todo
                             :delete delete-todo
                             :put update-todo}}])

;;; API server

(defmethod ig/init-key ::server [_ {:keys [app options]}]
  (jetty/run-jetty app options))

(defmethod ig/halt-key! ::server [_ server]
  (.stop server))

(integrant.repl/set-prep! (constantly {::routes {}
                                       ::app {:routes (ig/ref ::routes)}
                                       ::server {:app (ig/ref ::app)
                                                 :options {:port 3000
                                                           :join? false}}}))

(ns phrag.handlers.bidi-test
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.test :refer :all]
            [clojure.walk :as w]
            [ring.util.response :as ring-res]
            [phrag.handlers.bidi :as bd]
            [phrag.core-test :refer [create-database]]))

;; (deftest bidi-graphql-handler
;;   (testing "root resource routes"
;;     (let [db (create-database)
;;           table "members"
;;           cols #{"first_name" "last_name" "email"}
;;           hdlr (bd/list-root db table cols)]
;;       (let [params-a {"first_name" "john"
;;                       "last_name" "doe"
;;                       "email" "john@test.com"}
;;             params-b {"first_name" "taro"
;;                       "last_name" "yamada"
;;                       "email" "taro@test.com"}
;;             created-a (-> (assoc params-a "id" 1) (w/keywordize-keys))
;;             created-b (-> (assoc params-b "id" 2) (w/keywordize-keys))
;;             update-kv {"email" "doe@test.com"}
;;             updated-a (-> (merge created-a update-kv) (w/keywordize-keys))]
;;         (testing "list returns empty list"
;;           (is (= (ring-res/response [])
;;                  (hdlr {}))))
;;         (testing "create returns 200"
;;           (is (= (ring-res/response nil)
;;                  (hdlr {:params params-a}))))))))

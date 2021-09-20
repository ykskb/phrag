(ns phrag.handlers.duct-ataraxy-test
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.test :refer :all]
            [integrant.core :as ig]
            [phrag.handlers.duct-ataraxy :refer :all]
            [phrag.core-test :refer [create-database]]))

;; (deftest duct-ataraxy-handlers
;;   (testing "root resource routes"
;;     (let [db (create-database)
;;           cols #{"first_name" "last_name" "email"}
;;           opt {:db db :db-keys [] :table "members" :cols cols}
;;           hdlr (:phrag.handlers.duct-ataraxy/list-root
;;                      (ig/init {:phrag.handlers.duct-ataraxy/list-root opt}))]
;;       (let [params-a {:first_name "john"
;;                       :last_name "doe"
;;                       :email "john@test.com"}
;;             params-b {:first_name "taro"
;;                       :last_name "yamada"
;;                       :email "taro@test.com"}
;;             created-a (assoc params-a :id 1)
;;             created-b (assoc params-b :id 2)
;;             update-kv {:email "doe@test.com"}
;;             updated-a (merge created-a update-kv)]
;;         (testing "list returns empty list"
;;           (is (= [:ataraxy.response/ok []]
;;                  (hdlr {}))))
;;         (testing "create returns 200"
;;           (is (= [:ataraxy.response/ok nil]
;;                  (hdlr {:ataraxy/result [nil params-a]}))))))))

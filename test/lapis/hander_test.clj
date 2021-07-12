(ns lapis.hander-test
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.test :refer :all]
            [integrant.core :as ig]
            [lapis.core-test :refer [create-database]]))

(deftest handlers
  (testing "root resource routes"
    (let [db (create-database)
          cols #{"first_name" "last_name" "email"}
          opt {:db db :db-keys [] :table "members" :cols cols}
          list-hdlr (:lapis.handler/list-root
                     (ig/init {:lapis.handler/list-root opt}))
          create-hdlr (:lapis.handler/create-root
                       (ig/init {:lapis.handler/create-root opt}))
          fetch-hdlr (:lapis.handler/fetch-root
                      (ig/init {:lapis.handler/fetch-root opt}))
          put-hdlr (:lapis.handler/put-root
                    (ig/init {:lapis.handler/put-root opt}))
          patch-hdlr (:lapis.handler/patch-root
                      (ig/init {:lapis.handler/patch-root opt}))
          delete-hdlr (:lapis.handler/delete-root
                       (ig/init {:lapis.handler/delete-root opt}))]
      (let [params-a {:first_name "john"
                      :last_name "doe"
                      :email "john@test.com"}
            params-b {:first_name "taro"
                      :last_name "yamada"
                      :email "taro@test.com"}
            created-a (assoc params-a :id 1)
            created-b (assoc params-b :id 2)
            update-kv {:email "doe@test.com"}
            updated-a (merge created-a update-kv)]
        (testing "list returns empty list"
          (is (= [:ataraxy.response/ok []] 
                 (list-hdlr {}))))
        (testing "create returns 200"
          (is (= [:ataraxy.response/ok] 
                 (create-hdlr {:ataraxy/result [nil params-a]}))))
        (testing "create returns 200"
          (is (= [:ataraxy.response/ok] 
                 (create-hdlr {:ataraxy/result [nil params-b]}))))
        (testing "list returns created two items"
          (is (= [:ataraxy.response/ok [created-a created-b]] 
                 (list-hdlr {}))))
        (testing "list returns entry matching query"
          (is (= [:ataraxy.response/ok [created-a]]
                 (list-hdlr {:ataraxy/result [nil {"first_name" "john"}]}))))
        (testing "fetch returns entry by id"
          (is (= [:ataraxy.response/ok created-a]
                 (fetch-hdlr {:ataraxy/result [nil 1]}))))
        (testing "patch returns 200"
          (is (= [:ataraxy.response/ok]
                 (patch-hdlr {:ataraxy/result [nil 1 update-kv]}))))
        (testing "list returns list including updated entry"
          (is (= [:ataraxy.response/ok [updated-a created-b]]
                 (list-hdlr {}))))
        (testing "delete by id returns 200"
          (is (= [:ataraxy.response/ok]
                 (delete-hdlr {:ataraxy/result [nil 1]}))))
        (testing "list does not return deleted item"
          (is (= [:ataraxy.response/ok [created-b]]
                 (list-hdlr {})))))))
  
  (testing "one-n resource routes"
    (let [db (create-database)
          cols #{"title" "start_at" "venue_id" "group_id"}
          opt {:db db :db-keys [] :table "meetups" :p-col "venue_id" :cols cols}
          list-hdlr (:lapis.handler/list-one-n
                     (ig/init {:lapis.handler/list-one-n opt}))
          create-hdlr (:lapis.handler/create-one-n
                       (ig/init {:lapis.handler/create-one-n opt}))
          fetch-hdlr (:lapis.handler/fetch-one-n
                      (ig/init {:lapis.handler/fetch-one-n opt}))
          put-hdlr (:lapis.handler/put-one-n
                    (ig/init {:lapis.handler/put-one-n opt}))
          patch-hdlr (:lapis.handler/patch-one-n
                      (ig/init {:lapis.handler/patch-one-n opt}))
          delete-hdlr (:lapis.handler/delete-one-n
                       (ig/init {:lapis.handler/delete-one-n opt}))]
      (let [params-a {:title "user group meetup Jan"
                      :start_at "2020-01-01 12:00:00"
                      :venue_id 1
                      :group_id 1}
            params-b {:title "user group meetup Feb"
                      :start_at "2020-02-01 12:00:00"
                      :venue_id 2
                      :group_id 1}
            created-a (assoc params-a :id 1)
            created-b (assoc params-b :id 2)
            update-kv {:venue_id 2}
            updated-a (merge created-a update-kv)]
        (doto db
          (jdbc/execute! (str "insert into venues "
                              "(name, postal_code) values "
                              "('office one', '12345');"))
          (jdbc/execute! (str "insert into venues "
                              "(name, postal_code) values "
                              "('office two', '23456');")))
        
        (testing "list returns items under a parent id"
          (is (= [:ataraxy.response/ok []] 
                 (list-hdlr {:ataraxy/result [nil "1"]}))))
        (testing "create under first parent returns 200"
          (is (= [:ataraxy.response/ok] 
                 (create-hdlr {:ataraxy/result [nil "1" params-a]}))))
        (testing "create under second parent returns 200"
          (is (= [:ataraxy.response/ok] 
                 (create-hdlr {:ataraxy/result [nil "2" params-b]}))))
        (testing "list returns items under first parent"
          (is (= [:ataraxy.response/ok [created-a]] 
                 (list-hdlr {:ataraxy/result [nil "1"]}))))
        (testing "list returns items under second parent"
          (is (= [:ataraxy.response/ok [created-b]] 
                 (list-hdlr {:ataraxy/result [nil "2"]}))))
        (testing "list does not return unmatching query"
          (is (= [:ataraxy.response/ok []]
                 (list-hdlr {:ataraxy/result
                             [nil "1" {"title" "non-exising"}]}))))
        (testing "list returns with matching query"
          (is (= [:ataraxy.response/ok [created-a]]
                 (list-hdlr {:ataraxy/result
                             [nil "1" {"title" "user group meetup Jan"}]}))))
        (testing "fetch returns matching item with parent id and item id"
          (is (= [:ataraxy.response/ok created-a]
                 (fetch-hdlr {:ataraxy/result [nil "1" "1"]}))))
        (testing "patch returns 200"
          (is (= [:ataraxy.response/ok]
                 (patch-hdlr {:ataraxy/result [nil "1" "1" update-kv]}))))
        (testing "list returns updated items under updated parent"
          (is (= [:ataraxy.response/ok [updated-a created-b]]
                 (list-hdlr {:ataraxy/result [nil "2"]}))))
        (testing "delete returns 200"
          (is (= [:ataraxy.response/ok]
                 (delete-hdlr {:ataraxy/result [nil "1" "1"]}))))
        (testing "list does not return a deleted item"
          (is (= [:ataraxy.response/ok []]
                 (list-hdlr {:ataraxy/result [nil "1"]}))))))))

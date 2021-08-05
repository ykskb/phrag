(ns sapid.duct-ataraxy-test
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.test :refer :all]
            [integrant.core :as ig]
            [sapid.handler :refer :all]
            [sapid.core-test :refer [create-database]]))

(deftest duct-ataraxy-handlers
  (testing "root resource routes"
    (let [db (create-database)
          cols #{"first_name" "last_name" "email"}
          opt {:db db :db-keys [] :table "members" :cols cols}
          list-hdlr (:sapid.handler/list-root
                     (ig/init {:sapid.handler/list-root opt}))
          create-hdlr (:sapid.handler/create-root
                       (ig/init {:sapid.handler/create-root opt}))
          fetch-hdlr (:sapid.handler/fetch-root
                      (ig/init {:sapid.handler/fetch-root opt}))
          put-hdlr (:sapid.handler/put-root
                    (ig/init {:sapid.handler/put-root opt}))
          patch-hdlr (:sapid.handler/patch-root
                      (ig/init {:sapid.handler/patch-root opt}))
          delete-hdlr (:sapid.handler/delete-root
                       (ig/init {:sapid.handler/delete-root opt}))]
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
          (is (= [:ataraxy.response/ok nil]
                 (create-hdlr {:ataraxy/result [nil params-a]}))))
        (testing "create returns 200"
          (is (= [:ataraxy.response/ok nil]
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
          (is (= [:ataraxy.response/ok nil]
                 (patch-hdlr {:ataraxy/result [nil 1 update-kv]}))))
        (testing "list returns list including updated entry"
          (is (= [:ataraxy.response/ok [updated-a created-b]]
                 (list-hdlr {}))))
        (testing "delete by id returns 200"
          (is (= [:ataraxy.response/ok nil]
                 (delete-hdlr {:ataraxy/result [nil 1]}))))
        (testing "list does not return deleted item"
          (is (= [:ataraxy.response/ok [created-b]]
                 (list-hdlr {})))))))

  (testing "one-n resource routes"
    (let [db (create-database)
          cols #{"title" "start_at" "venue_id" "group_id"}
          opt {:db db :db-keys [] :table "meetups" :p-col "venue_id" :cols cols}
          list-hdlr (:sapid.handler/list-one-n
                     (ig/init {:sapid.handler/list-one-n opt}))
          create-hdlr (:sapid.handler/create-one-n
                       (ig/init {:sapid.handler/create-one-n opt}))
          fetch-hdlr (:sapid.handler/fetch-one-n
                      (ig/init {:sapid.handler/fetch-one-n opt}))
          put-hdlr (:sapid.handler/put-one-n
                    (ig/init {:sapid.handler/put-one-n opt}))
          patch-hdlr (:sapid.handler/patch-one-n
                      (ig/init {:sapid.handler/patch-one-n opt}))
          delete-hdlr (:sapid.handler/delete-one-n
                       (ig/init {:sapid.handler/delete-one-n opt}))]
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

        (testing "list returns empty list under a parent id"
          (is (= [:ataraxy.response/ok []]
                 (list-hdlr {:ataraxy/result [nil "1"]}))))
        (testing "create under first parent returns 200"
          (is (= [:ataraxy.response/ok nil]
                 (create-hdlr {:ataraxy/result [nil "1" params-a]}))))
        (testing "create under second parent returns 200"
          (is (= [:ataraxy.response/ok nil]
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
          (is (= [:ataraxy.response/ok nil]
                 (patch-hdlr {:ataraxy/result [nil "1" "1" update-kv]}))))
        (testing "list returns updated items under updated parent"
          (is (= [:ataraxy.response/ok [updated-a created-b]]
                 (list-hdlr {:ataraxy/result [nil "2"]}))))
        (testing "delete returns 200"
          (is (= [:ataraxy.response/ok nil]
                 (delete-hdlr {:ataraxy/result [nil "1" "1"]}))))
        (testing "list does not return a deleted item"
          (is (= [:ataraxy.response/ok []]
                 (list-hdlr {:ataraxy/result [nil "1"]})))))))

  (testing "n-n resource routes"
    (let [db (create-database)
          cols #{"member_id" "group_id"}
          list-a-opt {:db db :db-keys [] :table "groups"
                      :nn-table "groups_members" :nn-join-col "group_id"
                      :nn-p-col "member_id" :cols cols}
          list-b-opt {:db db :db-keys [] :table "members"
                      :nn-table "groups_members" :nn-join-col "member_id"
                      :nn-p-col "group_id" :cols cols}
          opt {:db db :db-keys [] :table "groups_members"
               :col-a "member_id" :col-b "group_id" :cols cols}
          list-a-hdlr (:sapid.handler/list-n-n
                       (ig/init {:sapid.handler/list-n-n list-a-opt}))
          list-b-hdlr (:sapid.handler/list-n-n
                       (ig/init {:sapid.handler/list-n-n list-b-opt}))
          create-hdlr (:sapid.handler/create-n-n
                       (ig/init {:sapid.handler/create-n-n opt}))
          delete-hdlr (:sapid.handler/delete-n-n
                       (ig/init {:sapid.handler/delete-n-n opt}))]
      (let [rsc-a-1 {:id 1
                     :name "clojure meetup"
                     :created_at nil}
            rsc-a-2 {:id 2
                     :name "common lisp meetup"
                     :created_at nil}
            rsc-b {:id 1
                   :first_name "john"
                   :last_name "doe"
                   :email "john@test.com"}]
        (doto db
          (jdbc/execute! (str "insert into members "
                              "(first_name, last_name, email) values "
                              "('john', 'doe', 'john@test.com');"))
          (jdbc/execute! (str "insert into groups "
                              "(name) values "
                              "('clojure meetup');"))
          (jdbc/execute! (str "insert into groups "
                              "(name) values "
                              "('common lisp meetup');")))
        (testing "list a returns empty list"
          (is (= [:ataraxy.response/ok []]
                 (list-a-hdlr {:ataraxy/result [nil "1"]}))))
        (testing "create n-to-n entry returns 200"
          (is (= [:ataraxy.response/ok nil]
                 (create-hdlr {:ataraxy/result [nil "1" "1"]}))))
        (testing "list-a returns a linked item"
          (is (= [:ataraxy.response/ok [rsc-a-1]]
                 (list-a-hdlr {:ataraxy/result [nil "1"]}))))
        (testing "list-b returns a linked item"
          (is (= [:ataraxy.response/ok [rsc-b]]
                 (list-b-hdlr {:ataraxy/result [nil "1"]}))))
        (testing "create n-to-n entry with another resource-a returns 200"
          (is (= [:ataraxy.response/ok nil]
                 (create-hdlr {:ataraxy/result [nil "1" "2"]}))))
        (testing "list a returns all linked items"
          (is (= [:ataraxy.response/ok [rsc-a-1 rsc-a-2]]
                 (list-a-hdlr {:ataraxy/result [nil "1"]}))))
        (testing "list b returns a linked item"
          (is (= [:ataraxy.response/ok [rsc-b]]
                 (list-b-hdlr {:ataraxy/result [nil "1"]}))))
        (testing "delete first n-to-n entry returns 200"
          (is (= [:ataraxy.response/ok nil]
                 (delete-hdlr {:ataraxy/result [nil "1" "1"]}))))
        (testing "list-a returns a linked item after deletion"
          (is (= [:ataraxy.response/ok [rsc-a-2]]
                 (list-a-hdlr {:ataraxy/result [nil "1"]}))))
        (testing "list-b returns empty list after deletion"
          (is (= [:ataraxy.response/ok []]
                 (list-b-hdlr {:ataraxy/result [nil "1"]}))))))))

(ns sapid.handlers.bidi-test
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.test :refer :all]
            [clojure.walk :as w]
            [ring.util.response :as ring-res]
            [sapid.handlers.bidi :as bd]
            [sapid.core-test :refer [create-database]]))

(deftest bidi-handlers
  (testing "root resource routes"
    (let [db (create-database)
          table "members"
          cols #{"first_name" "last_name" "email"}
          list-hdlr (bd/list-root db table cols)
          create-hdlr (bd/create-root db table cols)
          fetch-hdlr (bd/fetch-root db table cols)
          put-hdlr (bd/put-root db table cols)
          patch-hdlr (bd/patch-root db table cols)
          delete-hdlr (bd/delete-root db table cols)]
      (let [params-a {"first_name" "john"
                      "last_name" "doe"
                      "email" "john@test.com"}
            params-b {"first_name" "taro"
                      "last_name" "yamada"
                      "email" "taro@test.com"}
            created-a (-> (assoc params-a "id" 1) (w/keywordize-keys))
            created-b (-> (assoc params-b "id" 2) (w/keywordize-keys))
            update-kv {"email" "doe@test.com"}
            updated-a (-> (merge created-a update-kv) (w/keywordize-keys))]
        (testing "list returns empty list"
          (is (= (ring-res/response [])
                 (list-hdlr {}))))
        (testing "create returns 200"
          (is (= (ring-res/response nil)
                 (create-hdlr {:params params-a}))))
        (testing "create returns 200"
          (is (= (ring-res/response nil)
                 (create-hdlr {:params params-b}))))
        (testing "list returns created two items"
          (is (= (ring-res/response [created-a created-b])
                 (list-hdlr {}))))
        (testing "list returns entry matching query"
          (is (= (ring-res/response [created-a])
                 (list-hdlr {:query-string "first_name=john"}))))
        (testing "fetch returns entry by id"
          (is (= (ring-res/response created-a)
                 (fetch-hdlr {:route-params {:id 1}}))))
        (testing "patch returns 200"
          (is (= (ring-res/response nil)
                 (patch-hdlr {:route-params {:id 1} :params update-kv}))))
        (testing "list returns list including updated entry"
          (is (= (ring-res/response [updated-a created-b])
                 (list-hdlr {}))))
        (testing "delete by id returns 200"
          (is (= (ring-res/response nil)
                 (delete-hdlr {:route-params {:id 1}}))))
        (testing "list does not return deleted item"
          (is (= (ring-res/response [created-b])
                 (list-hdlr {}))))))))

(testing "one-n resource routes"
  (let [db (create-database)
        table "meetups"
        p-col "venue_id"
        cols #{"title" "start_at" "venue_id" "group_id"}
        list-hdlr (bd/list-one-n db table p-col cols)
        create-hdlr (bd/create-one-n db table p-col cols)
        fetch-hdlr (bd/fetch-one-n db table p-col cols)
        put-hdlr (bd/put-one-n db table p-col cols)
        patch-hdlr (bd/patch-one-n db table p-col cols)
        delete-hdlr (bd/delete-one-n db table p-col cols)]
    (let [params-a {"title" "user group meetup Jan"
                    "start_at" "2020-01-01 12:00:00"
                    "venue_id" 1
                    "group_id" 1}
          params-b {"title" "user group meetup Feb"
                    "start_at" "2020-02-01 12:00:00"
                    "venue_id" 2
                    "group_id" 1}
          created-a (-> (assoc params-a "id" 1) (w/keywordize-keys))
          created-b (-> (assoc params-b "id" 2) (w/keywordize-keys))
          update-kv {"venue_id" 2}
          updated-a (merge created-a (w/keywordize-keys update-kv))]
      (doto db
        (jdbc/execute! (str "insert into venues "
                            "(name, postal_code) values "
                            "('office one', '12345');"))
        (jdbc/execute! (str "insert into venues "
                            "(name, postal_code) values "
                            "('office two', '23456');")))

      (testing "list returns empty list under a parent id"
        (is (= (ring-res/response [])
               (list-hdlr {:route-params {:p-id "1"}}))))
      (testing "create under first parent returns 200"
        (is (= (ring-res/response nil)
               (create-hdlr {:route-params {:p-id "1"} :params params-a}))))
      (testing "create under second parent returns 200"
        (is (= (ring-res/response nil)
               (create-hdlr {:route-params {:p-id "2"} :params params-b}))))
      (testing "list returns items under first parent"
        (is (= (ring-res/response [created-a])
               (list-hdlr {:route-params {:p-id "1"}}))))
      (testing "list returns items under second parent"
        (is (= (ring-res/response [created-b])
               (list-hdlr {:route-params {:p-id "2"}}))))
      (testing "list does not return unmatching query"
        (is (= (ring-res/response [])
               (list-hdlr {:route-params {:p-id "1"}
                           :query-string "title=non-existing"}))))
      (testing "list returns with matching query"
        (is (= (ring-res/response [created-a])
               (list-hdlr {:route-params {:p-id "1"}
                           :query-string "title=user group meetup Jan"}))))
      (testing "fetch returns matching item with parent id and item id"
        (is (= (ring-res/response created-a)
               (fetch-hdlr {:route-params {:p-id "1" :id "1"}}))))
      (testing "patch returns 200"
        (is (= (ring-res/response nil)
               (patch-hdlr {:route-params {:p-id "1" :id "1"} :params update-kv}))))
      (testing "list returns updated items under updated parent"
        (is (= (ring-res/response [updated-a created-b])
               (list-hdlr {:route-params {:p-id "2"}}))))
      (testing "delete returns 200"
        (is (= (ring-res/response nil)
               (delete-hdlr {:route-params {:p-id "1" :id "1"}}))))
      (testing "list does not return a deleted item"
        (is (= (ring-res/response [])
               (list-hdlr {:route-params {:p-id "1"}})))))))

(testing "n-n resource routes"
  (let [db (create-database)
        col-a "member_id"
        col-b "group_id"
        cols #{"member_id" "group_id"}
        list-a-hdlr (bd/list-n-n db "groups"  "groups_members"
                                 "group_id" "member_id" cols)
        list-b-hdlr (bd/list-n-n db "members" "groups_members"
                                 "member_id" "group_id" cols)
        create-hdlr (bd/create-n-n db "groups_members"
                                   "member_id" "group_id" cols)
        delete-hdlr (bd/delete-n-n db "groups_members"
                                   "member_id" "group_id" cols)]
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
        (is (= (ring-res/response [])
               (list-a-hdlr {:route-params {:p-id "1"}}))))
      (testing "create n-to-n entry returns 200"
        (is (= (ring-res/response nil)
               (create-hdlr {:route-params {:id-a "1" :id-b "1"}}))))
      (testing "list-a returns a linked item"
        (is (= (ring-res/response [rsc-a-1])
               (list-a-hdlr {:route-params {:p-id "1"}}))))
      (testing "list-b returns a linked item"
        (is (= (ring-res/response [rsc-b])
               (list-b-hdlr {:route-params {:p-id "1"}}))))
      (testing "create n-to-n entry with another resource-a returns 200"
        (is (= (ring-res/response nil)
               (create-hdlr {:route-params {:id-a "1" :id-b "2"}}))))
      (testing "list a returns all linked items"
        (is (= (ring-res/response [rsc-a-1 rsc-a-2])
               (list-a-hdlr {:route-params {:p-id "1"}}))))
      (testing "list b returns a linked item"
        (is (= (ring-res/response [rsc-b])
               (list-b-hdlr {:route-params {:p-id "1"}}))))
      (testing "delete first n-to-n entry returns 200"
        (is (= (ring-res/response nil)
               (delete-hdlr {:route-params {:id-a "1" :id-b "1"}}))))
      (testing "list-a returns a linked item after deletion"
        (is (= (ring-res/response [rsc-a-2])
               (list-a-hdlr {:route-params {:p-id "1"}}))))
      (testing "list-b returns empty list after deletion"
        (is (= (ring-res/response [])
               (list-b-hdlr {:route-params {:p-id "1"}})))))))

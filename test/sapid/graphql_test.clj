(ns sapid.graphql-test
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.test :refer :all]
            [com.walmartlabs.lacinia :as lcn]
            [sapid.core-test :refer [create-database]]
            [sapid.graphql :as gql]))

(def ^:private test-config
  {:table-name-plural true,
   :resource-path-plural true
   :tables [{:name "members"
             :columns [{:name "id" :type "integer"}
                       {:name "first_name" :type "text"}
                       {:name "last_name" :type "text"}
                       {:name "email" :type "text"}]
             :relation-types [:root]
             :belongs-to []}
            {:name "venues"
             :columns [{:name "id" :type "integer"}
                       {:name "name" :type "text"}
                       {:name "postal_code" :type "text"}]
             :relation-types [:root]
             :belongs-to []}
            {:name "meetups"
             :columns [{:name "id" :type "integer"}
                       {:name "title" :type "text"}
                       {:name "start_at" :type "timestamp"}
                       {:name "venue_id" :type "integer"}
                       {:name "group_id" :type "integer"}]
             :relation-types [:one-n :root]
             :belongs-to ["venue" "group"]}
            {:name "meetups_members"
             :columns [{:name "meetup_id" :type "integer"}
                       {:name "member_id" :type "integer"}]
             :relation-types [:n-n]
             :belongs-to ["meetups" "members"]}]})

(deftest graphql-queries
  (let [db (create-database)
        conf (assoc test-config :db db)
        schema (gql/schema conf)]
    (doto db
      (jdbc/execute! (str "insert into members (email, first_name, last_name)"
                          "values ('jim@test.com', 'jim', 'smith');"))
      (jdbc/execute! (str "insert into members (email, first_name, last_name)"
                          "values ('yoshi@test.com', 'yoshi', 'tanabe');"))
      (jdbc/execute! (str "insert into venues (name, postal_code) "
                          "values ('office one', '123456');"))
      (jdbc/execute! (str "insert into venues (name, postal_code) "
                          "values ('city hall', '234567');"))
      (jdbc/execute! (str "insert into meetups (title, start_at, venue_id)"
                          "values ('rust meetup', '2021-01-01 18:00:00', 2);"))
      (jdbc/execute! (str "insert into meetups (title, start_at, venue_id)"
                          "values ('cpp meetup', '2021-01-12 18:00:00', 1);"))
      (jdbc/execute! (str "insert into meetups_members (meetup_id, member_id)"
                          "values (2,1);"))
      (jdbc/execute! (str "insert into meetups_members (meetup_id, member_id)"
                          "values (1,1);"))
      (jdbc/execute! (str "insert into meetups_members (meetup_id, member_id)"
                          "values (1,2);")))
    (testing "fetch root type entity"
      (let [q "{ member(id: 1) { id email first_name }}"
            result (lcn/execute schema q nil nil)]
        (is (= {:id 1 :email "jim@test.com" :first_name "jim"}
               (-> result :data :member)))))

    (testing "fetch entity with has-one param"
      (let [q "{ meetup(id: 1) { title start_at venue { id name }}}"
            result (lcn/execute schema q nil nil)]
        (is (= {:title "rust meetup" :start_at "2021-01-01 18:00:00"
                :venue {:id 2 :name "city hall"}}
               (-> result :data :meetup))))
      (let [q "{ meetup(id: 2) { title start_at venue { id name }}}"
            result (lcn/execute schema q nil nil)]
        (is (= {:title "cpp meetup" :start_at "2021-01-12 18:00:00"
                :venue {:id 1 :name "office one"}}
               (-> result :data :meetup)))))

    (testing "fetch entity with has-many param"
      (let [q "{ venue(id: 1) { name postal_code meetups { id title }}}"
            result (lcn/execute schema q nil nil)]
        (is (= {:name "office one" :postal_code "123456"
                :meetups [{:id 2 :title "cpp meetup"}]}
               (-> result :data :venue))))
      (let [q "{ venue(id: 2) { name postal_code meetups { id title }}}"
            result (lcn/execute schema q nil nil)]
        (is (= {:name "city hall" :postal_code "234567"
                :meetups [{:id 1 :title "rust meetup"}]}
               (-> result :data :venue)))))

    (testing "fetch entity with many-to-many param"
      (let [q "{ member(id: 1) { email meetups { id title }}}"
            result (lcn/execute schema q nil nil)]
        (is (= {:email "jim@test.com"
                :meetups [{:id 2 :title "cpp meetup"}
                          {:id 1 :title "rust meetup"}]}
               (-> result :data :member))))
      (let [q "{ member(id: 2) { email meetups { id title }}}"
            result (lcn/execute schema q nil nil)]
        (is (= {:email "yoshi@test.com"
                :meetups [{:id 1 :title "rust meetup"}]}
               (-> result :data :member)))))))

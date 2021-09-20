(ns phrag.graphql-test
  (:require [clojure.test :refer :all]
            [com.walmartlabs.lacinia :as lcn]
            [phrag.core-test :refer [create-database]]
            [phrag.graphql :as gql]))

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
    ;; Create mutations
    (testing "create 1st user"
      (let [q (str "mutation {createMember (email: \"jim@test.com\" "
                   "first_name: \"jim\" last_name: \"smith\") {result}}")
            res (lcn/execute schema q nil nil)]
        (println res)
        (is (= true (-> res :data :createMember :result)))))

    (testing "create 2nd user"
      (let [q (str "mutation {createMember (email: \"yoshi@test.com\" "
                   "first_name: \"yoshi\" last_name: \"tanabe\") {result}}")
            res (lcn/execute schema q nil nil)]
        (is (= true (-> res :data :createMember :result)))))

    (testing "create 1st venue"
      (let [q (str "mutation {createVenue (name: \"office one\" "
                   "postal_code: \"123456\") {result}}")
            res (lcn/execute schema q nil nil)]
        (is (= true (-> res :data :createVenue :result)))))

    (testing "create 2nd venue"
      (let [q (str "mutation {createVenue (name: \"city hall\" "
                   "postal_code: \"234567\") {result}}")
            res (lcn/execute schema q nil nil)]
        (is (= true (-> res :data :createVenue :result)))))

    (testing "create 1st meetup"
      (let [q (str "mutation {createMeetup (title: \"rust meetup\" "
                   "start_at: \"2021-01-01 18:00:00\" venue_id: 2) {result}}")
            res (lcn/execute schema q nil nil)]
        (is (= true (-> res :data :createMeetup :result)))))

    (testing "create 2nd meetup"
      (let [q (str "mutation {createMeetup (title: \"cpp meetup\" "
                   "start_at: \"2021-01-12 18:00:00\" venue_id: 1) {result}}")
            res (lcn/execute schema q nil nil)]
        (is (= true (-> res :data :createMeetup :result)))))

    (testing "add member 1 to meetup 1"
      (let [q (str "mutation {createMeetupMember (meetup_id: 1"
                   "member_id: 1) {result}}")
            res (lcn/execute schema q nil nil)]
        (is (= true (-> res :data :createMeetupMember :result)))))

    (testing "add member 1 to meetup 2"
      (let [q (str "mutation {createMeetupMember (meetup_id: 2"
                   "member_id: 1) {result}}")
            res (lcn/execute schema q nil nil)]
        (is (= true (-> res :data :createMeetupMember :result)))))

    (testing "add member 2 to meetup 1"
      (let [q (str "mutation {createMeetupMember (meetup_id: 1"
                   "member_id: 2) {result}}")
            res (lcn/execute schema q nil nil)]
        (is (= true (-> res :data :createMeetupMember :result)))))

    ;; Queries
    (testing "list root type entity"
      (let [q "{ members { id email first_name }}"
            result (lcn/execute schema q nil nil)]
        (is (= [{:id 1 :email "jim@test.com" :first_name "jim"}
                {:id 2 :email "yoshi@test.com" :first_name "yoshi"}]
               (-> result :data :members)))))

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
                :meetups [{:id 1 :title "rust meetup"}
                          {:id 2 :title "cpp meetup"}]}
               (-> result :data :member))))
      (let [q "{ member(id: 2) { email meetups { id title }}}"
            result (lcn/execute schema q nil nil)]
        (is (= {:email "yoshi@test.com"
                :meetups [{:id 1 :title "rust meetup"}]}
               (-> result :data :member)))))

    ;; Filters
    (testing "list entity with eq filter"
      (let [q (str "{ members (filter: {first_name: {operator:eq "
                   "value: \"yoshi\"}}) { id last_name }}")
            result (lcn/execute schema q nil nil)]
        (is (= [{:id 2 :last_name "tanabe"}]
               (-> result :data :members))))
      (let [q (str "{ members (filter: {first_name: {operator: eq "
                   "value: \"yoshi\"} last_name: {operator: eq "
                   "value: \"smith\"}}) { id last_name }}")
            result (lcn/execute schema q nil nil)]
        (is (= [] (-> result :data :members)))))

    ;; Pagination
    (testing "list entity with pagination"
      (let [q (str "{ members (limit: 1) { id first_name }}")
            result (lcn/execute schema q nil nil)]
        (is (= [{:id 1 :first_name "jim"}]
               (-> result :data :members))))
      (let [q (str "{ members (offset: 1) { id first_name }}")
            result (lcn/execute schema q nil nil)]
        (is (= [{:id 2 :first_name "yoshi"}] (-> result :data :members)))))

    ;; Sorting
    (testing "list entity sorted"
      (let [q (str "{ members (sort: {first_name: desc}) { id first_name }}")
            result (lcn/execute schema q nil nil)]
        (is (= [{:id 2 :first_name "yoshi"}
                {:id 1 :first_name "jim"}]
               (-> result :data :members))))
      (let [q (str "{ members (sort: {first_name: asc}) { id first_name }}")
            result (lcn/execute schema q nil nil)]
        (is (= [{:id 1 :first_name "jim"}
                {:id 2 :first_name "yoshi"}]
               (-> result :data :members)))))

    ;; Update mutation
    (testing "update entity"
      (let [q (str "mutation {updateMember (id: 1 email: \"ken@test.com\" "
                   "first_name: \"Ken\" last_name: \"Spencer\") {result}}")
            res (lcn/execute schema q nil nil)]
        (is (= true (-> res :data :updateMember :result))))
      (let [q (str "{ member (id: 1) { id first_name last_name email}}")
            result (lcn/execute schema q nil nil)]
        (is (= {:id 1 :first_name "Ken" :last_name "Spencer"
                :email "ken@test.com"}
               (-> result :data :member)))))

    ;; Delete mutation
    (testing "delete entity"
      (let [q (str "mutation {deleteMember (id: 1) {result}}")
            res (lcn/execute schema q nil nil)]
        (is (= true (-> res :data :deleteMember :result))))
      (let [q (str "{ members { id first_name }}")
            result (lcn/execute schema q nil nil)]
        (is (= [{:id 2 :first_name "yoshi"}]
               (-> result :data :members)))))))

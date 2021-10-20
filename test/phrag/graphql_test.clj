(ns phrag.graphql-test
  (:require [clojure.test :refer :all]
            [clojure.java.jdbc :as jdbc]
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
        test-gql (fn [q res-keys expected]
                   (let [schema (gql/schema conf)
                         res (gql/exec conf schema q nil)]
                     (println res)
                     (is (= expected (get-in res res-keys)))))]

    ;; Create mutations
    (testing "create 1st user"
      (test-gql (str "mutation {createMember (email: \"jim@test.com\" "
                     "first_name: \"jim\" last_name: \"smith\") { id }}")
                [:data :createMember :id] 1))

    (testing "create 2nd user"
      (test-gql (str "mutation {createMember (email: \"yoshi@test.com\" "
                     "first_name: \"yoshi\" last_name: \"tanabe\") { id }}")
                [:data :createMember :id] 2))

    (testing "create 1st venue"
      (test-gql (str "mutation {createVenue (name: \"office one\" "
                     "postal_code: \"123456\") { id }}")
                [:data :createVenue :id] 1))

    (testing "create 2nd venue"
      (test-gql (str "mutation {createVenue (name: \"city hall\" "
                     "postal_code: \"234567\") { id }}")
                [:data :createVenue :id] 2))

    (testing "create 1st meetup"
      (test-gql (str "mutation {createMeetup (title: \"rust meetup\" "
                     "start_at: \"2021-01-01 18:00:00\" venue_id: 2) { id }}")
                [:data :createMeetup :id] 1))

    (testing "create 2nd meetup"
      (test-gql (str "mutation {createMeetup (title: \"cpp meetup\" "
                     "start_at: \"2021-01-12 18:00:00\" venue_id: 1) { id }}")
                [:data :createMeetup :id] 2))

    (testing "add member 1 to meetup 1"
      (test-gql (str "mutation {createMeetupMember (meetup_id: 1"
                     "member_id: 1) { result }}")
                [:data :createMeetupMember :result] true))

    (testing "add member 1 to meetup 2"
      (test-gql (str "mutation {createMeetupMember (meetup_id: 2"
                     "member_id: 1) { result }}")
                [:data :createMeetupMember :result] true))

    (testing "add member 2 to meetup 1"
      (test-gql (str "mutation {createMeetupMember (meetup_id: 1"
                     "member_id: 2) { result }}")
                [:data :createMeetupMember :result] true))
                                        ;
    ;; Queries
    (testing "list root type entity"
      (test-gql  "{ members { id email first_name }}"
                 [:data :members]
                 [{:id 1 :email "jim@test.com" :first_name "jim"}
                  {:id 2 :email "yoshi@test.com" :first_name "yoshi"}]))

    (testing "fetch root type entity"
      (test-gql  "{ member(id: 1) { id email first_name }}"
                 [:data :member]
                 {:id 1 :email "jim@test.com" :first_name "jim"}))

    (testing "fetch entity with has-one param"
      (test-gql  "{ meetup(id: 1) { title start_at venue { id name }}}"
                 [:data :meetup]
                 {:title "rust meetup" :start_at "2021-01-01 18:00:00"
                  :venue {:id 2 :name "city hall"}})
      (test-gql  "{ meetup(id: 2) { title start_at venue { id name }}}"
                 [:data :meetup]
                 {:title "cpp meetup" :start_at "2021-01-12 18:00:00"
                  :venue {:id 1 :name "office one"}}))

    (testing "fetch entity with has-many param"
      (test-gql  "{ venue(id: 1) { name postal_code meetups { id title }}}"
                 [:data :venue]
                 {:name "office one" :postal_code "123456"
                  :meetups [{:id 2 :title "cpp meetup"}]})
      (test-gql  "{ venue(id: 2) { name postal_code meetups { id title }}}"
                 [:data :venue]
                 {:name "city hall" :postal_code "234567"
                  :meetups [{:id 1 :title "rust meetup"}]}))

    (testing "list entities with many-to-many param"
      (test-gql  "{ members { email meetups { id title }}}"
                 [:data :members]
                 [{:email "jim@test.com"
                   :meetups [{:id 1 :title "rust meetup"}
                             {:id 2 :title "cpp meetup"}]}
                  {:email "yoshi@test.com"
                   :meetups [{:id 1 :title "rust meetup"}]}]))

    (testing "fetch entity with many-to-many param"
      (test-gql  "{ member(id: 1) { email meetups { id title }}}"
                 [:data :member]
                 {:email "jim@test.com"
                  :meetups [{:id 1 :title "rust meetup"}
                            {:id 2 :title "cpp meetup"}]})
      (test-gql  "{ member(id: 2) { email meetups { id title }}}"
                 [:data :member]
                 {:email "yoshi@test.com"
                  :meetups [{:id 1 :title "rust meetup"}]}))

    ;; Filters
    (testing "list entity with where arg"
      (test-gql (str "{ members (where: {first_name: {eq:  \"yoshi\"}}) "
                     "{ id last_name }}")
                [:data :members]
                [{:id 2 :last_name "tanabe"}])
      (test-gql (str "{ members (where: {first_name: {eq: \"yoshi\"} "
                     "last_name: {eq: \"unknown\"}}) { id last_name }}")
                [:data :members]
                []))

    (testing "list entity with AND group"
      (test-gql (str "{ members (where: "
                     "{ and: [{id: {gt: 1}}, {first_name: {eq: \"yoshi\"}}]}) "
                     "{ id last_name }}")
                [:data :members]
                [{:id 2 :last_name "tanabe"}])
      (test-gql (str "{ members (where: "
                     "{ and: [{id: {gt: 0}}, {first_name: {eq: \"jim\"}}]}) "
                     "{ id last_name }}")
                [:data :members]
                [{:id 1 :last_name "smith"}]))

    (testing "list entity with OR group"
      (test-gql (str "{ members (where: "
                     "{ or: [{id: {eq: 1}}, {first_name: {eq: \"yoshi\"}}]}) "
                     "{ id last_name }}")
                [:data :members]
                [{:id 1 :last_name "smith"}
                 {:id 2 :last_name "tanabe"}])
      (test-gql (str "{ members (where: "
                     "{ or: [{id: {gt: 1}}, {first_name: {eq: \"yoshi\"}}]}) "
                     "{ id last_name }}")
                [:data :members]
                [{:id 2 :last_name "tanabe"}]))

    (testing "fetch entity with has-many param filtered with where"
      (test-gql   "{ venue(id: 1) { name postal_code meetups { id title }}}"
                 [:data :venue]
                 {:name "office one" :postal_code "123456"
                  :meetups [{:id 2 :title "cpp meetup"}]})
      (test-gql  (str "{ venue(id: 1) { name postal_code meetups "
                      "(where: {title: {like: \"%rust%\"}}) { id title }}}")
                 [:data :venue]
                 {:name "office one" :postal_code "123456"
                  :meetups []}))

    ;; Pagination
    (testing "list entity with pagination"
      (test-gql  "{ members (limit: 1) { id first_name }}"
                 [:data :members]
                 [{:id 1 :first_name "jim"}])
      (test-gql  "{ members (offset: 1) { id first_name }}"
                 [:data :members]
                 [{:id 2 :first_name "yoshi"}]))

    ;; Sorting
    (testing "list entity sorted"
      (test-gql  "{ members (sort: {first_name: desc}) { id first_name }}"
                 [:data :members]
                 [{:id 2 :first_name "yoshi"}
                  {:id 1 :first_name "jim"}])
      (test-gql  "{ members (sort: {first_name: asc}) { id first_name }}"
                 [:data :members]
                 [{:id 1 :first_name "jim"}
                  {:id 2 :first_name "yoshi"}]))

    ;; Update mutation
    (testing "update entity"
      (test-gql (str "mutation {updateMember (id: 1 email: \"ken@test.com\" "
                     "first_name: \"Ken\" last_name: \"Spencer\") {result}}")
                [:data :updateMember :result]
                true)
      (test-gql  "{ member (id: 1) { id first_name last_name email}}"
                 [:data :member]
                 {:id 1 :first_name "Ken" :last_name "Spencer"
                  :email "ken@test.com"}))

    ;; Delete mutation
    (testing "delete entity"
      (test-gql  "mutation {deleteMember (id: 1) { result }}"
                 [:data :deleteMember :result]
                 true)
      (test-gql  "{ members { id first_name }}"
                 [:data :members]
                 [{:id 2 :first_name "yoshi"}]))
    ))

(defn- members-pre-query [sql-args ctx]
  ;; Apply filter with email from ctx
  (update sql-args :where conj [:= :email (:email ctx)]))

(defn- members-post-query [res ctx]
  ;; Replace first_name with one from ctx
  [(assoc (first res) :first_name (:first-name ctx))])

(defn- members-pre-create [sql-args ctx]
  ;; Replace email with one from ctx
  (assoc sql-args :email (:email ctx)))

(defn- members-post-create [res ctx]
  (assoc res :id (+ (:id res) (count (keys res)))))

(def ^:private signal-test-config
  {:table-name-plural true,
   :resource-path-plural true
   :tables [{:name "members"
             :columns [{:name "id" :type "integer"}
                       {:name "first_name" :type "text"}
                       {:name "last_name" :type "text"}
                       {:name "email" :type "text"}]
             :relation-types [:root]
             :belongs-to []}]
   :signal-ctx {:email "yoshi@test.com" :first-name "changed-first-name"}
   :signals {:members {:query {:pre members-pre-query
                               :post members-post-query}
                       :create {:pre members-pre-create
                                :post members-post-create}}}})

(deftest graphql-signals
  (let [db (doto (create-database)
             (jdbc/insert! :members {:email "jim@test.com"
                                     :first_name "jim"
                                     :last_name "smith"}))
        conf (assoc signal-test-config :db db)
        test-gql (fn [q res-keys expected]
                   (let [schema (gql/schema conf)
                         res (gql/exec conf schema q nil)]
                     (is (= expected (get-in res res-keys)))))]

    (testing "create 2nd user"
      (test-gql (str "mutation {createMember (email: \"input-email\" "
                     "first_name: \"yoshi\" last_name: \"tanabe\") { id }}")
                [:data :createMember :id] 6))

   ;; Queries
    (testing "list root type entity"
      (test-gql  "{ members { id email first_name }}"
                 [:data :members]
                 [{:id 2 :email "yoshi@test.com"
                   :first_name "changed-first-name"}]))))


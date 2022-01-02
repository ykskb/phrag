(ns phrag.graphql-test
  (:require [clojure.test :refer :all]
            [clojure.java.jdbc :as jdbc]
            [phrag.core-test :refer [create-db postgres-db]]
            [phrag.table :as tbl]
            [phrag.graphql :as gql]))

;; create-db: in-memory SQLite
;; postgres-db: real PostgreSQL

(deftest graphql-queries
  (let [db (create-db)
        opt {:db db
             :scan-schema true}
        conf {:db db
              :tables (tbl/schema-from-db opt)
              :use-aggregation true}
        test-gql (fn [q res-keys expected]
                   (let [schema (gql/schema conf)
                         res (gql/exec conf schema q nil {})]
                     (is (= expected (get-in res res-keys)))))]

    ;; Root entities

    (testing "create 1st user"
      (test-gql (str "mutation {createMember (email: \"jim@test.com\" "
                     "first_name: \"jim\" last_name: \"smith\") { id }}")
                [:data :createMember :id] 1))

    (testing "create 2nd user"
      (test-gql (str "mutation {createMember (email: \"yoshi@test.com\" "
                     "first_name: \"yoshi\" last_name: \"tanabe\") { id }}")
                [:data :createMember :id] 2))

    (testing "list root type entity"
      (test-gql  "{ members { id email first_name }}"
                 [:data :members]
                 [{:id 1 :email "jim@test.com" :first_name "jim"}
                  {:id 2 :email "yoshi@test.com" :first_name "yoshi"}]))

    (testing "fetch root type entity"
      (test-gql  "{ members (where: {id: {eq: 1}}) { id email first_name }}"
                 [:data :members]
                 [{:id 1 :email "jim@test.com" :first_name "jim"}]))

    (testing "aggregate root type entity"
      (test-gql "{ members_aggregate {count max {id} min {id}}}"
                [:data :members_aggregate]
                {:count 2 :max {:id 2} :min {:id 1}})
      (test-gql "{ members_aggregate {count max {id email} min {id}}}"
                [:data :members_aggregate]
                {:count 2 :max {:id 2 :email "yoshi@test.com"} :min {:id 1}}))

    ;; One-to-many relationships

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

    (testing "list entities with has-one param"
      (test-gql  (str "{ meetups { id title start_at venue_id "
                      "venue { id name }}}")
                 [:data :meetups]
                 [{:id 1 :title "rust meetup" :start_at "2021-01-01 18:00:00"
                   :venue_id 2 :venue {:id 2 :name "city hall"}}
                  {:id 2 :title "cpp meetup" :start_at "2021-01-12 18:00:00"
                   :venue_id 1 :venue {:id 1 :name "office one"}}]))

    (testing "fetch entity with has-one param"
      (test-gql  (str "{ meetups (where: {id: {eq: 1}}) "
                      "{ id title start_at venue_id venue { id name }}}")
                 [:data :meetups]
                 [{:id 1 :title "rust meetup" :start_at "2021-01-01 18:00:00"
                   :venue_id 2 :venue {:id 2 :name "city hall"}}])
      (test-gql (str  "{ meetups (where: {id: {eq: 2}}) "
                      "{ id title start_at venue_id venue { id name }}}")
                 [:data :meetups]
                 [{:id 2 :title "cpp meetup" :start_at "2021-01-12 18:00:00"
                   :venue_id 1 :venue {:id 1 :name "office one"}}]))

    (testing "list entities with has-many param"
      (test-gql (str "{ venues { id name postal_code meetups { id title }}}")
                [:data :venues]
                [{:id 1 :name "office one" :postal_code "123456"
                  :meetups [{:id 2 :title "cpp meetup"}]}
                 {:id 2 :name "city hall" :postal_code "234567"
                  :meetups [{:id 1 :title "rust meetup"}]}]))

    (testing "list entities with has-many param and aggregation"
      (test-gql (str "{ venues { id name postal_code meetups { id title } "
                     "meetups_aggregate {count max {id title} min {id}}}}")
                [:data :venues]
                [{:id 1 :name "office one" :postal_code "123456"
                  :meetups [{:id 2 :title "cpp meetup"}]
                  :meetups_aggregate {:count 1 :min {:id 2}
                                      :max {:id 2 :title "cpp meetup"}}}
                 {:id 2 :name "city hall" :postal_code "234567"
                  :meetups [{:id 1 :title "rust meetup"}]
                  :meetups_aggregate {:count 1 :min {:id 1}
                                      :max {:id 1 :title "rust meetup"}}}]))

    (testing "fetch entity with has-many param"
      (test-gql (str "{ venues (where: {id: {eq: 1}}) "
                      "{ name postal_code meetups { id title }}}")
                 [:data :venues]
                 [{:name "office one" :postal_code "123456"
                   :meetups [{:id 2 :title "cpp meetup"}]}])
      (test-gql (str  "{ venues (where: {id: {eq: 2}}) "
                      "{ name postal_code meetups { id title }}}")
                 [:data :venues]
                 [{:name "city hall" :postal_code "234567"
                   :meetups [{:id 1 :title "rust meetup"}]}]))

    (testing "fetch entity with has-many param and aggregate"
      (test-gql (str "{ venues (where: {id: {eq: 1}}) "
                     "{ name postal_code meetups { id title } "
                     "meetups_aggregate {count max {id} min {id}}}}")
                [:data :venues]
                [{:name "office one" :postal_code "123456"
                  :meetups [{:id 2 :title "cpp meetup"}]
                  :meetups_aggregate {:count 1 :min {:id 2} :max {:id 2}}}]))

    ;; Many-to-many relationships

    (testing "add member 1 to meetup 1"
      (test-gql (str "mutation {createMeetupsMember (meetup_id: 1"
                     "member_id: 1) { meetup_id member_id }}")
                [:data :createMeetupsMember]
                {:meetup_id 1 :member_id 1}))

    (testing "add member 1 to meetup 2"
      (test-gql (str "mutation {createMeetupsMember (meetup_id: 2"
                     "member_id: 1) { meetup_id member_id }}")
                [:data :createMeetupsMember]
                {:meetup_id 2 :member_id 1}))

    (testing "add member 2 to meetup 1"
      (test-gql (str "mutation {createMeetupsMember (meetup_id: 1"
                     "member_id: 2) { meetup_id member_id }}")
                [:data :createMeetupsMember]
                {:meetup_id 1 :member_id 2}))

    (testing "list entities with many-to-many param"
      (test-gql  "{ members { email meetups_members { meetup { id title }}}}"
                 [:data :members]
                 [{:email "jim@test.com"
                   :meetups_members [{:meetup {:id 1 :title "rust meetup"}}
                                     {:meetup {:id 2 :title "cpp meetup"}}]}
                  {:email "yoshi@test.com"
                   :meetups_members [{:meetup {:id 1 :title "rust meetup"}}]}]))

    (testing "list entities with many-to-many param and aggregation"
      (test-gql  (str "{ members { email meetups_members { meetup { id title }} "
                      "meetups_members_aggregate { count "
                      "max { meetup_id member_id } "
                      "min { meetup_id member_id }}}}")
                 [:data :members]
                 [{:email "jim@test.com"
                   :meetups_members [{:meetup {:id 1 :title "rust meetup"}}
                                     {:meetup {:id 2 :title "cpp meetup"}}]
                   :meetups_members_aggregate {:count 2
                                               :max {:meetup_id 2 :member_id 1}
                                               :min {:meetup_id 1 :member_id 1}}}
                  {:email "yoshi@test.com"
                   :meetups_members [{:meetup {:id 1 :title "rust meetup"}}]
                   :meetups_members_aggregate {:count 1
                                               :max {:meetup_id 1 :member_id 2}
                                               :min {:meetup_id 1 :member_id 2}}}]))

    (testing "list entities with many-to-many param and filtered aggregation"
      (test-gql  (str "{ members { email meetups_members { meetup { id title }} "
                      "meetups_members_aggregate (where: {meetup_id: {lt: 2}}) "
                      "{ count max { meetup_id member_id } "
                      "min { meetup_id member_id }}}}")
                 [:data :members]
                 [{:email "jim@test.com"
                   :meetups_members [{:meetup {:id 1 :title "rust meetup"}}
                                     {:meetup {:id 2 :title "cpp meetup"}}]
                   :meetups_members_aggregate {:count 1
                                               :max {:meetup_id 1 :member_id 1}
                                               :min {:meetup_id 1 :member_id 1}}}
                  {:email "yoshi@test.com"
                   :meetups_members [{:meetup {:id 1 :title "rust meetup"}}]
                   :meetups_members_aggregate {:count 1
                                               :max {:meetup_id 1 :member_id 2}
                                               :min {:meetup_id 1 :member_id 2}}}]))

    (testing "fetch entity with many-to-many param"
      (test-gql (str  "{ members (where: {id: {eq: 1}}) "
                      "{ email meetups_members {meetup { id title }}}}")
                 [:data :members]
                 [{:email "jim@test.com"
                   :meetups_members [{:meetup {:id 1 :title "rust meetup"}}
                                     {:meetup {:id 2 :title "cpp meetup"}}]}])
      (test-gql (str "{ members (where: {id: {eq: 2}}) "
                     "{ email meetups_members {meetup { id title }}}}")
                 [:data :members]
                 [{:email "yoshi@test.com"
                   :meetups_members [{:meetup {:id 1 :title "rust meetup"}}]}]))

    (testing "fetch entity with many-to-many param and aggregation"
      (test-gql (str  "{ members (where: {id: {eq: 1}}) "
                      "{ email meetups_members {meetup { id title }} "
                      "meetups_members_aggregate {count "
                      "max {meetup_id member_id} min {meetup_id member_id}}}}")
                [:data :members]
                [{:email "jim@test.com"
                  :meetups_members [{:meetup {:id 1 :title "rust meetup"}}
                                    {:meetup {:id 2 :title "cpp meetup"}}]
                  :meetups_members_aggregate {:count 2
                                              :max {:meetup_id 2 :member_id 1}
                                              :min {:meetup_id 1 :member_id 1}}}])
      (test-gql (str "{ members (where: {id: {eq: 2}}) "
                     "{ email meetups_members {meetup { id title }} "
                     "meetups_members_aggregate {count "
                     "max {meetup_id member_id} min {meetup_id member_id}}}}")
                [:data :members]
                [{:email "yoshi@test.com"
                  :meetups_members [{:meetup {:id 1 :title "rust meetup"}}]
                  :meetups_members_aggregate {:count 1
                                              :max {:meetup_id 1 :member_id 2}
                                              :min {:meetup_id 1 :member_id 2}}}]))

    ;; Circular many-to-many relationship

    (testing "add member 2 follow to member 1"
      (test-gql (str "mutation {createMemberFollow (member_id: 2"
                     "created_by: 1) { member_id created_by }}")
                [:data :createMemberFollow]
                {:member_id 2 :created_by 1}))

    (testing "list entities with circular many-to-many pararm"
      (test-gql (str  "{ members { first_name member_follows_on_created_by "
                      "{ member { first_name }}}}")
                 [:data :members]
                 [{:first_name "jim"
                   :member_follows_on_created_by
                   [{:member {:first_name "yoshi"}}]}
                  {:first_name "yoshi"
                   :member_follows_on_created_by []}])
      (test-gql (str  "{ members { first_name member_follows_on_member_id "
                      "{ created_by_member { first_name }}}}")
                 [:data :members]
                 [{:first_name "jim"
                   :member_follows_on_member_id []}
                  {:first_name "yoshi"
                   :member_follows_on_member_id
                   [{:created_by_member {:first_name "jim"}}]}]))

    (testing "list entities with circular many-to-many param and aggregation"
      (test-gql (str  "{ members { first_name member_follows_on_created_by "
                      "{ member { first_name }}"
                      "member_follows_on_created_by_aggregate { count "
                      "max { member_id created_by } min { member_id created_by }}}}")
                [:data :members]
                [{:first_name "jim"
                  :member_follows_on_created_by
                  [{:member {:first_name "yoshi"}}]
                  :member_follows_on_created_by_aggregate
                  {:count 1
                   :max {:member_id 2 :created_by 1}
                   :min {:member_id 2 :created_by 1}}}
                 {:first_name "yoshi"
                  :member_follows_on_created_by []
                  :member_follows_on_created_by_aggregate
                  {:count nil
                   :max {:member_id nil :created_by nil}
                   :min {:member_id nil :created_by nil}}}])
      (test-gql (str  "{ members { first_name member_follows_on_member_id "
                      "{ created_by_member { first_name }}"
                      "member_follows_on_member_id_aggregate { count "
                      "max { member_id created_by } min { member_id created_by }}}}")
                [:data :members]
                [{:first_name "jim"
                  :member_follows_on_member_id []
                  :member_follows_on_member_id_aggregate
                  {:count nil
                   :max {:member_id nil :created_by nil}
                   :min {:member_id nil :created_by nil}}}
                 {:first_name "yoshi"
                  :member_follows_on_member_id
                  [{:created_by_member {:first_name "jim"}}]
                  :member_follows_on_member_id_aggregate
                  {:count 1
                   :max {:member_id 2 :created_by 1}
                   :min {:member_id 2 :created_by 1}}}]))

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
      (test-gql (str "{ venues (where: {id: {eq: 1}}) "
                     "{ name postal_code meetups { id title }}}")
                 [:data :venues]
                 [{:name "office one" :postal_code "123456"
                   :meetups [{:id 2 :title "cpp meetup"}]}])
      (test-gql (str "{ venues (where: {id: {eq: 1}}) "
                     "{ name postal_code meetups "
                     "(where: {title: {like: \"%rust%\"}}) { id title }}}")
                 [:data :venues]
                 [{:name "office one" :postal_code "123456"
                   :meetups []}]))

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
      (test-gql (str "mutation {updateMember "
                     "(pk_columns: {id: 1} email: \"ken@test.com\" "
                     "first_name: \"Ken\" last_name: \"Spencer\") {result}}")
                [:data :updateMember :result]
                true)
      (test-gql (str  "{ members (where: {id: {eq: 1}}) "
                      "{ id first_name last_name email}}")
                 [:data :members]
                 [{:id 1 :first_name "Ken" :last_name "Spencer"
                   :email "ken@test.com"}]))

    ;; Delete mutation
    (testing "delete entity"
      (test-gql  "mutation {deleteMember (pk_columns: {id: 1}) { result }}"
                 [:data :deleteMember :result]
                 true)
      (test-gql  "{ members { id first_name }}"
                 [:data :members]
                 [{:id 2 :first_name "yoshi"}]))
    ))

;;; Testing signals

(defn- change-where-from-ctx [sql-args ctx]
  ;; Apply filter with email from ctx
  (update sql-args :where conj [:= :email (:email ctx)]))

(defn- change-res-from-ctx [res ctx]
  ;; Replace first_name with one from ctx
  [(assoc (first res) :first_name (:first-name ctx))])

(defn- change-arg-from-ctx [args ctx]
  ;; Replace email with one from ctx
  (assoc args :email (:email ctx)))

(defn- change-id-to-count [res _ctx]
  ;; expected: 7
  ;; id: 2
  ;; res keys: (:email :first_name :last_name :last_insert_rowid() :id)
  (assoc res :id (+ (:id res) (count (keys res)))))

(defn- increment-id-count [res _ctx]
  (update res :id + 1))

(defn- members-pre-update [args _ctx]
  nil)

(def ^:private signal-test-config
  {:table-name-plural true,
   :scan-schema false
   :tables [{:name "members"
             :columns [{:name "id" :type "integer"}
                       {:name "first_name" :type "text"}
                       {:name "last_name" :type "text"}
                       {:name "email" :type "text"}]
             :table-type :root
             :fks []
             :pks [{:name "id" :type "integer"}]}]
   :signal-ctx {:email "yoshi@test.com" :first-name "changed-first-name"}
   :signals {:members {:query {:pre change-where-from-ctx
                               :post [change-res-from-ctx]}
                       :create {:pre change-arg-from-ctx
                                :post [change-id-to-count
                                       increment-id-count]}
                       :update {:pre members-pre-update}}}})

(deftest graphql-signals
  (let [db (doto (create-db)
             (jdbc/insert! :members {:email "jim@test.com"
                                     :first_name "jim"
                                     :last_name "smith"}))
        conf (assoc signal-test-config :db db)
        test-gql (fn [q res-keys expected]
                   (let [schema (gql/schema conf)
                         res (gql/exec conf schema q nil {})]
                     (prn res)
                     (is (= expected (get-in res res-keys)))))]

    (testing "post-create signal mutate with ctx"
      (test-gql (str "mutation {createMember (email: \"input-email\" "
                     "first_name: \"yoshi\" last_name: \"tanabe\") { id }}")
                [:data :createMember :id] 8))

    (testing "pre-create / post-query signal"
      (test-gql  "{ members { id email first_name }}"
                 [:data :members]
                 [{:id 2 :email "yoshi@test.com"
                   :first_name "changed-first-name"}]))

    (testing "pre-update signal"
      (test-gql (str "mutation {updateMember (pk_columns: {id: 2} email: \"fake-email\" "
                     "first_name: \"fake-first-name\") { result }}")
                [:data :updateMember :result] true)
      (test-gql "{ members { id email first_name }}"
                [:data :members]
                [{:id 2 :email "yoshi@test.com"
                  :first_name "changed-first-name"}]))))


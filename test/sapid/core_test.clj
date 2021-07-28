(ns sapid.core-test
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.test :refer :all]
            [sapid.core :as sapid]
            [integrant.core :as ig]))

(def ^:private db-ref (ig/ref :test-db-ref))
(def ^:private sym-q (symbol 'q))
(def ^:private sym-b (symbol 'b))
(def ^:private sym-id (symbol 'id))
(def ^:private sym-p-id (symbol 'p-id))
(def ^:private sym-id-a (symbol 'id-a))
(def ^:private sym-id-b (symbol 'id-b))

(def ^:private root-option
  {:project-ns "my-project"
   :router :ataraxy
   :db-ref db-ref
   :db-keys nil
   :tables [{:name "members"
             :columns [{:name "id"}
                       {:name "first_name"}
                       {:name "last_name"}
                       {:name "email"}]
             :relation-types [:root]}]})

(def ^:private root-ataraxy-routes
  [{[:get "/members" {sym-q :query-params}]
    [:my-project.handler.members/list-root sym-q]}
   {[:post "/members" {sym-b :params}]
    [:my-project.handler.members/create-root sym-b]}
   {[:get "/members/" sym-id {sym-q :query-params}]
    [:my-project.handler.members/fetch-root sym-id sym-q]}
   {[:delete "/members/" sym-id]
    [:my-project.handler.members/delete-root sym-id]}
   {[:put "/members/" sym-id {sym-b :params}]
    [:my-project.handler.members/put-root sym-id sym-b]}
   {[:patch "/members/" sym-id {sym-b :params}]
    [:my-project.handler.members/patch-root sym-id sym-b]}])

(def ^:private root-ataraxy-handlers
  [{[:sapid.handler/list-root
     :my-project.handler.members/list-root]
    {:db db-ref
     :db-keys nil
     :table "members"
     :cols #{"id" "email" "last_name" "first_name"}}}
   {[:sapid.handler/create-root
     :my-project.handler.members/create-root]
    {:db db-ref
     :db-keys nil
     :table "members"
     :cols #{"id" "email" "last_name" "first_name"}}}
   {[:sapid.handler/fetch-root
     :my-project.handler.members/fetch-root]
    {:db db-ref
     :db-keys nil
     :table "members"
     :cols #{"id" "email" "last_name" "first_name"}}}
   {[:sapid.handler/delete-root
     :my-project.handler.members/delete-root]
    {:db db-ref
     :db-keys nil
     :table "members"
     :cols #{"id" "email" "last_name" "first_name"}}}
   {[:sapid.handler/put-root :my-project.handler.members/put-root]
    {:db db-ref
     :db-keys nil
     :table "members"
     :cols #{"id" "email" "last_name" "first_name"}}}
   {[:sapid.handler/patch-root
     :my-project.handler.members/patch-root]
    {:db db-ref
     :db-keys nil
     :table "members"
     :cols #{"id" "email" "last_name" "first_name"}}}])

(def ^:private one-n-option
  {:project-ns "my-project"
   :router :ataraxy
   :db-ref db-ref
   :db-keys [:spec]
   :tables [{:name "members"
             :columns [{:name "id"}
                       {:name "email"}
                       {:name "country_id"}]
             :relation-types [:one-n]
             :belongs-to ["countries"]}]})

(def ^:private one-n-ataraxy-routes
  [{[:get "/countries/" sym-id "/members" {sym-q :query-params}]
    [:my-project.handler.countries.members/list-one-n sym-id sym-q]}
   {[:post "/countries/" sym-id "/members" {sym-b :params}]
    [:my-project.handler.countries.members/create-one-n sym-id sym-b]}
   {[:get "/countries/" sym-p-id "/members/" sym-id {sym-q :query-params}]
    [:my-project.handler.countries.members/fetch-one-n sym-p-id sym-id sym-q]}
   {[:delete "/countries/" sym-p-id "/members/" sym-id]
    [:my-project.handler.countries.members/delete-one-n sym-p-id sym-id]}
   {[:put "/countries/" sym-p-id "/members/" sym-id {sym-b :params}]
    [:my-project.handler.countries.members/put-one-n sym-p-id sym-id sym-b]}
   {[:patch "/countries/" sym-p-id "/members/" sym-id {sym-b :params}]
    [:my-project.handler.countries.members/patch-one-n sym-p-id sym-id sym-b]}])

(def ^:private one-n-ataraxy-handlers
  [{[:sapid.handler/list-one-n
     :my-project.handler.countries.members/list-one-n]
    {:db db-ref
     :db-keys [:spec]
     :table "members"
     :p-col "country_id"
     :cols #{"id" "email" "country_id"}}}
   {[:sapid.handler/create-one-n
     :my-project.handler.countries.members/create-one-n]
    {:db db-ref
     :db-keys [:spec]
     :table "members"
     :p-col "country_id"
     :cols #{"id" "email" "country_id"}}}
   {[:sapid.handler/fetch-one-n
     :my-project.handler.countries.members/fetch-one-n]
    {:db db-ref
     :db-keys [:spec]
     :table "members"
     :p-col "country_id"
     :cols #{"id" "email" "country_id"}}}
   {[:sapid.handler/delete-one-n
     :my-project.handler.countries.members/delete-one-n]
    {:db db-ref
     :db-keys [:spec]
     :table "members"
     :p-col "country_id"
     :cols #{"id" "email" "country_id"}}}
   {[:sapid.handler/put-one-n
     :my-project.handler.countries.members/put-one-n]
    {:db db-ref
     :db-keys [:spec]
     :table "members"
     :p-col "country_id"
     :cols #{"id" "email" "country_id"}}}
   {[:sapid.handler/patch-one-n
     :my-project.handler.countries.members/patch-one-n]
    {:db db-ref
     :db-keys [:spec]
     :table "members"
     :p-col "country_id"
     :cols #{"id" "email" "country_id"}}}])

(def ^:private n-n-option
  {:project-ns "my-project"
   :router :ataraxy
   :db-ref db-ref
   :db-keys [:spec]
   :tables [{:name "members_groups"
             :columns [{:name "member_id"}
                       {:name "group_id"}]
             :relation-types [:n-n]
             :belongs-to ["members" "groups"]}]})

(def ^:private n-n-ataraxy-routes
  [{[:post "/members/" sym-id-a "/groups/" sym-id-b "/add" {sym-b :params}]
    [:my-project.handler.members.groups/create-n-n sym-id-a sym-id-b sym-b]}
   {[:post "/groups/" sym-id-a "/members/" sym-id-b "/add" {sym-b :params}]
    [:my-project.handler.groups.members/create-n-n sym-id-a sym-id-b sym-b]}
   {[:post "/members/" sym-id-a "/groups/" sym-id-b "/delete"]
    [:my-project.handler.members.groups/delete-n-n sym-id-a sym-id-b]}
   {[:post "/groups/" sym-id-a "/members/" sym-id-b "/delete"]
    [:my-project.handler.groups.members/delete-n-n sym-id-a sym-id-b]}
   {[:get "/members/" sym-id "/groups" {sym-q :query-params}]
    [:my-project.handler.members.groups/list-n-n sym-id sym-q]}
   {[:get "/groups/" sym-id "/members" {sym-q :query-params}]
    [:my-project.handler.groups.members/list-n-n sym-id sym-q]}])

(def ^:private n-n-ataraxy-handlers
  [{[:sapid.handler/create-n-n
     :my-project.handler.members.groups/create-n-n]
    {:db db-ref
     :db-keys [:spec]
     :table "members_groups"
     :col-a "member_id"
     :col-b "group_id"
     :cols #{"group_id" "member_id"}}}
   {[:sapid.handler/create-n-n
     :my-project.handler.groups.members/create-n-n]
    {:db db-ref
     :db-keys [:spec]
     :table "members_groups"
     :col-a "member_id"
     :col-b "group_id"
     :cols #{"group_id" "member_id"}}}
   {[:sapid.handler/delete-n-n
     :my-project.handler.members.groups/delete-n-n]
    {:db db-ref
     :db-keys [:spec]
     :table "members_groups"
     :col-a "member_id"
     :col-b "group_id"
     :cols #{"group_id" "member_id"}}}
   {[:sapid.handler/delete-n-n
     :my-project.handler.groups.members/delete-n-n]
    {:db db-ref
     :db-keys [:spec]
     :table "members_groups"
     :col-a "member_id"
     :col-b "group_id"
     :cols #{"group_id" "member_id"}}}
   {[:sapid.handler/list-n-n
     :my-project.handler.members.groups/list-n-n]
    {:db db-ref
     :db-keys [:spec]
     :nn-p-col "member_id"
     :nn-join-col "group_id"
     :table "groups"
     :nn-table "members_groups"
     :cols #{"group_id" "member_id"}}}
   {[:sapid.handler/list-n-n
     :my-project.handler.groups.members/list-n-n]
    {:db db-ref
     :db-keys [:spec]
     :nn-p-col "group_id"
     :nn-join-col "member_id"
     :table "members"
     :nn-table "members_groups"
     :cols #{"group_id" "member_id"}}}])

(deftest ataraxy-routes-creation
  (testing "root type from config"
    (is (= {:routes root-ataraxy-routes
            :handlers root-ataraxy-handlers}
           (sapid/rest-routes
            (sapid/make-rest-config root-option)))))
  (testing "one-to-n relation type from option"
    (is (= {:routes one-n-ataraxy-routes
            :handlers one-n-ataraxy-handlers}
           (sapid/rest-routes
            (sapid/make-rest-config one-n-option)))))
  (testing "n-to-n relation type from option"
    (is (= {:routes n-n-ataraxy-routes
            :handlers n-n-ataraxy-handlers}
           (sapid/rest-routes
            (sapid/make-rest-config n-n-option))))))

(defn create-database []
  (doto {:connection (jdbc/get-connection {:connection-uri "jdbc:sqlite:"})}
    (jdbc/execute! (str "create table members ("
                        "id               integer primary key, "
                        "first_name       text, "
                        "last_name        text, "
                        "email            text);"))
    (jdbc/execute! (str "create table groups ("
                        "id            integer primary key, "
                        "name          text, "
                        "created_at    timestamp);"))
    (jdbc/execute! (str "create table venues ("
                        "id               integer primary key, "
                        "name             text, "
                        "postal_code      text);"))
    (jdbc/execute! (str "create table meetups ("
                        "id              integer primary key, "
                        "title           text not null, "
                        "start_at        timestamp, "
                        "venue_id        int, "
                        "group_id        int);"))
    (jdbc/execute! (str "create table meetups_members ("
                        "meetup_id     int, "
                        "member_id     int, "
                        "primary key (meetup_id, member_id));"))
    (jdbc/execute! (str "create table groups_members ("
                        "group_id    int, "
                        "member_id   int, "
                        "primary key (group_id, member_id));"))))

(def ^:private expected-schema-map
  [{:name "members"
    :columns [{:name "id"}
              {:name "first_name"}
              {:name "last_name"}
              {:name "email"}]
    :relation-types [:root]
    :belongs-to []}
   {:name "groups"
    :columns [{:name "id"}
              {:name "name"}
              {:name "created_at"}]
    :relation-types [:root]
    :belongs-to []}
   {:name "venues"
    :columns [{:name "id"}
              {:name "name"}
              {:name "postal_code"}]
    :relation-types [:root]
    :belongs-to []}
   {:name "meetups"
    :columns [{:name "id"}
              {:name "title"}
              {:name "start_at"}
              {:name "venue_id"}
              {:name "group_id"}]
    :relation-types [:one-n :root]
    :belongs-to ["venue" "group"]}
   {:name "meetups_members"
    :columns [{:name "meetup_id"}
              {:name "member_id"}]
    :relation-types [:n-n]
    :belongs-to ["meetups" "members"]}
   {:name "groups_members"
    :columns [{:name "group_id"}
              {:name "member_id"}]
    :relation-types [:n-n]
    :belongs-to ["groups" "members"]}])

(deftest schema-map-from-db
  (testing "all relation types"
    (let [res-map
          (reduce (fn [m table]
                    (let [col-names (map #(:name %) (:columns table))]
                      (assoc m (:name table)
                             (assoc table :col-names col-names))))
                  {}
                  (sapid/schema-from-db (create-database)))]
      (doseq [exp-table expected-schema-map]
        (let [res-table (get res-map (:name exp-table))]
          (is (= (map #(:name %) (:columns exp-table)) (:col-names res-table)))
          (is (= (:relation-types exp-table) (:relation-types res-table)))
          (is (= (:belongs-to exp-table) (:belongs-to res-table))))))))

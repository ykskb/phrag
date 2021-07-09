(ns lapis.core-test
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.test :refer :all]
            [clojure.data :as d]
            [clojure.pprint :as pp]
            [lapis.core :as lapis]
            [integrant.core :as ig]))

(def ^:private db-ref (ig/ref :test-db-ref))
(def ^:private sym-q (symbol 'q))
(def ^:private sym-b (symbol 'b))
(def ^:private sym-id (symbol 'id))

(def ^:private member-root-option
  {:project-ns "my-project"
   :db-ref db-ref
   :tables [{:name "members"
             :columns [{:name "id"}
                       {:name "first_name"}
                       {:name "last_name"}
                       {:name "email"}]
             :relation-types [:root]}]})

(def ^:private member-root-routes
  (list {[:get "/members" {sym-q :query-params}]
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
         [:my-project.handler.members/patch-root sym-id sym-b]}))

(def ^:private member-root-handlers
  (list {[:my-project.handler/list-root
          :my-project.handler.members/list-root]
         {:db db-ref
          :table "members"
          :cols #{"id" "email" "last_name" "first_name"}}}
        {[:my-project.handler/create-root
          :my-project.handler.members/create-root]
         {:db db-ref
          :table "members",
          :cols #{"id" "email" "last_name" "first_name"}}}
        {[:my-project.handler/fetch-root
          :my-project.handler.members/fetch-root]
         {:db db-ref
          :table "members",
          :cols #{"id" "email" "last_name" "first_name"}}}
        {[:my-project.handler/delete-root
          :my-project.handler.members/delete-root]
         {:db db-ref
          :table "members",
          :cols #{"id" "email" "last_name" "first_name"}}}
        {[:my-project.handler/put-root :my-project.handler.members/put-root]
         {:db db-ref
          :table "members",
          :cols #{"id" "email" "last_name" "first_name"}}}
        {[:my-project.handler/patch-root
          :my-project.handler.members/patch-root]
         {:db db-ref
          :table "members",
          :cols #{"id" "email" "last_name" "first_name"}}}))

(deftest ataraxy-routes-creation
  (testing "root type from config"
    (is (= {:routes member-root-routes
            :handlers member-root-handlers}
           (lapis/rest-routes
            (lapis/make-rest-config {} member-root-option))))))

(defn- create-database []
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

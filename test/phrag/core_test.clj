(ns phrag.core-test
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.test :refer :all]
            [phrag.core :as phrag]
            [phrag.table :as tbl]
            [integrant.core :as ig]))

(def ^:private db-ref (gensym 'test-db-ref))
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
  [{[:post "/graphql" {sym-b :params}]
    [:my-project.handler.members/create-root sym-b]}])

(def ^:private root-ataraxy-handlers
  [{[:phrag.handlers.duct-ataraxy/list-root
     :my-project.handler.members/list-root]
    {:db db-ref
     :db-keys nil
     :table "members"
     :cols #{"id" "email" "last_name" "first_name"}}}])

;; (deftest ataraxy-routes-creation
;;   (testing "root type from config"
;;     (is (= {:routes root-ataraxy-routes
;;             :handlers root-ataraxy-handlers}
;;            (dissoc (rest/rest-routes (rest/make-rest-config root-option))
;;                    :swag-paths :swag-defs)))))

(defn postgres-db []
  (doto {:connection (jdbc/get-connection {:dbtype "postgresql"
                                           :dbname "postgres"
                                           :host "localhost"
                                           :port 5432
                                           :user "postgres"
                                           :password "example"
                                           :stringtype "unspecified"})}
    (jdbc/execute! (str "create table members ("
                        "id               bigserial primary key,"
                        "first_name       varchar(128),"
                        "last_name        varchar(128),"
                        "email            varchar(128));"))
    (jdbc/execute! (str "create table groups ("
                        "id            bigserial primary key,"
                        "name          varchar(128),"
                        "created_at    timestamp);"))
    (jdbc/execute! (str "create table venues ("
                        "id               bigserial primary key,"
                        "name             varchar(128),"
                        "postal_code      varchar(128));"))
    (jdbc/execute! (str "create table meetups ("
                        "id              bigserial primary key,"
                        "title           varchar(128) not null, "
                        "start_at        timestamp,"
                        "venue_id        integer,"
                        "group_id        integer);"))
    (jdbc/execute! (str "create table meetups_members ("
                        "meetup_id     integer,"
                        "member_id     integer,"
                        "primary key (meetup_id, member_id));"))
    (jdbc/execute! (str "create table groups_members ("
                        "group_id    integer,"
                        "member_id   integer,"
                        "primary key (group_id, member_id));"))))

(defn create-db []
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

;; (deftest schema-map-from-db
;;   (testing "all relation types"
;;     (let [config (rest/make-rest-config one-n-option)
;;           res-map
;;           (reduce (fn [m table]
;;                     (let [col-names (map #(:name %) (:columns table))]
;;                       (assoc m (:name table)
;;                              (assoc table :col-names col-names))))
;;                   {}
;;                   (tbl/schema-from-db config (create-database)))]
;;       (doseq [exp-table expected-schema-map]
;;         (let [res-table (get res-map (:name exp-table))]
;;           (is (= (map #(:name %) (:columns exp-table)) (:col-names res-table)))
;;           (is (= (:relation-types exp-table) (:relation-types res-table)))
;;           (is (= (:belongs-to exp-table) (:belongs-to res-table))))))))

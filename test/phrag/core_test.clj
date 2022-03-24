(ns phrag.core-test
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.test :refer :all]))

(defn postgres-db []
  (doto {:connection (jdbc/get-connection {:dbtype "postgresql"
                                           :dbname "phrag_test"
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
                        "vid              bigserial primary key,"
                        "name             varchar(128),"
                        "postal_code      varchar(128));"))
    (jdbc/execute! (str "create table meetups ("
                        "id              bigserial primary key,"
                        "title           varchar(128) not null, "
                        "start_at        timestamp,"
                        "venue_id        integer,"
                        "group_id        integer,"
                        "foreign key(venue_id) references venues(vid), "
                        "foreign key(group_id) references groups(id));"))
    (jdbc/execute! (str "create table member_follow ("
                        "created_by    integer, "
                        "member_id     integer, "
                        "foreign key(created_by) references members(id), "
                        "foreign key(member_id) references members(id), "
                        "primary key (created_by, member_id));"))
    (jdbc/execute! (str "create table meetups_members ("
                        "meetup_id     integer,"
                        "member_id     integer,"
                        "foreign key(meetup_id) references meetups(id), "
                        "foreign key(member_id) references members(id), "
                        "primary key (meetup_id, member_id));"))
    (jdbc/execute! (str "create table groups_members ("
                        "group_id    integer,"
                        "member_id   integer,"
                        "foreign key(group_id) references groups(id), "
                        "foreign key(member_id) references members(id), "
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
                        ;; testing non-"id" naming
                        "vid              integer primary key, "
                        "name             text, "
                        "postal_code      text);"))
    (jdbc/execute! (str "create table meetups ("
                        "id              integer primary key, "
                        "title           text not null, "
                        "start_at        timestamp, "
                        "venue_id        int, "
                        "group_id        int, "
                        "foreign key(venue_id) references venues(vid), "
                        "foreign key(group_id) references groups(id));"))
    (jdbc/execute! (str "create table member_follow ("
                        "created_by    int, "
                        "member_id     int, "
                        "foreign key(created_by) references members(id), "
                        "foreign key(member_id) references members(id), "
                        "primary key (created_by, member_id));"))
    (jdbc/execute! (str "create table meetups_members ("
                        "meetup_id     int, "
                        "member_id     int, "
                        "foreign key(meetup_id) references meetups(id), "
                        "foreign key(member_id) references members(id), "
                        "primary key (meetup_id, member_id));"))
    (jdbc/execute! (str "create table groups_members ("
                        "group_id    int, "
                        "member_id   int, "
                        "foreign key(group_id) references groups(id), "
                        "foreign key(member_id) references members(id), "
                        "primary key (group_id, member_id));"))))


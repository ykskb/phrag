(ns phrag.core-test
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.test :refer :all]
            [hikari-cp.core :as hkr]))

(def ^:private pg-members-table
  (str "create table members ("
       "id               bigserial primary key,"
       "first_name       varchar(128),"
       "last_name        varchar(128),"
       "email            varchar(128));"))

(def ^:private pg-groups-table
  (str "create table groups ("
       "id            bigserial primary key,"
       "name          varchar(128),"
       "created_at    timestamp);"))

(def ^:private pg-venues-table
  (str "create table venues ("
       "vid              bigserial primary key,"
       "name             varchar(128),"
       "postal_code      varchar(128));"))

(def ^:private pg-meetups-table
  (str "create table meetups ("
       "id              bigserial primary key,"
       "title           varchar(128) not null, "
       "start_at        timestamp,"
       "venue_id        integer,"
       "group_id        integer,"
       "foreign key(venue_id) references venues(vid), "
       "foreign key(group_id) references groups(id));"))

(def ^:private pg-member-follow-table
  (str "create table member_follow ("
       "created_by    integer, "
       "member_id     integer, "
       "foreign key(created_by) references members(id), "
       "foreign key(member_id) references members(id), "
       "primary key (created_by, member_id));"))

(def ^:private pg-meetups-members-table
  (str "create table meetups_members ("
       "meetup_id     integer,"
       "member_id     integer,"
       "foreign key(meetup_id) references meetups(id), "
       "foreign key(member_id) references members(id), "
       "primary key (meetup_id, member_id));"))

(def ^:private pg-groups-members-table
  (str "create table groups_members ("
       "group_id    integer,"
       "member_id   integer,"
       "foreign key(group_id) references groups(id), "
       "foreign key(member_id) references members(id), "
       "primary key (group_id, member_id));"))

(defn postgres-conn []
  (doto {:connection (jdbc/get-connection {:dbtype "postgresql"
                                           :dbname "phrag_test"
                                           :host "localhost"
                                           :port 5432
                                           :user "postgres"
                                           :password "example"
                                           :stringtype "unspecified"})}
    (jdbc/execute! pg-members-table)
    (jdbc/execute! pg-groups-table)
    (jdbc/execute! pg-venues-table)
    (jdbc/execute! pg-meetups-table)
    (jdbc/execute! pg-member-follow-table)
    (jdbc/execute! pg-meetups-members-table)
    (jdbc/execute! pg-groups-members-table)))

(defn postgres-data-src []
  (let [data-src (delay (hkr/make-datasource {:adapter "postgresql"
                                              :username "postgres"
                                              :password "example"
                                              :database-name "phrag_test"
                                              :server-name "localhost"
                                              :port-number 5432
                                              ;; :stringtype "unspecified"
                                              :current-schema "public"}))
        db {:datasource @data-src}]
    (doto db
      (jdbc/execute! pg-members-table)
      (jdbc/execute! pg-groups-table)
      (jdbc/execute! pg-venues-table)
      (jdbc/execute! pg-meetups-table)
      (jdbc/execute! pg-member-follow-table)
      (jdbc/execute! pg-meetups-members-table)
      (jdbc/execute! pg-groups-members-table))))

(def ^:private sqlite-members-table
  (str "create table members ("
       "id               integer primary key, "
       "first_name       text, "
       "last_name        text, "
       "email            text);"))

(def ^:private sqlite-groups-table
  (str "create table groups ("
       "id            integer primary key, "
       "name          text, "
       "created_at    timestamp);"))

(def ^:private sqlite-venues-table
  (str "create table venues ("
       ;; testing non-"id" naming
       "vid              integer primary key, "
       "name             text, "
       "postal_code      text);"))

(def ^:private sqlite-meetups-table
  (str "create table meetups ("
       "id              integer primary key, "
       "title           text not null, "
       "start_at        timestamp, "
       "venue_id        int, "
       "group_id        int, "
       "foreign key(venue_id) references venues(vid), "
       "foreign key(group_id) references groups(id));"))

(def ^:private sqlite-member-follow-table
  (str "create table member_follow ("
       "created_by    int, "
       "member_id     int, "
       "foreign key(created_by) references members(id), "
       "foreign key(member_id) references members(id), "
       "primary key (created_by, member_id));"))

(def ^:private sqlite-meetups-members-table
  (str "create table meetups_members ("
       "meetup_id     int, "
       "member_id     int, "
       "foreign key(meetup_id) references meetups(id), "
       "foreign key(member_id) references members(id), "
       "primary key (meetup_id, member_id));"))

(def ^:private sqlite-groups-members-table
  (str "create table groups_members ("
       "group_id    int, "
       "member_id   int, "
       "foreign key(group_id) references groups(id), "
       "foreign key(member_id) references members(id), "
       "primary key (group_id, member_id));"))

(def ^:private sqlite-meetups-with-venue-name
  (str "create view meetup_with_venue as "
       "select m.id, "
       "m.title, "
       "v.vid as venue_id, "
       "v.name as venue_name "
       "from meetups as m "
       "join venues as v on m.venue_id = v.vid;"))

(defn sqlite-conn []
  (doto {:connection (jdbc/get-connection {:connection-uri "jdbc:sqlite:"})}
    (jdbc/execute! sqlite-members-table)
    (jdbc/execute! sqlite-groups-table)
    (jdbc/execute! sqlite-venues-table)
    (jdbc/execute! sqlite-meetups-table)
    (jdbc/execute! sqlite-member-follow-table)
    (jdbc/execute! sqlite-meetups-members-table)
    (jdbc/execute! sqlite-groups-members-table)
    (jdbc/execute! sqlite-meetups-with-venue-name)))

(defn sqlite-data-src []
  (let [data-src (delay (hkr/make-datasource
                         {:jdbc-url "jdbc:sqlite:"}))
        db {:datasource @data-src}]
    (doto db
      (jdbc/execute! sqlite-members-table)
      (jdbc/execute! sqlite-groups-table)
      (jdbc/execute! sqlite-venues-table)
      (jdbc/execute! sqlite-meetups-table)
      (jdbc/execute! sqlite-member-follow-table)
      (jdbc/execute! sqlite-meetups-members-table)
      (jdbc/execute! sqlite-groups-members-table)
      (jdbc/execute! sqlite-meetups-with-venue-name))))


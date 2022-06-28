(ns phrag.core-test
  (:require [clojure.java.jdbc :as jdbc]
            [environ.core :refer [env]]
            [clojure.test :refer :all]
            [hikari-cp.core :as hkr]))

(def ^:private pg-members-table
  (str "CREATE TABLE IF NOT EXISTS members ("
       "id               bigserial primary key,"
       "first_name       varchar(128),"
       "last_name        varchar(128),"
       "email            varchar(128));"))

(def ^:private pg-groups-table
  (str "CREATE TABLE IF NOT EXISTS groups ("
       "id            bigserial primary key,"
       "name          varchar(128),"
       "created_at    timestamp);"))

(def ^:private pg-venues-table
  (str "CREATE TABLE IF NOT EXISTS venues ("
       "vid              bigserial primary key,"
       "name             varchar(128),"
       "postal_code      varchar(128));"))

(def ^:private pg-meetups-table
  (str "CREATE TABLE IF NOT EXISTS meetups ("
       "id              bigserial primary key,"
       "title           varchar(128) not null, "
       "start_at        timestamp,"
       "venue_id        integer,"
       "group_id        integer,"
       "foreign key(venue_id) references venues(vid), "
       "foreign key(group_id) references groups(id));"))

(def ^:private pg-member-follow-table
  (str "CREATE TABLE IF NOT EXISTS member_follow ("
       "created_by    integer, "
       "member_id     integer, "
       "foreign key(created_by) references members(id), "
       "foreign key(member_id) references members(id), "
       "primary key (created_by, member_id));"))

(def ^:private pg-meetups-members-table
  (str "CREATE TABLE IF NOT EXISTS meetups_members ("
       "meetup_id     integer,"
       "member_id     integer,"
       "foreign key(meetup_id) references meetups(id), "
       "foreign key(member_id) references members(id), "
       "primary key (meetup_id, member_id));"))

(def ^:private pg-groups-members-table
  (str "CREATE TABLE IF NOT EXISTS groups_members ("
       "group_id    integer,"
       "member_id   integer,"
       "foreign key(group_id) references groups(id), "
       "foreign key(member_id) references members(id), "
       "primary key (group_id, member_id));"))

(def ^:private pg-meetups-with-venue-name
  (str "CREATE OR REPLACE VIEW meetup_with_venue AS "
       "SELECT m.id, "
       "m.title, "
       "v.vid AS venue_id, "
       "v.name AS venue_name "
       "FROM meetups AS m "
       "JOIN venues AS v ON m.venue_id = v.vid;"))

(def ^:private pg-clean-up
  (str "DELETE FROM meetups_members;"
       "DELETE FROM groups_members;"
       "DELETE FROM member_follow;"
       "DELETE FROM meetups;"
       "ALTER SEQUENCE meetups_id_seq RESTART WITH 1;"
       "DELETE FROM venues;"
       "ALTER SEQUENCE venues_vid_seq RESTART WITH 1;"
       "DELETE FROM groups;"
       "ALTER SEQUENCE groups_id_seq RESTART WITH 1;"
       "DELETE FROM members;"
       "ALTER SEQUENCE members_id_seq RESTART WITH 1;"))

(defn postgres-testable? []
  (and (env :db-name)
       (env :db-host)
       (env :db-user)
       (env :db-pass)))

(defn postgres-conn []
  (doto {:connection (jdbc/get-connection {:dbtype "postgresql"
                                           :dbname (env :db-name)
                                           :host (env :db-host)
                                           :port (env :db-port 5432)
                                           :user (env :db-user)
                                           :password (env :db-pass)
                                           :stringtype "unspecified"})}
    (jdbc/execute! pg-members-table)
    (jdbc/execute! pg-groups-table)
    (jdbc/execute! pg-venues-table)
    (jdbc/execute! pg-meetups-table)
    (jdbc/execute! pg-member-follow-table)
    (jdbc/execute! pg-meetups-members-table)
    (jdbc/execute! pg-groups-members-table)
    (jdbc/execute! pg-meetups-with-venue-name)
    (jdbc/execute! pg-clean-up)))

(defn postgres-data-src []
  (let [data-src (delay (hkr/make-datasource {:adapter "postgresql"
                                              :username (env :db-user)
                                              :password (env :db-pass)
                                              :database-name (env :db-name)
                                              :server-name (env :db-host)
                                              :port-number (env :db-port 5432)
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
  (str "CREATE TABLE members ("
       ;; Column types in capital
       "id               INTEGER PRIMARY KEY, "
       "first_name       TEXT, "
       "last_name        TEXT, "
       "email            TEXT);"))

(def ^:private sqlite-groups-table
  (str "CREATE TABLE groups ("
       "id            integer primary key, "
       "name          text, "
       "created_at    timestamp);"))

(def ^:private sqlite-venues-table
  (str "CREATE TABLE venues ("
       ;; testing non-"id" naming
       "vid              integer primary key, "
       "name             text, "
       "postal_code      text);"))

(def ^:private sqlite-meetups-table
  (str "CREATE TABLE meetups ("
       "id              integer primary key, "
       "title           text not null, "
       "start_at        timestamp, "
       "venue_id        int, "
       "group_id        int, "
       "FOREIGN KEY(venue_id) REFERENCES venues(vid), "
       "FOREIGN KEY(group_id) REFERENCES groups(id));"))

(def ^:private sqlite-member-follow-table
  (str "CREATE TABLE member_follow ("
       "created_by    int, "
       "member_id     int, "
       "FOREIGN KEY(created_by) REFERENCES members(id), "
       "FOREIGN KEY(member_id) REFERENCES members(id), "
       "PRIMARY KEY (created_by, member_id));"))

(def ^:private sqlite-meetups-members-table
  (str "CREATE TABLE meetups_members ("
       "meetup_id     int, "
       "member_id     int, "
       "FOREIGN KEY(meetup_id) REFERENCES meetups(id), "
       "FOREIGN KEY(member_id) REFERENCES members(id), "
       "PRIMARY KEY (meetup_id, member_id));"))

(def ^:private sqlite-groups-members-table
  (str "CREATE TABLE groups_members ("
       "group_id    int, "
       "member_id   int, "
       "FOREIGN KEY(group_id) REFERENCES groups(id), "
       "FOREIGN KEY(member_id) REFERENCES members(id), "
       "PRIMARY KEY (group_id, member_id));"))

(def ^:private sqlite-meetups-with-venue-name
  (str "CREATE VIEW meetup_with_venue AS "
       "SELECT m.id, "
       "m.title, "
       "v.vid AS venue_id, "
       "v.name AS venue_name "
       "FROM meetups AS m "
       "JOIN venues AS v ON m.venue_id = v.vid;"))

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


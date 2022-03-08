(ns phrag.table-test
  (:require [clojure.test :refer :all]
            [clojure.set :as st]
            [clojure.java.jdbc :as jdbc]
            [com.walmartlabs.lacinia :as lcn]
            [phrag.core-test :refer [create-db postgres-db]]
            [phrag.table :as tbl]))

(defn- subset-maps? [expected subject id-key]
  (let [exp-map (zipmap (map id-key expected) expected)
        sbj-map (zipmap (map id-key subject) subject)]
    (every? (fn [[k v]]
              (is (st/subset? (set v) (set (get sbj-map k)))))
            exp-map)))

(defn- schema-as-expected? [expected subject]
  (let [exp-map (zipmap (map :name expected) expected)
        sbj-map (zipmap (map :name subject) subject)]
    (every? (fn [[sbj-tbl-name sbj-tbl]]
              (let [exp-tbl (get exp-map sbj-tbl-name)]
                (every? (fn [k]
                          (cond
                            (= :name k) (is (:name exp-tbl) (:name sbj-tbl))
                            (= :table-type k) (is (:table-type exp-tbl)
                                                  (:table-type sbj-tbl))
                            (= :columns k) (subset-maps? (:columns exp-tbl)
                                                         (:columns sbj-tbl) :name)
                            (= :fks k) (subset-maps? (:fks exp-tbl)
                                                     (:fks sbj-tbl) :from)
                            (= :pks k) (subset-maps? (:pks exp-tbl)
                                                     (:pks sbj-tbl) :name)))
                        (keys exp-tbl))))
            sbj-map)))

(def ^:private members
  {:name "members"
   :columns [{:name "id" :type "integer"}
             {:name "first_name" :type "text"}
             {:name "last_name" :type "text"}
             {:name "email" :type "text"}]
   :table-type :root
   :fks []
   :pks [{:name "id" :type "integer"}]})

(def ^:private groups
  {:name "groups"
   :columns [{:name "id" :type "integer"}
             {:name "name" :type "text"}
             {:name "created_at" :type "timestamp"}]
   :table-type :root
   :fks []
   :pks [{:name "id" :type "integer"}]})

(def ^:private venues
  {:name "venues"
   :columns [{:name "id" :type "integer"}
             {:name "name" :type "text"}
             {:name "postal_code" :type "text"}]
   :table-type :root
   :fks []
   :pks [{:name "id" :type "integer"}]})

(def ^:private meetups
  {:name "meetups"
   :columns [{:name "id" :type "integer"}
             {:name "title" :type "text"}
             {:name "start_at" :type "timestamp"}
             {:name "venue_id" :type "int"}
             {:name "group_id" :type "int"}]
   :table-type :root
   :fks [{:table "venues" :from "venue_id" :to "id"}
         {:table "groups" :from "group_id" :to "id"}]
   :pks [{:name "id" :type "integer"}]})

(def ^:private meetups-members
  {:name "meetups_members"
   :columns [{:name "meetup_id" :type "int"}
             {:name "member_id" :type "int"}]
   :table-type :pivot
   :fks [{:table "meetups" :from "meetup_id" :to "id"}
         {:table "members" :from "member_id" :to "id"}]
   :pks [{:name "meetup_id" :type "int"}
         {:name "member_id" :type "int"}]})



(deftest db-schema-with-fks
  (let [db (create-db)]
    (testing "scan DB with fk: no config table data"
      (schema-as-expected?
       [members
        groups
        venues
        meetups
        meetups-members]
       (tbl/db-schema {:db db
                            :scan-schema true
                            :no-fk-on-db false
                            :tables []})))

    (testing "scan DB with fk: additional config table data"
      (let [extra-table {:name "extra"
                         :columns [{:name "extra-column" :type "extra-type"}]}]
        (schema-as-expected?
         [members
          groups
          venues
          meetups
          meetups-members
          extra-table]
         (tbl/db-schema {:db db
                              :scan-schema true
                              :no-fk-on-db false
                              :tables [extra-table]}))))

    (testing "scan DB with fk: config table data to override"
      (let [venues-columns [{:name "id" :type "integer"}]
            meetups-fks [{:table "venues" :from "venue_id" :to "id"}]]
        (schema-as-expected?
         [members
          groups
          (assoc venues :columns venues-columns)
          (assoc meetups :fks meetups-fks)
          meetups-members]
         (tbl/db-schema {:db db
                              :scan-schema true
                              :no-fk-on-db false
                              :tables [{:name "venues"
                                        :columns venues-columns}
                                       {:name "meetups"
                                        :fks meetups-fks}]}))))))

(defn db-without-fk []
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

(deftest db-schema-without-fk
  (let [db (db-without-fk)]
    (testing  "scan DB without fk: no config table and plural table name"
      (schema-as-expected?
       [members
        groups
        venues
        meetups
        meetups-members]
       (tbl/db-schema {:db db
                            :scan-schema true
                            :no-fk-on-db true
                            :plural-table-name true
                            :tables []})))))

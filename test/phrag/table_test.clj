(ns phrag.table-test
  (:require [clojure.test :refer :all]
            [clojure.set :as st]
            [clojure.java.jdbc :as jdbc]
            [com.walmartlabs.lacinia :as lcn]
            [phrag.core-test :refer [sqlite-conn]]
            [phrag.db.adapter :as db-adapter]
            [phrag.table :as tbl]))

;; Schema data validation

(defn- subset-maps? [expected subject id-key]
  (let [exp-map (zipmap (map id-key expected) expected)
        sbj-map (zipmap (map id-key subject) subject)]
    (every? (fn [[k v]]
              (is (st/subset? (set v) (set (get sbj-map k)))))
            exp-map)))

(defn- schema-as-expected? [expected subject]
  (let [exp-map (zipmap (map :name expected) expected)
        sbj-map (zipmap (map :name subject) subject)]
    (is (not (or (nil? subject) (empty? subject))))
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

;; Expected table data

(def ^:private members
  {:name "members"
   :columns [{:name "id" :type "integer"}
             {:name "first_name" :type "text"}
             {:name "last_name" :type "text"}
             {:name "email" :type "text"}]
   :fks []
   :pks [{:name "id" :type "integer"}]})

(def ^:private groups
  {:name "groups"
   :columns [{:name "id" :type "integer"}
             {:name "name" :type "text"}
             {:name "created_at" :type "timestamp"}]
   :fks []
   :pks [{:name "id" :type "integer"}]})

(def ^:private venues
  {:name "venues"
   :columns [{:name "vid" :type "integer"}
             {:name "name" :type "text"}
             {:name "postal_code" :type "text"}]
   :fks []
   :pks [{:name "vid" :type "integer"}]})

(def ^:private meetups
  {:name "meetups"
   :columns [{:name "id" :type "integer"}
             {:name "title" :type "text"}
             {:name "start_at" :type "timestamp"}
             {:name "venue_id" :type "int"}
             {:name "group_id" :type "int"}]
   :fks [{:table "venues" :from "venue_id" :to "vid"}
         {:table "groups" :from "group_id" :to "id"}]
   :pks [{:name "id" :type "integer"}]})

(def ^:private meetups-members
  {:name "meetups_members"
   :columns [{:name "meetup_id" :type "int"}
             {:name "member_id" :type "int"}]
   :fks [{:table "meetups" :from "meetup_id" :to "id"}
         {:table "members" :from "member_id" :to "id"}]
   :pks [{:name "meetup_id" :type "int"}
         {:name "member_id" :type "int"}]})

(deftest db-schema-with-fks
  (let [db (sqlite-conn)]
    (testing "scan DB with fk: no config table data"
      (schema-as-expected?
       [members
        groups
        venues
        meetups
        meetups-members]
       (-> (tbl/db-schema {:db db
                           :db-adapter (db-adapter/db->adapter db)
                           :scan-tables true
                           :tables []})
           :tables)))

    (testing "scan DB with fk: additional config table data"
      (let [extra-table {:name "extra"
                         :columns [{:name "extra-column" :type "extra-type"}]
                         :pks [{:name "extra-column" :type "extra-type"}]}]
        (schema-as-expected?
         [members
          groups
          venues
          meetups
          meetups-members
          extra-table]
         (-> (tbl/db-schema {:db db
                             :db-adapter (db-adapter/db->adapter db)
                             :scan-tables true
                             :tables [extra-table]})
             :tables))))

    (testing "scan DB with fk: config table data to override"
      (let [venues-columns [{:name "id" :type "integer"}]
            meetups-fks [{:table "venues" :from "venue_id" :to "vid"}]]
        (schema-as-expected?
         [members
          groups
          (assoc venues :columns venues-columns)
          (assoc meetups :fks meetups-fks)
          meetups-members]
         (-> (tbl/db-schema {:db db
                             :db-adapter (db-adapter/db->adapter db)
                             :scan-tables true
                             :tables [{:name "venues"
                                       :columns venues-columns}
                                      {:name "meetups"
                                       :fks meetups-fks}]})
             :tables))))))

(def ^:private meetups-with-venues
  {:name "meetup_with_venue"
   :columns [{:name "id" :type "integer"}
             {:name "title" :type "text"}
             {:name "venue_id" :type "integer"}
             {:name "venue_name" :type "text"}]
   :fks []
   :pks []})

(deftest db-view-schema
  (let [db (sqlite-conn)]
    (testing "scan DB for view schema"
      (schema-as-expected?
       [meetups-with-venues]
       (-> (tbl/db-schema {:db db
                           :db-adapter (db-adapter/db->adapter db)
                           :scan-tables true
                           :scan-views true
                           :tables []})
           :views)))

    (testing "views not scanned for config false"
      (let [scm (tbl/db-schema {:db db
                                :db-adapter (db-adapter/db->adapter db)
                                :scan-tables true
                                :scan-views false})]
        (is (empty? (:views scm)))
        (is (not-empty (:tables scm)))))))

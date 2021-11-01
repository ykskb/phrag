(ns phrag.table-test
  (:require [clojure.test :refer :all]
            [clojure.set :as st]
            [clojure.java.jdbc :as jdbc]
            [com.walmartlabs.lacinia :as lcn]
            [phrag.core-test :refer [create-db postgres-db]]
            [phrag.table :as tbl]
            [phrag.graphql :as gql]))

(defn- subset-maps? [expected subject id-key]
  (let [exp-map (zipmap (map id-key expected) expected)
        sbj-map (zipmap (map id-key subject) subject)]
    (every? (fn [[k v]]
              (is (st/subset? (set v) (set (get sbj-map k)))))
            exp-map)))

(defn- schema-as-expected? [expected schema]
  (let [exp-map (zipmap (map :name expected) expected)
        scm-map (zipmap (map :name schema) schema)]
    (every? (fn [[tbl-name tbl]]
              (let [exp-tbl (get exp-map tbl-name)]
                (every? (fn [k]
                          (cond
                            (= :name k) (is (:name exp-tbl) (:name tbl))
                            (= :table-type k) (is (:table-type exp-tbl)
                                                  (:table-type tbl))
                            (= :columns k) (subset-maps? (:columns exp-tbl)
                                                         (:columns tbl) :name)
                            (= :fks k) (subset-maps? (:fks exp-tbl)
                                                     (:fks tbl) :from)
                            (= :pks k) (subset-maps? (:pks exp-tbl)
                                                     (:pks tbl) :name)))
                        (keys exp-tbl))))
            scm-map)))

(deftest db-schema
  (let [db (create-db)]
    (testing "scan schema with fk nor table data"
      (schema-as-expected?
           [{:name "members"
             :columns [{:name "id" :type "integer"}
                       {:name "first_name" :type "text"}
                       {:name "last_name" :type "text"}
                       {:name "email" :type "text"}]
             :table-type :root
             :fks []
             :pks [{:name "id" :type "integer"}]}
            {:name "venues"
             :columns [{:name "id" :type "integer"}
                       {:name "name" :type "text"}
                       {:name "postal_code" :type "text"}]
             :table-type :root
             :fks []
             :pks [{:name "id" :type "integer"}]}
            {:name "meetups"
             :columns [{:name "id" :type "integer"}
                       {:name "title" :type "text"}
                       {:name "start_at" :type "timestamp"}
                       {:name "venue_id" :type "int"}
                       {:name "group_id" :type "int"}]
             :table-type :root
             :fks [{:table "venues" :from "venue_id" :to "id"}
                   {:table "groups" :from "group_id" :to "id"}]
             :pks [{:name "id" :type "integer"}]}
            {:name "meetups_members"
             :columns [{:name "meetup_id" :type "int"}
                       {:name "member_id" :type "int"}]
             :table-type :pivot
             :fks [{:table "meetups" :from "meetup_id" :to "id"}
                   {:table "members" :from "member_id" :to "id"}]
             :pks []}]
           (tbl/schema-from-db {:db db
                                :scan-schema true
                                :no-fk-on-db false
                                :tables []})))))

(ns sapid.swagger-test
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.test :refer :all]
            [sapid.swagger :as swg]))

(def ^:private root-table
  {:name "members",
   :columns
   [{:cid 0 :name "id" :type "integer"
     :notnull 0 :dflt_value nil :pk 1}
    {:cid 1 :name "name" :type "text",
     :notnull 0 :dflt_value nil :pk 0}]
   :fks []
   :relation-types [:root]
   :belongs-to []})

(def ^:private root-defs
  {"members" {:properties {"name" {:type "string"}
                           "id" {:format "int64" :type "integer"}}
              :required []
              :type "object"}})

(def ^:private member-id-q-param
  {:name "id" :format "int64" :in "query" :required false :type "integer"})

(def ^:private member-name-q-param
  {:name "name" :in "query" :required false :type "string"})

(def ^:private order-by-q-param
  {:name "order-by" :in "query" :required false :type "string"
   :description "Format of [column]:asc or [column]:desc is supported."})

(def ^:private limit-q-param
  {:name "limit" :in "query" :required false :type "integer"
   :description "Number of items to limit."})

(def ^:private offset-q-param
  {:name "offset" :in "query" :required false :type "integer"
   :description "Number of items to offset."})

(def ^:private member-list-params
  [member-id-q-param member-name-q-param
   order-by-q-param limit-q-param offset-q-param])

(def ^:private member-list-details
  {:consumes ["application/json"]
   :parameters member-list-params
   :produces ["application/json"]
   :responses
   {:200 {:schema {:items {"$ref" "#/definitions/members"} :type "array"}}},
   :summary "List members"
   :tags ["members"]})

(def ^:private member-body-param
  {:description "Payload for members",
   :in "body" :name "body" :required true,
   :schema {"$ref" "#/definitions/members"}})

(def ^:private member-create-details
  {:consumes ["application/json"]
   :parameters [member-body-param]
   :produces ["application/json"]
   :responses []
   :summary "Create members"
   :tags ["members"]})

(def ^:private member-country-id-path-param
  {:format "int64" :in "path" :name "memberId" :required true :type "integer"})

(def ^:private member-delete-details
  {:consumes ["application/json"],
   :parameters [member-country-id-path-param]
   :produces ["application/json"],
   :responses [],
   :summary "Delete members",
   :tags ["members"]})

(def ^:private member-fetch-details
  {:consumes ["application/json"],
   :parameters
   [member-id-q-param
    member-name-q-param
    member-country-id-path-param]
   :produces ["application/json"]
   :responses {:200 {:schema {"$ref" "#/definitions/members"}}}
   :summary "Fetch members"
   :tags ["members"]})

(def ^:private member-update-details
  {:consumes ["application/json"],
   :parameters [member-body-param member-country-id-path-param]
   :produces ["application/json"]
   :responses []
   :summary "Update members"
   :tags ["members"]})

(deftest swagger-root
  (let [config {:table-name-plural true,
                :resource-path-plural true}
        table root-table
        result (swg/root config table)
        defs (first (:swag-defs result))
        paths (first (:swag-paths result))
        root-details (get paths "/members")
        id-path-details (get paths "/members/{memberId}")]
    (testing "root definition"
      (is (= root-defs defs)))
    (testing "root list"
      (is (= member-list-details (:get root-details))))
    (testing "root create"
      (is (= member-create-details (:post root-details))))
    (testing "root fetch"
      (is (= member-fetch-details (:get id-path-details))))
    (testing "root delete"
      (is (= member-delete-details (:delete id-path-details))))
    (testing "root put"
      (is (= member-update-details (:put id-path-details))))
    (testing "root patch"
      (is (= member-update-details (:patch id-path-details))))))

(def ^:private one-n-table
  {:name "members",
   :columns
   [{:cid 0 :name "id" :type "integer"
     :notnull 0 :dflt_value nil :pk 1}
    {:cid 1 :name "name" :type "text",
     :notnull 0 :dflt_value nil :pk 0}]
   :fks []
   :relation-types [:one-n]
   :belongs-to ["country"]})

(def ^:private country-id-path-param
  {:format "int64" :in "path" :name "countryId" :required true :type "integer"})

(deftest swagger-one-n
  (let [config {:table-name-plural true,
                :resource-path-plural true}
        table one-n-table
        result (swg/one-n config table "country")
        defs (first (:swag-defs result))
        paths (first (:swag-paths result))
        details (get paths "/countries/{countryId}/members")
        id-path-details (get paths"/countries/{countryId}/members/{memberId}")]
    (testing "one-n list"
      (let [params (apply conj (subvec member-list-params 0 2)
                          country-id-path-param (subvec member-list-params 2))
            expected (-> member-list-details
                         (assoc :parameters params)
                         (assoc :tags ["countries"]))]
        (is (= expected (:get details)))))
    (testing "one-n create"
      (let [expected (-> member-create-details
                         (update-in  [:parameters] conj country-id-path-param)
                         (assoc :tags ["countries"]))]
        (is (= expected (:post details)))))
    (testing "one-n fetch"
      (let [expected (-> member-fetch-details
                         (update-in [:parameters] conj country-id-path-param)
                         (assoc :tags ["countries"]))]
        (is (= expected (:get id-path-details)))))
    (testing "one-n delete"
      (let [expected (-> member-delete-details
                         (update-in [:parameters] conj country-id-path-param)
                         (assoc :tags ["countries"]))]
        (is (= expected (:delete id-path-details)))))
    (testing "one-n put"
      (let [expected (-> member-update-details
                         (update-in [:parameters] conj country-id-path-param)
                         (assoc :tags ["countries"]))]
        (is (= expected (:put id-path-details)))))
    (testing "one-n patch"
      (let [expected (-> member-update-details
                         (update-in [:parameters] conj country-id-path-param)
                         (assoc :tags ["countries"]))]
        (is (= expected (:patch id-path-details)))))))


(ns sapid.swagger
  (:require [clojure.string :as s]
            [inflections.core :as inf]))

(def ^:private swag-types
  {"int" "integer"
   "integer" "integer"
   "text" "string"
   "timestamp" "string"})

(defn- to-path-rsc [rsc config]
  (if (:resource-path-plural config) (inf/plural rsc) (inf/singular rsc)))

(defn- ref-responses
  ([rsc-def list?]
   (let [ref {"$ref" (str "#/definitions/" rsc-def)}]
     {:200 {:schema (if list? {:type "array" :items ref} ref)}}))
  ([rsc-def]
   (ref-responses rsc-def false)))

(defn- path-param [param]
  {:name (name param)
   :in "path"
   :required true
   :type "integer"
   :format "int64"})

(defn- body-params [def-name]
  [{:in "body"
    :name "body"
    :description (str "Payload for " def-name)
    :required true
    :schema {"$ref" (str "#/definitions/" def-name)}}])

(defn- query-params [table]
  (map (fn [col]
         (let [name (:name col)
               type (:type col)
               swag-type (get swag-types type)]
           (cond-> {:name name
                    :in "query"
                    :required false
                    :type swag-type}
             (= type "timestamp") (assoc :format "date-time")
             (= swag-type "integer") (assoc :format "int32"))))
       (:columns table)))

(defn- method-details
  ([tag summary params responses]
  {:tags [tag]
   :summary summary
   :consumes ["application/json"]
   :produces ["application/json"]
   :parameters params
   :responses responses})
  ([tag action params]
   (method-details tag action params []))
  ([tag action]
   (method-details tag action [] [])))

(defn- path-details
  ([table rsc tag path-params]
   (let [def-name (s/capitalize rsc)
         get-params (apply conj (query-params table) path-params)
         post-params (apply conj (body-params def-name) path-params)]
     {:get (method-details tag (str "List " def-name)
                           get-params (ref-responses def-name true))
      :post (method-details tag (str "Create " def-name) post-params)}))
  ([table rsc tag]
   (path-details table rsc tag [])))

(defn- id-path-details [table rsc tag path-params]
  (let [def-name (s/capitalize rsc)
        get-params (apply conj (query-params table) path-params)
        update-params (apply conj (body-params def-name) path-params)
        get-smry (str "Fetch " def-name)
        del-smry(str "Delete " def-name)
        update-smry (str "Update " def-name)]
    {:get (method-details tag get-smry get-params (ref-responses def-name))
     :delete (method-details tag del-smry path-params)
     :put (method-details tag update-smry update-params)
     :patch (method-details tag update-smry update-params)}))

(defn- n-n-create-details [table rsc-a rsc-b path-params post?]
  (let [def-name (s/capitalize (:name table))
        post-params (apply conj (body-params def-name) path-params)
        post-smry (if post? (str "Add " rsc-b " to " rsc-a)
                      (str "Delete " rsc-b " from " rsc-a))]
    {:post (method-details rsc-a post-smry post-params)}))

(defn- root-paths [config table]
  (let [rsc (to-path-rsc (:name table) config)
        id-param (path-param "id")]
    {(str "/" rsc) (path-details table rsc rsc)
     (str "/" rsc "/{id}") (id-path-details table rsc rsc [id-param])}))

(defn- one-n-paths [config table p-rsc]
  (let [c-rsc (to-path-rsc (:name table) config)
        p-rsc (to-path-rsc p-rsc config)
        id-param (path-param "id")
        p-id-param (path-param "pId")]
    {(str "/" p-rsc "/{pId}/" c-rsc) (path-details table c-rsc p-rsc [p-id-param])
     (str "/" p-rsc "/{pId}/" c-rsc "/{id}") (id-path-details table c-rsc p-rsc
                                                              [id-param p-id-param])}))

(defn- n-n-create-paths [config table]
  (let [rsc-a (to-path-rsc (first (:belongs-to table)) config)
        rsc-b (to-path-rsc (second (:belongs-to table)) config)
        a-add-path (str "/" rsc-a "/{pId}/" rsc-b "/{id}/add")
        b-add-path (str "/" rsc-b "/{pId}/" rsc-a "/{id}/add")
        a-del-path (str "/" rsc-a "/{pId}/" rsc-b "/{id}/delete")
        b-del-path (str "/" rsc-b "/{pId}/" rsc-a "/{id}/delete")
        path-params [(path-param "id") (path-param "pId")]]
    {a-add-path (n-n-create-details table rsc-a rsc-b path-params true)
     b-add-path (n-n-create-details table rsc-b rsc-a path-params true)
     a-del-path (n-n-create-details table rsc-a rsc-b path-params false)
     b-del-path (n-n-create-details table rsc-b rsc-a path-params false)}))

(defn- cols->required [cols]
  (reduce (fn [v col]
            (if (or (= 0 (:notnull col)) (some? (:dflt_value col)))
              v (conj v (:name col))))
          [] cols))

(defn- cols->props [cols]
  (reduce (fn [m col]
            (let [col-name (:name col)
                  col-type (:type col)
                  swag-type (get swag-types col-type)
                  prop {:type swag-type}]
              (assoc m col-name (cond-> prop
                                  (= type "timestamp") (assoc :format "date-time")
                                  (= swag-type "integer") (assoc :format "int64")))))
          {} cols))

(defn swag-def [config table]
  (let [def-name (s/capitalize (:name table))
        cols (:columns table)]
    {def-name {:type "object"
               :required (cols->required cols)
               :properties (cols->props cols)}}))

(defn root [config table]
  {:swag-paths [(root-paths config table)]
   :swag-defs [(swag-def config table)]})

(defn one-n [config table p-rsc]
  {:swag-paths [(one-n-paths config table p-rsc)]
   :swag-defs []})

(defn n-n-create [config table]
  {:swag-paths [(n-n-create-paths config table)]
   :swag-defs [(swag-def config table)]})

(defn schema
  ([swag-paths swag-defs info host secDefs]
  (let [paths (into (sorted-map) (apply merge swag-paths))
        defs (into (sorted-map) (apply merge swag-defs))
        tags (map s/lower-case (keys defs))]
    (println "fdsa" (keys paths))
    {:swagger "2.0"
     :info info
     :host host
     :basePath ""
     :tags tags
     :schemes ["http"]
     :paths paths
     :securityDefinitions secDefs
     :definitions defs}))
  ([swag-paths swag-defs]
   (schema swag-paths swag-defs {} "localhost:3000" {})))

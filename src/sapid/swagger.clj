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

;;; params & responses

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

;;; path details

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
   (let [def-name rsc ; (s/capitalize rsc)
         get-params (apply conj (query-params table) path-params)
         post-params (apply conj (body-params def-name) path-params)]
     {:get (method-details tag (str "List " def-name)
                           get-params (ref-responses def-name true))
      :post (method-details tag (str "Create " def-name) post-params)}))
  ([table rsc tag]
   (path-details table rsc tag [])))

(defn- id-path-details [table rsc tag path-params]
  (let [def-name rsc ; (s/capitalize rsc)
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
  (let [def-name (:name table) ; (s/capitalize (:name table))
        bd-prms (if post? (body-params def-name) nil)
        post-params (apply conj bd-prms path-params)
        post-smry (if post? (str "Add " rsc-b " to " rsc-a)
                      (str "Delete " rsc-b " from " rsc-a))]
    {:post (method-details rsc-a post-smry post-params)}))

(defn- n-n-link-details [table p-rsc c-rsc path-params]
  (let [def-name c-rsc ; (s/capitalize c-rsc)
        params (apply conj (query-params table) path-params)
        smry (str "List " c-rsc " per " p-rsc)]
    {:get (method-details p-rsc smry params (ref-responses def-name true))}))

;;; paths

(defn- id-path [rsc]
  (str (inf/singular rsc) "Id"))

(defn- root-paths [config table]
  (let [rsc (to-path-rsc (:name table) config)
        id-name (id-path rsc)
        id-param (path-param id-name)]
    {(str "/" rsc) (path-details table rsc rsc)
     (str "/" rsc "/{" id-name "}") (id-path-details table rsc rsc [id-param])}))

(defn- one-n-paths [config table p-rsc]
  (let [c-rsc (to-path-rsc (:name table) config)
        p-rsc (to-path-rsc p-rsc config)
        c-id-name (id-path c-rsc)
        p-id-name (id-path p-rsc)
        c-id-param (path-param c-id-name)
        p-id-param (path-param p-id-name)
        path (str "/" p-rsc "/{" p-id-name "}/" c-rsc)
        id-path (str "/" p-rsc "/{" p-id-name "}/" c-rsc "/{" c-id-name "}")]
    {path (path-details table c-rsc p-rsc [p-id-param])
     id-path (id-path-details table c-rsc p-rsc [c-id-param p-id-param])}))

(defn- n-n-create-paths [config table]
  (let [rsc-a (to-path-rsc (first (:belongs-to table)) config)
        rsc-b (to-path-rsc (second (:belongs-to table)) config)
        rsc-a-id (id-path rsc-a)
        rsc-b-id (id-path rsc-b)
        a-add-path (str "/" rsc-a "/{" rsc-a-id "}/" rsc-b "/{" rsc-b-id "}/add")
        b-add-path (str "/" rsc-b "/{" rsc-b-id "}/" rsc-a "/{" rsc-a-id "}/add")
        a-del-path (str "/" rsc-a "/{" rsc-a-id "}/" rsc-b "/{" rsc-b-id "}/delete")
        b-del-path (str "/" rsc-b "/{" rsc-b-id "}/" rsc-a "/{" rsc-a-id "}/delete")
        path-params [(path-param rsc-a-id) (path-param rsc-b-id)]]
    {a-add-path (n-n-create-details table rsc-a rsc-b path-params true)
     b-add-path (n-n-create-details table rsc-b rsc-a path-params true)
     a-del-path (n-n-create-details table rsc-a rsc-b path-params false)
     b-del-path (n-n-create-details table rsc-b rsc-a path-params false)}))

(defn- n-n-link-paths [config table p-rsc c-rsc]
  (let [p-id-name (id-path p-rsc)
        p-id-param (path-param p-id-name)]
    {(str "/" p-rsc "/{" p-id-name "}/" c-rsc) (n-n-link-details table p-rsc c-rsc
                                                                 [p-id-param])}))

;;; columns

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

;;; public

(defn swag-def [config table]
  (let [def-name (:name table) ; (s/capitalize (:name table))
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

(defn n-n-link [config table p-rsc c-rsc]
  {:swag-paths [(n-n-link-paths config table p-rsc c-rsc)]
   :swag-defs []})

(defn schema
  ([swag-paths swag-defs info host secDefs]
   (let [paths (into (sorted-map) (apply merge swag-paths))
         defs (into (sorted-map) (apply merge swag-defs))
         tags (map s/lower-case (keys defs))]
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

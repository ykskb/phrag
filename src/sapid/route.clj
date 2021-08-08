(ns sapid.route
  (:require [clojure.string :as s]
            [inflections.core :as inf]
            [sapid.handlers.bidi :as bd]))

(defn- to-path-rsc [rsc config]
  (if (:resource-path-plural config) (inf/plural rsc) (inf/singular rsc)))

(defn- to-table-name [rsc config]
  (if (:table-name-plural config) (inf/plural rsc) (inf/singular rsc)))

(defn- to-col-name [rsc]
  (str (inf/singular rsc) "_id"))

(defn- col-names [table]
  (set (map :name (:columns table))))

(defmulti root-routes (fn [config & _] (:router config)))
(defmulti one-n-link-routes (fn [config & _] (:router config)))
(defmulti n-n-create-routes (fn [config & _] (:router config)))
(defmulti n-n-link-routes (fn [config & _] (:router config)))

;;; Bidi

(defmethod root-routes :bidi [config table]
  (let [db (:db config)
        table-name (:name table)
        cols (col-names table)
        rsc-path (str "/" (to-path-rsc table-name config) "/")
        rsc-path-end (str "/" (to-path-rsc table-name config))]
    {:routes [{rsc-path-end {:get (bd/list-root db table-name cols)
                             :post (bd/create-root db table-name cols)}
               [rsc-path :id] {:get (bd/fetch-root db table-name cols)
                               :delete (bd/delete-root db table-name cols)
                               :put (bd/put-root db table-name cols)
                               :patch (bd/patch-root db table-name cols)}}]
     :handlers []}))

(defmethod one-n-link-routes :bidi [config table p-rsc]
  (let [db (:db config)
        table-name (:name table)
        p-col (to-col-name p-rsc)
        cols (col-names table)
        p-rsc-path (str "/" (to-path-rsc p-rsc config) "/")
        c-rsc-path (str "/" (to-path-rsc table-name config) "/")
        c-rsc-path-end (str "/" (to-path-rsc table-name config))]
    {:routes [{[p-rsc-path :p-id c-rsc-path-end]
               {:get (bd/list-one-n db table-name p-col cols)
                :post (bd/create-one-n db table-name p-col cols)}
               [p-rsc-path :p-id c-rsc-path :id]
               {:get (bd/fetch-one-n db table-name p-col cols)
                :delete (bd/delete-one-n db table-name p-col cols)
                :put (bd/put-one-n db table-name p-col cols)
                :patch (bd/patch-one-n db table-name p-col cols)}}]
     :handlers []}))

(defmethod n-n-create-routes :bidi [config table]
  (let [db (:db config)
        table-name (:name table)
        parts (s/split table-name #"_")
        rsc-a (first (:belongs-to table))
        rsc-b (second (:belongs-to table))
        col-a (to-col-name rsc-a)
        col-b (to-col-name rsc-b)
        cols (col-names table)
        rsc-a-path (str "/" (to-path-rsc rsc-a config) "/")
        rsc-b-path (str "/" (to-path-rsc rsc-b config) "/")]
    {:routes [{[rsc-a-path :id-a rsc-b-path :id-b "/add"]
               {:post (bd/create-n-n db table-name col-a col-b cols)}
               [rsc-b-path :id-a rsc-a-path :id-b "/add"]
               {:post (bd/create-n-n db table-name col-a col-b cols)}
               [rsc-a-path :id-a rsc-b-path :id-b "/delete"]
               {:post (bd/delete-n-n db table-name col-a col-b cols)}
               [rsc-b-path :id-a rsc-a-path :id-b "/delete"]
               {:post (bd/delete-n-n db table-name col-a col-b cols)}}]
     :handlers []}))

(defmethod n-n-link-routes :bidi [config table p-rsc c-rsc]
  (let [db (:db config)
        nn-table (:name table)
        table (to-table-name c-rsc config)
        nn-join-col (to-col-name c-rsc)
        nn-p-col (to-col-name p-rsc)
        p-rsc-path (str "/" (to-path-rsc p-rsc config) "/")
        c-rsc-path (str "/" (to-path-rsc c-rsc config))]
    {:routes [{[p-rsc-path :p-id c-rsc-path]
               {:get (bd/list-n-n db table nn-table nn-join-col nn-p-col
                                  (col-names table))}}]
     :handlers []}))

;;; Duct Ataraxy

(defn handler-key [project-ns action]
  (keyword "sapid.handlers.duct-ataraxy" action))

(defn route-key [project-ns resource action]
  (let [ns (str project-ns ".handler." resource)] (keyword ns action)))

(defn handler-map [handler-key route-key opts]
  (derive route-key handler-key)
  {[handler-key route-key] opts})

(defn route-map [path route-key param-names]
  (if (coll? param-names)
    {path (into [] (concat [route-key] param-names))}
    {path [route-key]}))

(defmethod root-routes :ataraxy [config table]
  (let [ns (:project-ns config)
        table-name (:name table)
        opts {:db (:db-ref config) :db-keys (:db-keys config)
              :table table-name :cols (col-names table)}
        rsc-path (str "/" (to-path-rsc table-name config) "/")
        rsc-path-end (str "/" (to-path-rsc table-name config))]
    (reduce (fn [m [action path param-names]]
              (let [route-key (route-key ns table-name action)
                    handler-key (handler-key ns action)]
                (-> m
                    (update :routes conj (route-map path route-key param-names))
                    (update :handlers conj (handler-map
                                            handler-key route-key opts)))))
            {:routes [] :handlers []}
            [["list-root" [:get rsc-path-end {'q :query-params}] ['q]]
             ["create-root" [:post rsc-path-end {'b :params}] ['b]]
             ["fetch-root" [:get rsc-path 'id {'q :query-params}] [^int 'id 'q]]
             ["delete-root" [:delete rsc-path 'id] [^int 'id]]
             ["put-root" [:put rsc-path 'id {'b :params}] [^int 'id 'b]]
             ["patch-root" [:patch rsc-path 'id {'b :params}] [^int 'id 'b]]])))

(defmethod one-n-link-routes :ataraxy [config table p-rsc]
  (let [ns (:project-ns config)
        c-rsc (:name table)
        p-rsc-path (str "/" (to-path-rsc p-rsc config) "/")
        c-rsc-path (str "/" (to-path-rsc c-rsc config) "/")
        c-rsc-path-end (str "/" (to-path-rsc c-rsc config))
        opts {:db (:db-ref config) :db-keys (:db-keys config)
              :table c-rsc :p-col (to-col-name p-rsc)
              :cols (col-names table)}
        rscs (str p-rsc "." c-rsc)]
    (reduce (fn [m [action path param-names]]
              (let [route-key (route-key ns rscs action)
                    handler-key (handler-key ns action)]
                (-> m
                    (update :routes conj (route-map path route-key param-names))
                    (update :handlers conj (handler-map
                                            handler-key route-key opts)))))
            {:routes [] :handlers []}
            [["list-one-n" [:get p-rsc-path 'id c-rsc-path-end
                            {'q :query-params}] [^int 'id 'q]]
             ["create-one-n" [:post p-rsc-path 'id c-rsc-path-end {'b :params}]
              [^int 'id 'b]]
             ["fetch-one-n" [:get p-rsc-path 'p-id c-rsc-path 'id
                             {'q :query-params}] [^int 'p-id ^int 'id 'q]]
             ["delete-one-n" [:delete p-rsc-path 'p-id c-rsc-path 'id]
              [^int 'p-id ^int 'id]]
             ["put-one-n" [:put p-rsc-path 'p-id c-rsc-path 'id {'b :params}]
              [^int 'p-id ^int 'id 'b]]
             ["patch-one-n" [:patch p-rsc-path 'p-id c-rsc-path 'id {'b :params}]
              [^int 'p-id ^int 'id 'b]]])))

(defmethod n-n-create-routes :ataraxy [config table]
  (let [ns (:project-ns config)
        table-name (:name table)
        parts (s/split table-name #"_")
        rsc-a (first (:belongs-to table))
        rsc-b (second (:belongs-to table))
        rsc-a-path (str "/" (to-path-rsc rsc-a config) "/")
        rsc-b-path (str "/" (to-path-rsc rsc-b config) "/")
        opts {:db (:db-ref config) :db-keys (:db-keys config)
              :table table-name :col-a (to-col-name rsc-a)
              :col-b (to-col-name rsc-b) :cols (col-names table)}]
    (reduce (fn [m [action rscs path param-names]]
              (let [route-key (route-key ns rscs action)
                    handler-key (handler-key ns action)]
                (-> m
                    (update :routes conj (route-map path route-key param-names))
                    (update :handlers conj (handler-map handler-key
                                                        route-key opts)))))
            {:routes [] :handlers []}
            [["create-n-n" (str rsc-a "." rsc-b)
              [:post rsc-a-path 'id-a rsc-b-path 'id-b "/add" {'b :params}]
              [^int 'id-a ^int 'id-b 'b]]
             ["create-n-n" (str rsc-b "." rsc-a)
              [:post rsc-b-path 'id-a rsc-a-path 'id-b "/add" {'b :params}]
              [^int 'id-a ^int 'id-b 'b]]
             ["delete-n-n" (str rsc-a "." rsc-b)
              [:post rsc-a-path 'id-a rsc-b-path 'id-b "/delete"]
              [^int 'id-a ^int 'id-b]]
             ["delete-n-n" (str rsc-b "." rsc-a)
              [:post rsc-b-path 'id-a rsc-a-path 'id-b "/delete"]
              [^int 'id-a ^int 'id-b]]])))

(defmethod n-n-link-routes :ataraxy [config table p-rsc c-rsc]
  (let [ns (:project-ns config)
        p-rsc-path (str "/" (to-path-rsc p-rsc config) "/")
        c-rsc-path (str "/" (to-path-rsc c-rsc config))
        opts {:db (:db-ref config) :db-keys (:db-keys config)
              :table (to-table-name c-rsc config) :nn-table (:name table)
              :nn-join-col (to-col-name c-rsc) :nn-p-col (to-col-name p-rsc)
              :cols (col-names table)}]
    (reduce (fn [m [action path param-names]]
              (let [rscs (str p-rsc "." c-rsc)
                    route-key (route-key ns rscs action)
                    handler-key (handler-key ns action)]
                (-> m
                    (update :routes conj (route-map path route-key param-names))
                    (update :handlers conj (handler-map
                                            handler-key route-key opts)))))
            {:routes [] :handlers []}
            [["list-n-n" [:get p-rsc-path 'id c-rsc-path {'q :query-params}]
              [^int 'id 'q]]])))



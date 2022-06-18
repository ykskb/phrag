(ns phrag.db.adapter
  (:require [clojure.string :as s]
            [phrag.db.sqlite :as sqlite]
            [phrag.db.postgres :as postgres]))

(defn- connection-type [db]
  (let [db-info (s/lower-case (-> (.getMetaData (:connection db))
                                  (.getDatabaseProductName)))]
    (cond
      (s/includes? db-info "sqlite") :sqlite
      (s/includes? db-info "postgres") :postgres
      :else nil)))

(defn- data-src-type [db]
  (let [data-src (:datasource db)
        db-info (s/lower-case (or (.getJdbcUrl data-src)
                                  (.getDataSourceClassName data-src)))]
    (cond
      (s/includes? db-info "sqlite") :sqlite
      (s/includes? db-info "postgres") :postgres
      :else nil)))

(defn- db-type [db]
  (cond
    (:connection db) (connection-type db)
    (:datasource db) (data-src-type db)
    :else nil))

(defn db->adapter [db]
  (let [type-key (db-type db)]
    (case type-key
      :sqlite (sqlite/->SqliteAdapter db)
      :postgres (postgres/->PostgresAdapter db))))


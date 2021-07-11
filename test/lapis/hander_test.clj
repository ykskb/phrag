(ns lapis.hander-test
  (:require [clojure.test :refer :all]
            [integrant.core :as ig]
            [lapis.core-test :refer [create-database]]))

(deftest handlers
  (testing "root resource routes"
    (let [db (create-database)
          cols [""]
          opt {:db db :db-keys [] :table "members" :cols cols}
          list-hdlr (:lapis.handler/list-root
                     (ig/init {:lapis.handler/list-root opt}))
          create-hdlr (:lapis.handler/create-root
                       (ig/init {:lapis.handler/create-root opt}))
          fetch-hdlr (:lapis.handler/fetch-root
                      (ig/init {:lapis.handler/fetch-root opt}))
          put-hdlr (:lapis.handler/put-root
                    (ig/init {:lapis.handler/put-root opt}))
          patch-hdlr (:lapis.handler/patch-root
                      (ig/init {:lapis.handler/patch-root opt}))
          delete-hdlr (:lapis.handler/delete-root
                       (ig/init {:lapis.handler/delete-root opt}))
          ]
      (let [params-a {:first_name "john"
                      :last_name "doe"
                      :email "john@test.com"}
            params-b {:first_name "taro"
                      :last_name "yamada"
                      :email "taro@test.com"}
            created-a (assoc params-a :id 1)
            created-b (assoc params-b :id 2)
            update-kv {:email "doe@test.com"}
            updated-a (merge created-a update-kv)]
        (is (= (list-hdlr {}) 
               [:ataraxy.response/ok []]))
        (is (= (create-hdlr {:ataraxy/result [nil params-a]}) 
               [:ataraxy.response/ok]))
        (is (= (create-hdlr {:ataraxy/result [nil params-b]}) 
               [:ataraxy.response/ok]))
        (is (= (list-hdlr {}) 
               [:ataraxy.response/ok [created-a created-b]]))
        (is (= (fetch-hdlr {:ataraxy/result [nil 1]})
               [:ataraxy.response/ok created-a]))
        (is (= (patch-hdlr {:ataraxy/result [nil 1 update-kv]})
               [:ataraxy.response/ok]))
        (is (= (list-hdlr {})
               [:ataraxy.response/ok [updated-a created-b]]))
        (is (= (delete-hdlr {:ataraxy/result [nil 1]})
               [:ataraxy.response/ok]))
        (is (= (list-hdlr {})
               [:ataraxy.response/ok [created-b]]))
        )
      )))

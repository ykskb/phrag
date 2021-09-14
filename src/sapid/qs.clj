(ns sapid.qs
  (:require [clojure.string :as s]
            [ring.middleware.params :as prm]))

(def ^:private operator-map
  {"eq"  :=
   "lt"  :<
   "le"  :<=
   "lte" :<=
   "gt"  :>
   "ge"  :>=
   "gte" :>=
   "ne"  :!=})

(defn- parse-filter-val [k v]
  (let [parts (s/split (str v) #":" 2)
        c (count parts)
        op (get operator-map (first parts))]
    (if (or (nil? op) (< c 2))
      [:= (keyword k) v]
      [op (keyword k) (second parts)])))

(defn- parse-order-by [m v]
  (let [parts (s/split v #":" 2)
        direc (second parts)]
    (-> m
        (assoc :order-col (keyword (first parts)))
        (assoc :direc (if (nil? direc) :desc (keyword direc))))))

(defn query->filters [query cols]
  (reduce (fn [m [k v]]
            (cond
              (contains? cols k) (update m :filters conj (parse-filter-val k v))
              (= k "order-by") (parse-order-by m v)
              (= k "limit") (assoc m :limit v)
              (= k "offset") (assoc m :offset v)
              :else m))
          {:filters [] :limit 100 :offset 0}
          query))

(defn ring-query [req]
  (:query-params (prm/params-request req)))


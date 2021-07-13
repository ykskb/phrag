(ns user)

(defn dev-duct
  "Load and switch to the 'dev' namespace."
  []
  (require 'dev-duct)
  (in-ns 'dev-duct)
  :dev-duct-loaded)

(defn dev
  []
  (require 'dev)
  (in-ns 'dev)
  :dev-loaded)

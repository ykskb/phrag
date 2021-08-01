(ns user)

;;; duct 

(defn dev-duct
  "Load and switch to the 'dev' namespace."
  []
  (require 'dev-duct)
  (in-ns 'dev-duct)
  :dev-duct-loaded)

;;; bidi

 (defn dev-bidi
   []
   (require 'dev-bidi)
   (in-ns 'dev-bidi)
   :dev-loaded)

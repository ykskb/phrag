(ns user)

;;; retit

(defn dev-reitit
  "Load and switch to the 'dev' namespace."
  []
  (require 'dev-reitit)
  (in-ns 'dev-reitit)
  :dev-reitit-loaded)

;;; duct

(defn dev-duct
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

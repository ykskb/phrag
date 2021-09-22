(ns user)

;;; retit

(defn dev-reitit
  "Load and switch to the 'dev' namespace."
  []
  (require 'dev-reitit)
  (in-ns 'dev-reitit)
  :dev-reitit-loaded)

;;; bidi

 (defn dev-bidi
   []
   (require 'dev-bidi)
   (in-ns 'dev-bidi)
   :dev-loaded)

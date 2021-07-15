(ns user)

;;; duct 

(defn dev
  "Load and switch to the 'dev' namespace."
  []
  (require 'dev-duct)
  (in-ns 'dev-duct)
  :dev-duct-loaded)

;;; bidi

; (defn dev
;   []
;   (require 'dev)
;   (in-ns 'dev)
;   :dev-loaded)

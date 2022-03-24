(ns ^:no-doc phrag.logging
  (:require [clojure.tools.logging]))

(defmacro log [level & args]
  `(~(condp = level
       :debug 'clojure.tools.logging/debug
       :info 'clojure.tools.logging/info
       :warn 'clojure.tools.logging/warn
       :error 'clojure.tools.logging/error)
    ~@args))

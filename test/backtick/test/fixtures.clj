(ns backtick.test.fixtures
  (:require
   [clojure.test :refer :all]))

(defn clean-data []
  )

(defn wrap-fixture-data [f]
  (clean-data)
  (f))

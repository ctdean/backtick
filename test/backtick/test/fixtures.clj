(ns backtick.test.fixtures
  (:require
   [backtick.db :as db]
   [clojure.java.jdbc :as jdbc]))

(defn wrap-clean-data [f]
  (jdbc/delete! db/spec :backtick_cron [])
  (jdbc/delete! db/spec :backtick_queue [])
  (f))

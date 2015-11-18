(ns backtick.test.conf-test
  (:require
   [clojure.test :refer :all]
   [backtick.conf :refer :all]))

(deftest master-cf-test
  (is (:cron-ms master-cf))
  (is (:poll-ms master-cf))
  (is (:cron-ms master-cf))
  (is (:timeout-ms master-cf))
  (is (:revive-check-ms master-cf))
  (is (:remove-check-ms master-cf))
  (is (:max-completed-ms master-cf))
  (is (:cron-window-ms master-cf))
  (is (:max-tries master-cf)))

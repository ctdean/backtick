(ns backtick.test.cleaner-test
  (:require
   [clj-time.core :as t]
   [clj-time.coerce :as tc]
   [clojure.java.jdbc :as jdbc]
   [clojure.test :refer :all]
   [backtick.cleaner :as cleaner]
   [backtick.db :as db]))

(def now   (tc/to-sql-time (t/now)))
(def prev  (tc/to-sql-time (t/minus (t/now) (t/hours 1))))
(def prev2 (tc/to-sql-time (t/minus (t/now) (t/days 8))))

(def backtick-queue-cols
  [:id :name :priority :state :tries :data
   :started_at :finished_at :created_at :updated_at])

(def backtick-queue-rows
  [[300 "j0" prev2 "done" 1 "[]\n" prev2 prev2 prev2 prev2]
   [301 "j1" prev "running" 1 "[]\n" prev nil prev prev]
   [302 "j2" prev "queued" 1 "[]\n" prev nil prev prev]
   [303 "j3" prev "running" Integer/MAX_VALUE "[]\n" prev nil prev prev]
   [304 "j4" prev "running" 2 "[]\n" prev nil prev prev]
   [305 "j5" prev2 "canceled" 0 "[]\n" prev2 prev2 prev2 prev2]])

(defn drain-queue []
  (jdbc/delete! db/datasource :backtick_queue []))

(defn db-fixtures [f]
  (drain-queue)
  (jdbc/insert-multi! db/datasource
                      :backtick_queue
                      backtick-queue-cols
                      backtick-queue-rows)
  (f)
  (drain-queue))

(use-fixtures :each db-fixtures)

(deftest revive-one-job-test
  (let [[before] (jdbc/query db/datasource "SELECT * FROM backtick_queue WHERE id = 304")
        now (t/now)]
    (cleaner/revive-one-job 304)
    (let [[after] (jdbc/query db/datasource "SELECT * FROM backtick_queue WHERE id = 304")]
      (is (= 304 (:id before) (:id after)))
      (is (= "running" (:state before)))
      (is (= "queued" (:state after)))
      (is (= (:tries before) (:tries after)))
      (is (.before (:priority before) (:priority after)))))
  ;; Missing job should not throw an error
  (cleaner/revive-one-job -1))

(deftest revive-test
  (cleaner/revive)
  (let [[j1 j2 j3] (jdbc/query
                    db/datasource
                    "SELECT * FROM backtick_queue WHERE id in (301, 302, 303) ORDER BY id ASC")]
    ;; j1
    (is (= (:state j1) "queued"))
    (is (.after (:priority j1) now))
    (is (.after (:updated_at j1) now))
    (is (not (:finished_at j1)))
    ;; j2
    (is (= (:state j2) "queued"))
    (is (= (:priority j2) prev))
    (is (= (:updated_at j2) prev))
    (is (not (:finished_at j2)))
    ;; j3
    (is (= (:state j3) "exceeded"))
    (is (:finished_at j3))))

(deftest remove-old-test
  (cleaner/remove-old)
  (is (= (range 301 305)
         (->> (jdbc/query db/datasource "SELECT * FROM backtick_queue")
              (map :id)
              sort))))

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

(def backtick-queue-rows
  [[:id :name :priority :state :tries :data :started_at :finished_at :created_at :updated_at]
   [300 "j0" prev2 "done" 1 "[]\n" prev2 prev2 prev2 prev2]
   [301 "j1" prev "running" 1 "[]\n" prev nil prev prev]
   [302 "j2" prev "queued" 1 "[]\n" prev nil prev prev]
   [303 "j3" prev "running" Integer/MAX_VALUE "[]\n" prev nil prev prev]
   [304 "j4" prev "running" 2 "[]\n" prev nil prev prev]
   ])

(defn drain-queue []
  (jdbc/delete! db/spec :backtick_queue []))

(defn db-fixtures [f]
  (drain-queue)
  (apply jdbc/insert! db/spec :backtick_queue backtick-queue-rows)
  (f)
  (drain-queue))

(use-fixtures :each db-fixtures)

(deftest revive-one-job-test
  ;; Just in case revive already ran
  (jdbc/execute! db/spec ["UPDATE backtick_queue SET state = 'running' WHERE id = 304"])
  (let [[before] (jdbc/query db/spec "SELECT * FROM backtick_queue WHERE id = 304")]
    (cleaner/revive-one-job 304)
    (let [[after] (jdbc/query db/spec "SELECT * FROM backtick_queue WHERE id = 304")]
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
                    db/spec
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
         (->> (jdbc/query db/spec "SELECT * FROM backtick_queue")
              (map :id)
              sort))))

(ns backtick.test.core-test
  (:require
   [backtick.core :refer :all]
   [backtick.db :as db]
   [backtick.engine :as engine]
   [backtick.registry :refer [registered-workers register]]
   [backtick.test.fixtures :refer [wrap-clean-data]]
   [clj-time.core :as t]
   [clj-time.coerce :as tc]
   [clojure.edn :as edn]
   [clojure.java.jdbc :as jdbc]
   [clojure.test :refer :all]))

(use-fixtures :each wrap-clean-data)

(def my-atom (atom nil))

(define-worker my-test-worker [a b]
  (reset! my-atom (+ a b)))

(define-recurring my-test-recurring (* 1234 100 60) []
  (reset! my-atom 'recurring))

(defn schedule-test-job-0 []
  (+ 1 2))

(defn schedule-test-job-1 [x]
  x)

(defn- db-query-bt-queue []
  (jdbc/query db/spec "SELECT * FROM backtick_queue ORDER BY id ASC"))

(deftest schedule-test
  (register "schedule-test-job-0" schedule-test-job-0)
  (schedule schedule-test-job-0)
  (register "schedule-test-job-1" schedule-test-job-1)
  (schedule schedule-test-job-1 88)
  (schedule my-test-worker 2 3)
  (let [jobs (db-query-bt-queue)]
    (is (= [nil [88] [2 3]] (->> jobs (map :data) (map edn/read-string))))))

(deftest schedule-at-test
  (let [now (t/now)
        later (t/plus (t/now) (t/hours 1))
        laterer (t/plus (t/now) (t/days 1))]
    (register "at-job-test-0" schedule-test-job-0)
    (schedule-at now schedule-test-job-0)
    (register "at-job-test-1" schedule-test-job-1)
    (schedule-at later schedule-test-job-1 88)
    (schedule-at laterer my-test-worker 2 3)
    (let [jobs (db-query-bt-queue)]
      (is (= (map tc/to-sql-time [now later laterer]) (map :priority jobs)))
      (is (= [nil [88] [2 3]] (->> jobs (map :data) (map edn/read-string)))))))

(deftest schedule-recurring-test
  (let [nm (str "test-worker-" (rand-int 100))
        missing (str "missing-worker-" (rand-int 100))
        tag (rand-int 100)]
    (testing "new job"
      (register nm (fn [] tag))
      (schedule-recurring 98700 nm)
      (let [cf (get (registered-workers) nm)
            rec1 (get (engine/recurring-map) nm)]
        (is (= tag (cf)))
        (is (nil? (get (registered-workers) missing)))
        (is (= 98700 (:interval rec1)))
        (is (nil? (:cronspec rec1)))))
    (testing "new interval"
      (register nm (fn [] (inc tag)))
      (schedule-recurring 98701 nm)
      (let [cf (get (registered-workers) nm)
            rec2 (get (engine/recurring-map) nm)]
        (is (= (inc tag) (cf)))
        (is (= 98701 (:interval rec2)))
        (is (nil? (:cronspec rec2)))
        (testing "same interval"
          (register nm (fn [] (+ tag 2)))
          (schedule-recurring 98701 nm)
          (let [cf (get (registered-workers) nm)
                rec3 (get (engine/recurring-map) nm)]
            (is (= (+ tag 2) (cf)))
            (is (= rec2 rec3))))))))

(deftest schedule-cron-test
  (let [nm (str "test-worker-" (rand-int 100))
        missing (str "missing-worker-" (rand-int 100))
        tag (rand-int 100)]
    (testing "new job"
      (register nm (fn [] tag))
      (schedule-cron "0 0 0 * * *" nm)
      (let [cf (get (registered-workers) nm)
            rec1 (get (engine/recurring-map) nm)]
        (is (= tag (cf)))
        (is (nil? (get (registered-workers) missing)))
        (is (nil? (:interval rec1)))
        (is (= "0 0 0 * * *" (:cronspec rec1)))))
    (testing "new cronspec"
      (register nm (fn [] (inc tag)))
      (schedule-cron "0 1 2 * * *" nm)
      (let [cf (get (registered-workers) nm)
            rec2 (get (engine/recurring-map) nm)]
        (is (= (inc tag) (cf)))
        (is (nil? (:interval rec2)))
        (is (= "0 1 2 * * *" (:cronspec rec2)))
        (testing "same cronspec"
          (register nm (fn [] (+ tag 2)))
          (schedule-cron "0 1 2 * * *" nm)
          (let [cf (get (registered-workers) nm)
                rec3 (get (engine/recurring-map) nm)]
            (is (= (+ tag 2) (cf)))
            (is (= rec2 rec3))))))))

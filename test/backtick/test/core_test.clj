(ns backtick.test.core-test
  (:require
   [clojure.test :refer :all]
   [backtick.core :refer :all]))

(deftest register-test
  (let [nm (str "test-worker-" (rand-int 100))
        missing (str "missing-worker-" (rand-int 100))
        tag (rand-int 100)]
    (register nm (fn [] tag))
    (let [cf (get (registered-workers) nm)]
      (is (= tag (cf)))
      (is (nil? (get (registered-workers) missing))))))

(deftest register-cron-test
  (let [nm (str "test-worker-" (rand-int 100))
        missing (str "missing-worker-" (rand-int 100))
        tag (rand-int 100)]
    ;; new job
    (register-cron nm 98700 (fn [] tag))
    (let [cf (get (registered-workers) nm)
          cron1 (get (registered-crons) nm)]
      (is (= tag (cf)))
      (is (nil? (get (registered-workers) missing)))
      (is (= 98700 (:interval cron1))))
    ;; new interval
    (register-cron nm 98701 (fn [] (inc tag)))
    (let [cf (get (registered-workers) nm)
          cron2 (get (registered-crons) nm)]
      (is (= (inc tag) (cf)))
      (is (= 98701 (:interval cron2)))
      ;; same interval
      (register-cron nm 98701 (fn [] (+ tag 2)))
      (let [cf (get (registered-workers) nm)
            cron3 (get (registered-crons) nm)]
        (is (= (+ tag 2) (cf)))
        (is (= cron2 cron3))))))

(def my-atom (atom nil))

(define-worker my-test-worker [a b]
  (reset! my-atom (+ a b)))

(define-cron my-test-cron (* 1234 100 60) []
  (reset! my-atom 'cron))

(defn schedule-test-job [x]
  x)

(deftest schedule-test
  (register "schedule-test-job" schedule-test-job)
  (schedule schedule-test-job 88)
  (schedule my-test-worker 2 3))

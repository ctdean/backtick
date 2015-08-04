(ns backtick.core-test
  (:require
   [clojure.test :refer :all]
   [backtick.core :refer :all]))

(deftest master-cf-test
  (is (= "bt_queue" (:coll master-cf)))
  (is (:cron master-cf))
  (is (:poll-ms master-cf))
  (is (:cron-ms master-cf))
  (is (:timeout-ms master-cf))
  (is (:revive-check-ms master-cf))
  (is (:remove-check-ms master-cf))
  (is (:max-completed-ms master-cf))
  (is (:cron-window-ms master-cf))
  (is (:max-tries master-cf)))

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
    (register-cron nm 100 (fn [] tag))
    (let [cf (get (registered-workers) nm)
          cron1 (get (registered-crons) nm)]
      (is (= tag (cf nil)))
      (is (nil? (get (registered-workers) missing)))
      (is (= 100 (:interval cron1))))
    ;; new interval
    (register-cron nm 101 (fn [] (inc tag)))
    (let [cf (get (registered-workers) nm)
          cron2 (get (registered-crons) nm)]
      (is (= (inc tag) (cf nil)))
      (is (= 101 (:interval cron2)))
      ;; same interval
      (register-cron nm 101 (fn [] (+ tag 2)))
      (let [cf (get (registered-workers) nm)
            cron3 (get (registered-crons) nm)]
        (is (= (+ tag 2) (cf nil)))
        (is (= cron2 cron3))))))

(def my-atom (atom nil))

(define-worker my-test-worker [a b]
  (reset! my-atom (+ a b)))

(define-cron my-test-cron (* 1234 100 60) []
  (reset! my-atom 'cron))

(deftest define-helpers-test
  )

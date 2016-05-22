(ns backtick.test.registry-test
  (:require
   [backtick.registry :refer :all]
   [backtick.test.fixtures :refer [wrap-clean-data]]
   [clojure.test :refer :all]))

(use-fixtures :each wrap-clean-data)

(deftest register-test
  (let [nm (str "test-worker-" (rand-int 100))
        missing (str "missing-worker-" (rand-int 100))
        tag (rand-int 100)
        f  (fn [] tag)]
    (register nm f)
    (let [cf (get (registered-workers) nm)]
      (is (= tag (cf)))
      (is (nil? (get (registered-workers) missing))))
    (unregister nm)
    (is (nil? (get (registered-workers) nm)))))

(deftest resolve-worker-test
  (let [nm (str "test-worker-" (rand-int 100))
        missing (str "missing-worker-" (rand-int 100))
        tag (rand-int 100)
        f  (fn [] tag)]
    (register nm f)
    (is (= nm (resolve-worker->name nm)))
    (is (= nm (resolve-worker->name f)))
    (is (= f (resolve-worker->fn nm)))
    (is (= f (resolve-worker->fn f)))
    (is (not (resolve-worker->name missing)))
    (is (not (resolve-worker->fn missing)))
    (unregister nm)
    (is (not (resolve-worker->name nm)))
    (is (not (resolve-worker->name f)))
    (is (not (resolve-worker->fn nm)))
    (is (not (resolve-worker->fn f)))))

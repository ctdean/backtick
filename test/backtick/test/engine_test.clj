(ns backtick.test.engine-test
  (:require
   [backtick.db :as db]
   [backtick.engine :as engine]
   [backtick.registry :as registery]
   [backtick.test.fixtures :refer [wrap-clean-data]]
   [clj-time.coerce :as tc]
   [clj-time.core :as t]
   [clojure.core.async :refer [<!! chan thread]]
   [clojure.test :refer :all]))

(use-fixtures :each wrap-clean-data)

(deftest recurring-add-test
  (let [upsert-spy (atom nil)
        delete-spy (atom nil)]
    (with-redefs [backtick.db/recurring-upsert-interval
                  #(reset! upsert-spy (:interval %))
                  backtick.db/recurring-delete!
                  #(reset! delete-spy (:name %))
                  backtick.conf/master-cf {:recurring-resolution-ms 49}]
      (testing "invalid interval"
        (is (thrown? AssertionError (engine/recurring-add "foo" "0")))
        (is (nil? @upsert-spy)))
      (testing "zero interval - job disabled"
        (engine/recurring-add "foo" 0)
        (is (nil? @upsert-spy))
        (is (= @delete-spy "foo")))
      (testing "interval below recurring resolution"
        (engine/recurring-add "foo" 1)
        (is (= @upsert-spy 49))
        (reset! upsert-spy nil))
      (testing "interval ok"
        (engine/recurring-add "foo" 1234)
        (is (= @upsert-spy 1234))))))

(deftest add-test
  (let [spy (atom {})
        now (t/now)
        run-at (t/plus now (t/hours 1))]
    (with-redefs [backtick.db/queue-insert<! #(reset! spy %)]
      (engine/add run-at "foo" {:bar "baz" :quux 123 :flim :flam}))
    (is (= (dissoc @spy :priority)
           {:name "foo"
            :state "queued"
            :tries 0
            :data "{:bar \"baz\", :quux 123, :flim :flam}\n"}))
    (is (= (@spy :priority) (tc/to-sql-time run-at))))
  (let [inserted (engine/add (t/now) "foo" [1 2 3])]
    (is (= 0 (:tries inserted)))
    (is (nil? (:started_at inserted)))
    (is (> (:id inserted) 0))
    (is (= "queued" (:state inserted)))
    (is (= "[1 2 3]\n" (:data inserted)))))

(defprotocol FakeThreadPool
  (submit [this job]))

(deftest submit-worker-test
  (let [pool (reify FakeThreadPool
               (submit [_ job] (job)))
        ch (chan 1)
        msg {:id 12345 :name "foobar" :data [:x :y]}
        foobar-called (atom [])
        runner (fn [& args] (reset! foobar-called [engine/*job-id* args]))
        resolver (fn [_] runner)]
    (thread (with-redefs [registery/resolve-worker->fn resolver]
              (engine/submit-worker pool ch msg)))
    (is (= (<!! ch) :done))
    (is (first @foobar-called))
    (is (= (second @foobar-called) [:x :y]))))

(deftest cancel-all-test
  (dotimes [n 3] (engine/add (t/now) "foo" [n]))
  (let [job (first (db/queue-pop))]
    (is (= "foo" (:name job)))
    (is (= "[0]\n" (:data job))))
  (engine/cancel-all)
  (is (empty? (db/queue-pop))))

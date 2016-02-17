(ns backtick.test.engine-test
  (:require
   [clojure.core.async :refer [<!! chan thread]]
   [clojure.test :refer :all]
   [backtick.engine :as engine]))

(deftest add-test
  (let [spy (atom [])]
    (with-redefs [backtick.db/queue-insert<! #(reset! spy %)]
      (engine/add "foo" {:bar "baz" :quux 123 :flim :flam}))
    (is (contains? @spy :priority))
    (is (= (dissoc @spy :priority)
           {:name "foo"
            :state "queued"
            :data "{:bar \"baz\", :quux 123, :flim :flam}\n"})))
  (let [inserted (engine/add "foo" [1 2 3])]
    (is (= 0 (:tries inserted)))
    (is (:started_at inserted))
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
        workers (atom {"foobar" (fn [& args] (reset! foobar-called [engine/*job-id* args]))})]
    (thread (with-redefs [engine/workers workers]
      (engine/submit-worker pool ch msg)))
    (is (= (<!! ch) :done))
    (is (first @foobar-called))
    (is (= (second @foobar-called) [:x :y]))))

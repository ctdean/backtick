(ns backtick.test.engine-test
  (:require
   [clojure.core.async :refer [<!! chan thread]]
   [clojure.test :refer :all]
   [backtick.engine :as engine]))

(deftest add-test
  (let [spy (atom [])]
    (with-redefs [backtick.db/queue-insert! #(reset! spy %)]
      (engine/add "foo" {:bar "baz" :quux 123 :flim :flam}))
    (is (contains? @spy :priority))
    (is (= (dissoc @spy :priority)
           {:name "foo"
            :state "queued"
            :data "{:bar \"baz\", :quux 123, :flim :flam}\n"}))))

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

(ns backtick.test.core-test
  (:require
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

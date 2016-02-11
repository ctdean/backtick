(ns backtick.test.cleaner-test
  (:require
   [clj-time.core :as t]
   [clj-time.coerce :as tc]
   [clojure.java.jdbc :as jdbc]
   [clojure.test :refer :all]
   [backtick.cleaner :as cleaner]
   [backtick.db :as db]))

(def now        (tc/to-sql-time (t/now)))
(def previously (tc/to-sql-time (t/minus (t/now) (t/hours 1))))

(def backtick-queue-rows
  [[:id :name :priority :state :tries :data :started_at :created_at :updated_at]
   [301 "foo" previously "running" 1 "[]\n" previously previously previously]
   [302 "bar" previously "queued" 1 "[]\n" previously previously previously]
   ])

(defn drain-queue []
  (jdbc/delete! db/spec :backtick_queue []))

(defn db-fixtures [f]
  (drain-queue)
  (apply jdbc/insert! db/spec :backtick_queue backtick-queue-rows)
  (f)
  (drain-queue))

(use-fixtures :once db-fixtures)

(deftest revive-test
  (cleaner/revive)
  (let [[foo bar] (jdbc/query db/spec "SELECT * FROM backtick_queue ORDER BY id ASC")]
    ;; foo
    (is (= (:state foo) "queued"))
    (is (.after (:priority foo) now))
    (is (.after (:updated_at foo) now))
    ;; bar
    (is (= (:state bar) "queued"))
    (is (= (:priority bar) previously))
    (is (= (:updated_at bar) previously))
    ))

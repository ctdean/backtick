(ns backtick.cleaner
  "@ctdean"
  (:require
   [backtick.conf :refer [master-cf]]
   [backtick.db :as db]
   [clj-time.core :as t]
   [clj-time.coerce :refer [to-sql-time]]
   [clojure.tools.logging :as log]
   [iter.core :refer [iter iter!]]))

;;;
;;; Revive jobs that never finished.
;;;

(defn- exceeded? [tries]
  (>= tries (:max-tries master-cf)))

(defn- revive-range-ms [tries]
  (let [base (int (* (:retry-ms master-cf)
                     (Math/pow 2 (dec (or tries 1)))))]
    [base (* 2 base)]))

(defn- time-unit [x]
  (cond
   (< x 1000) (format "%3.1f %5s" x "ms")
   (< x (* 60 1000)) (format "%3.1f %5s" (/ x 1000.0) "secs")
   (< x (* 60 60 1000)) (format "%3.1f %5s" (/ x 60 1000.0) "mins")
   :else (format "%3.1f %5s" (/ x 60 60 1000.0) "hours")))

(defn- dump-revive-range
  "Print the possible revive times.  Useful for debugging."
  []
  (iter! (foreach tries (range 1 (:max-tries master-cf)))
         (let [[low hi] (revive-range-ms tries)]
           (printf "%2d %12s %12s\n" tries (time-unit low) (time-unit hi)))))

(defn- revive-at
  "The time at which to revive the failed job.  Notice that we pick a
   random start time between a low and high range."
  [tries]
  (let [[lo hi] (revive-range-ms tries)
        p (+ lo (rand-int (- hi lo)))]
    (to-sql-time (t/plus (t/now) (t/millis p)))))

(defn revive-one-job
  "Requeue a job to be run later"
  ([id]
   (if-let [payload (first (db/queue-running-job {:id id}))]
     (revive-one-job id (:tries payload))
     (log/errorf "No id for revive-one-job: %s" id)))
  ([id tries]
   (if (exceeded? tries)
     (db/queue-abort-job! {:id id})
     (db/queue-requeue-job! {:id id
                             :priority (revive-at tries)}))))

(defn revive
  "Revive jobs that never finished.  Will be run from a backtick job."
  []
  (let [time (to-sql-time (t/minus (t/now)
                                   (t/millis (* 2 (:timeout-ms master-cf)))))
        killed (db/queue-killed-jobs {:killtime time})]
    (iter! (foreach payload killed)
           (revive-one-job (:id payload) (:tries payload)))))

;;;
;;; Remove old jobs
;;;

(defn remove-old
  "Remove old successful and canceled jobs from the database."
  []
  (let [time (to-sql-time (t/minus (t/now)
                                   (t/millis (:max-completed-ms master-cf))))]
    (db/queue-delete-old-jobs! {:finished time})))

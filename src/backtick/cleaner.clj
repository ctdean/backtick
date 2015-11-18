(ns backtick.cleaner
  "@ctdean"
  (:require
   [backtick.conf :refer [master-cf]]
   [backtick.db :as db]
   [clj-time.core :as time]
   [clj-time.coerce :refer [to-sql-time]]
   [iter.core :refer [iter iter*]]))

;;;
;;; Revive jobs that never finished.
;;;

(defn- exceeded? [payload]
  (>= (:tries payload)
      (:max-tries master-cf)))

(defn- revive-priority [payload]
  (to-sql-time
   (time/plus (time/now)
              (time/millis
               (int (* (:retry-ms master-cf)
                       (Math/pow 2 (- (:tries payload 1) 2))))))))

(defn revive
  "Revive jobs that never finished.  Will be run from a backtick job."
  []
  (let [t (to-sql-time (time/minus (time/now)
                                   (time/millis (* 2 (:timeout-ms master-cf)))))
        killed (db/queue-killed-jobs {:killtime t})]
    (iter* (foreach payload killed)
           (if (exceeded? payload)
               (db/queue-abort-job! (select-keys payload [:id]))
               (db/queue-requeue-job! (-> (select-keys payload [:id])
                                          (assoc :priority (revive-priority payload))))))))

;;;
;;; Remove old jobs
;;;

(defn remove-old
  "Remove old successful jobs from the database."
  []
  (let [t (to-sql-time (time/plus (time/now)
                                  (time/millis (:max-completed-ms master-cf))))]
    (db/queue-delete-old-jobs! {:finished t})))

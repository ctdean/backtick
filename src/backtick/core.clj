(ns backtick.core
  "@ctdean @jimbru"
  (:require
   [backtick.conf :refer [master-cf]]
   [backtick.cleaner :as cleaner]
   [backtick.engine :as engine]
   [backtick.registry :refer [resolve-worker->name register]]
   [clojure.tools.logging :as log])
  (:gen-class))

(defn schedule-at
  "Schedule a job on the Backtick queue to be run at the appointed time. Worker can be
   either the worker's registered name or a reference to the worker function itself."
  [time worker & args]
  ;; Implementation detail: A priority of nil means now.
  (when-let [name (resolve-worker->name worker)]
    (engine/add time name args)))

(defn schedule
  "Schedule a job on the Backtick queue to be run as soon as possible. Worker can be
   either the worker's registered name or a reference to the worker function itself."
  [worker & args]
  (apply schedule-at nil worker args))

(defn schedule-recurring
  "Schedule a job to be run on the Backtick queue on a recurring basis, every
   msec milliseconds, starting for the first time msec milliseconds from now.
   Worker can be either the worker's registered name or a reference to the
   worker function itself. If msec is set to zero the job will be considered
   disabled and will not be run."
  [msec worker & args]
  (assert (empty? args) "Recurring jobs may not take arguments.")
  (when-let [name (resolve-worker->name worker)]
    (engine/recurring-add name msec)))

(defn schedule-cron
  "Schedule a job to be run on the Backtick queue recurring according
   to a Cron-style schedule specification ('cronspec') and timezone.
   Worker can be either the worker's registered name or a reference to
   the worker function itself."
  [cronspec timezone worker & args]
  (assert (empty? args) "Cron jobs may not take arguments.")
  (when-let [name (resolve-worker->name worker)]
    (engine/recurring-add-cronspec name cronspec timezone)))

;;;
;;; Helpers
;;;

(defmacro define-worker [& function-definition]
  (let [symbol-name (first function-definition)
        str-nm (str *ns* "/" symbol-name)]
    `(do
       (defn ~@function-definition)
       (register ~str-nm ~symbol-name))))

(defn ^:private register-worker [symbol-name body]
  `((defn ~symbol-name [] ~@body)
    (register ~(str *ns* "/" symbol-name) ~symbol-name)))

(defmacro define-recurring [name interval-ms args & body]
  (assert (= args []) "Recurring workers may not take arguments.")
  `(do
     ~@(register-worker name body)
     (schedule-recurring ~interval-ms ~name)))

(defmacro define-cron
  "Cronspec-timezone should be either a cronspec string,
   or a vector of a cronspec string and a timezone string."
  [name cronspec-timezone args & body]
  (assert (= args []) "Cron workers may not take arguments.")
  (let [[cs tz] (if (string? cronspec-timezone)
                  [cronspec-timezone]
                  cronspec-timezone)]
    `(do
       ~@(register-worker name body)
       (schedule-cron ~cs ~tz ~name))))

;;;
;;; Cleaners
;;;

(define-recurring revive-killed-jobs (:revive-check-ms master-cf) []
  (log/debug "Running revive-killed-jobs")
  (cleaner/revive))

(define-recurring remove-old-jobs (:remove-check-ms master-cf) []
  (log/debug "Running remove-old-jobs")
  (cleaner/remove-old))

;;;
;;; Run the queue on this JVM
;;;

(defn start
  ([]
   (start (:pool-size master-cf)))
  ([pool-size]
   (engine/run pool-size)))

(defn -main [& args]
  (log/info "Starting bactick")
  (start))

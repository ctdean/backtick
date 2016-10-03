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
   to a Cron-style schedule specification ('cronspec'). Worker can be
   either the worker's registered name or a reference to the worker
   function itself."
  [cronspec worker & args]
  (assert (empty? args) "Cron jobs may not take arguments.")
  (when-let [name (resolve-worker->name worker)]
    (engine/recurring-add-cronspec name cronspec)))

;;;
;;; Helpers
;;;

(defmacro define-worker [& function-definition]
  (let [symbol-name (first function-definition)
        str-nm (str *ns* "/" symbol-name)]
    `(do
       (defn ~@function-definition)
       (register ~str-nm ~symbol-name))))

(defn ^:private define-recurring* [schedulef name intv-or-cs args body]
  (assert (= args []) "Recurring functions may not take arguments.")
  (let [symbol-name name  ; avoid shadowing built-in
        str-nm (str *ns* "/" symbol-name)]
    `(do
       (defn ~symbol-name ~args ~@body)
       (register ~str-nm ~symbol-name)
       (~schedulef ~intv-or-cs ~symbol-name))))

(defmacro define-recurring [name interval-ms args & body]
  (define-recurring* schedule-recurring name interval-ms args body))

(defmacro define-cron [name cronspec args & body]
  (define-recurring* schedule-cron name cronspec args body))

;;;
;;; Cleaners
;;;

(define-recurring revive-killed-jobs (:revive-check-ms master-cf) []
  (log/info "Running revive-killed-jobs")
  (cleaner/revive))

(define-recurring remove-old-jobs (:remove-check-ms master-cf) []
  (log/info "Running remove-old-jobs")
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

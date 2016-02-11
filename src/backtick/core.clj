(ns backtick.core
  "@ctdean"
  (:require
   [backtick.conf :refer [master-cf]]
   [backtick.cleaner :as cleaner]
   [backtick.engine :as engine]
   [clojure.tools.logging :as log])
  (:gen-class))

(defn register [name f]
  ;; We store both the name => fn mapping (needed by submit-worker)
  ;; and fn => name mapping (needed by schedule)
  (swap! engine/workers assoc
         name f
         f name)
  name)

(defn unregister [name]
  (swap! engine/workers dissoc name (@engine/workers name))
  name)

(defn register-cron
  "Register or re-register a scheduled job.  The job is started for the
   first time interval ms from now."
  [name interval f]
  (register name f)
  (engine/cron-add name interval)
  name)

(defn registered-workers []
  @engine/workers)

(defn registered-crons
  "All the cron jobs as a map"
  []
  (engine/cron-map))

(defn schedule
  "Run a job on the backtick queue"
  [registered-function & args]
  (let [name (@engine/workers registered-function)]
    (if (not name)
        (log/errorf
         "Worker % not registered. Register this function before calling schedule"
         registered-function)
        (engine/add name args))))

;;;
;;; Helpers
;;;

(defmacro define-worker [& function-definition]
  (let [symbol-name (first function-definition)
        str-nm (str *ns* "/" symbol-name)]
    `(do
       (defn ~@function-definition)
       (register ~str-nm ~symbol-name))))

(defmacro define-cron [name interval-ms args & body]
  (assert (= args []) "Cron function takes no arguments")
  (let [symbol-name name
        str-nm (str *ns* "/" symbol-name)]
    `(do
       (defn ~symbol-name ~args ~@body)
       (register-cron ~str-nm ~interval-ms ~symbol-name))))

;;;
;;; Cleaners
;;;

(define-cron revive-killed-jobs (:revive-check-ms master-cf) []
  (log/info "Running revive-killed-jobs")
  (cleaner/revive))

(define-cron remove-old-jobs (:remove-check-ms master-cf) []
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

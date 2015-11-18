(ns backtick.core
  "@ctdean"
  (:require
   [backtick.conf :refer [master-cf]]
   [backtick.cleaner :as cleaner]
   [backtick.engine :as engine]
   [clojure.tools.logging :as log])
  (:gen-class))

(defn register [name f]
  (swap! engine/workers assoc name f))

(defn unregister [name]
  (swap! engine/workers dissoc name))

(defn register-cron
  "Register or re-register a scheduled job.  The job is started for the
   first time interval ms from now."
  [name interval f]
  (register name f)
  (engine/cron-add name interval))

(defn registered-workers []
  @engine/workers)

(defn registered-crons
  "All the cron jobs as a map"
  []
  (engine/cron-map))

(defn perform
  "Run a job on the backtick queue"
  [name & args]
  (if (not (@engine/workers name))
      (log/errorf "No worker %s registered, not queuing job" name)
      (engine/add name args)))

;;;
;;; Helpers
;;;

(defmacro define-worker [name args & body]
  (let [nm (str *ns* "/" name)]
    `(do
       (register ~nm (fn ~args ~@body))
       (defn ~name [& param#]
         (apply perform ~nm param#)))))

(defmacro define-cron [name interval-ms args & body]
  (assert (= args []) "Cron function takes no arguments")
  (let [nm (str *ns* "/" name)]
    `(do
       (register-cron ~nm ~interval-ms (fn ~args ~@body))
       (defn ~name []
         (perform ~nm)))))

;;;
;;; Cleaners
;;;


(defn revive-killed-jobs []
  (perform "backtick.core/revive-killed-jobs"))

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

(ns backtick.core
  "@ctdean @jimbru"
  (:require
   [backtick.conf :refer [master-cf]]
   [backtick.cleaner :as cleaner]
   [backtick.engine :as engine]
   [clj-time.core :as t]
   [clojure.tools.logging :as log])
  (:gen-class))

(defn register
  "Registers a Backtick worker. Workers must be registered in order to run jobs.
   The worker may be referenced in future calls by its unique name string."
  [name f]
  (assert (string? name) "Worker name must be a string.")
  ;; We store both the name => fn mapping (needed by submit-worker)
  ;; and fn => name mapping (needed by schedule)
  (swap! engine/workers assoc
         name f
         f name)
  name)

(defn unregister
  "Unregisters a Backtick worker."
  [name]
  (swap! engine/workers dissoc name (@engine/workers name))
  name)

(defn registered-workers
  "Returns a map of all registered workers, keyed by name."
  []
  (->> @engine/workers
       (filter #(string? (key %)))
       (into {})))

(defn recurring-jobs
  "Returns a map of all recurring jobs, keyed by name."
  []
  (engine/recurring-map))

(defn- resolve-worker [worker]
  (let [v (@engine/workers worker)]
    (cond
      (string? v) v
      (fn? v)     worker
      (nil? v)    (do
                    (log/errorf (str "Worker %s not registered. Call register on this "
                                     "function before attempting to schedule it.")
                                worker)
                    nil)
      :else       (do
                    (log/errorf (str "Unexpected value %s for worker %s in map.
                                     Scheduling failed.")
                                v
                                worker)
                    nil))))

(defn schedule-at
  "Schedule a job on the Backtick queue to be run at the appointed time. Worker can be
   either the worker's registered name or a reference to the worker function itself."
  [time worker & args]
  (when-let [name (resolve-worker worker)]
    (engine/add time name args)))

(defn schedule
  "Schedule a job on the Backtick queue to be run as soon as possible. Worker can be
   either the worker's registered name or a reference to the worker function itself."
  [worker & args]
  (apply schedule-at nil worker args))

(defn schedule-recurring
  "Schedule a job to be run on the Backtick queue on a recurring basis, every msec
   milliseconds, starting for the first time msec milliseconds from now. Worker can be
   either the worker's registered name or a reference to the worker function itself."
  [msec worker & args]
  (assert (empty? args) "Recurring jobs may not take arguments.")
  (when-let [name (resolve-worker worker)]
    (engine/recurring-add name msec)))

;;;
;;; Helpers
;;;

(defmacro define-worker [& function-definition]
  (let [symbol-name (first function-definition)
        str-nm (str *ns* "/" symbol-name)]
    `(do
       (defn ~@function-definition)
       (register ~str-nm ~symbol-name))))

(defmacro define-recurring [name interval-ms args & body]
  (assert (= args []) "Recurring functions may not take arguments.")
  (let [symbol-name name  ; avoid shadowing built-in
        str-nm (str *ns* "/" symbol-name)]
    `(do
       (defn ~symbol-name ~args ~@body)
       (register ~str-nm ~symbol-name)
       (schedule-recurring ~interval-ms ~symbol-name))))

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

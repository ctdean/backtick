(ns backtick.engine
  "@ctdean @jimbru"
  (:require
   [backtick.cleaner :as cleaner]
   [backtick.conf :refer [master-cf]]
   [backtick.db :as db]
   [backtick.registry :refer [resolve-worker->fn]]
   [clj-cron-parse.core :as cron]
   [clj-time.core :as t]
   [clj-time.coerce :refer [to-sql-time to-date-time]]
   [clojure.core.async :refer [chan alts! alts!! >!! <! >! go-loop timeout]]
   [clojure.edn :as edn]
   [clojure.tools.logging :as log])
  (:import java.util.concurrent.Executors))

(defn- cron-next-date [now cronspec tz]
  (let [next (cron/next-date now cronspec tz)]
    (if (nil? next)
      (throw (Exception. (format "Failed to parse cronspec: '%s'" cronspec)))
      next)))

(defn- recurring-next
  "Calculates a job's next targeted run time based on
   its interval or cronspec."
  [now interval cronspec tz]
  (if (nil? interval)
    (cron-next-date now cronspec tz)
    (t/plus now (t/millis interval))))

(defn- recurring-add* [name enabled? interval cronspec tz]
  (let [real-interval (when (not (nil? interval))
                        (int (max (:recurring-resolution-ms master-cf)
                                  interval)))
        next (to-sql-time (recurring-next (t/now) interval cronspec tz))]
    (if enabled?
        (db/recurring-upsert-interval {:name name
                                       :interval real-interval
                                       :cronspec cronspec
                                       :timezone tz
                                       :next next})
      ;; I know it's strange to delete a job in a function called "add",
      ;; but this ensures that a newly disabled recurring job's old
      ;; database entry is disabled to match.
      (db/recurring-delete! {:name name}))))

(defn recurring-add
  "Add a recurring job to the database. The job is started for the first time
   interval ms from now. An interval value of zero indicates that the job is
   disabled and will never run."
  [name interval]
  (assert (and (integer? interval) (<= 0 interval))
          "interval must be a non-negative integer")
  (recurring-add* name (not= 0 interval) interval nil nil))

(defn recurring-add-cronspec
  "Add a recurring job calculating the appropriate run interval
   based on a cronspec and timezone."
  [name cronspec timezone]
  (recurring-add* name true nil cronspec timezone))

(defn recurring-map
  "All the recurring jobs as a map."
  []
  (->> (db/recurring-all)
       (map #(vector (:name %) %))
       (into {})))

(defn add
  "Add a job to the queue"
  [& {:keys [name time args queue-name]}]
  (assert (or (nil? args) (seq args)) "Job data must be a seq or nil.")
  (assert queue-name)
  (db/queue-insert! {:name       name
                     :priority   (to-sql-time (or time (t/now)))
                     :state      "queued"
                     :tries      0
                     :queue_name queue-name
                     :data       (prn-str args)}))

(def ^:private factory-counter (atom 0))

(defn- make-thread-factory []
  (let [thread-counter (atom 0)
        name (swap! factory-counter inc)]
    (reify java.util.concurrent.ThreadFactory
      (newThread [_ r]
        (new Thread r (format "workq-thread-%s-%s"
                              name
                              (swap! thread-counter inc)))))))

(def ^:dynamic *job-id* nil)

(defn submit-worker [pool ch msg]
  (let [{id :id name :name data :data} msg
        f (resolve-worker->fn name)]
    (log/debugf "Running job %s %s ..." id name)
    (if f
      (.submit pool (fn [] (try
                             (binding [*job-id* id]
                               (apply f data))
                             (catch Throwable e
                               (log/warnf e "Unable to run job %s %s" id name)
                               (cleaner/revive-one-job id))
                             (finally
                               (log/debugf "Running job %s %s ... done" id name)
                               (>!! ch :done)
                               (log/debugf "Running job %s %s ... done sent" id name)))))
      (do
        (log/errorf "No worker %s registered, discarding job %s" name id)
        (>!! ch :done)
        nil))))

(defn- start-runners [pool pool-size job-ch]
  (log/debugf "start-runners")
  (doseq [r (range pool-size)]
    (go-loop []
      (let [msg (<! job-ch)]
        (condp = msg
          :ping (do
                  (log/debugf "runner %s ready" r)
                  (recur))

          :stop (log/debugf "runner %s stop" r)

          (let [done-ch (chan 1)
                worker (submit-worker pool done-ch msg)
                [done? port] (alts! [done-ch (timeout (:timeout-ms master-cf))])]
            (if done?
                (db/queue-finish! (select-keys msg [:id]))
                (do
                  (when worker
                    (.cancel worker true))
                  (log/infof "runner %s timed out job: %s %s"
                             r
                             (:id msg)
                             (:name msg))
                  (cleaner/revive-one-job (:id msg))))
            (log/debugf "start-runners: waiting")
            (recur)))))))

(def ^:private recurring-checked (atom 0))

(defn- edn-safe-read-string [s]
  (try
    (edn/read-string s)
    (catch java.lang.RuntimeException e
      (if (= (.getMessage e) "No reader function for tag object")
        (do
          (log/warn "Failed to deserialize EDN job data."
                    "You probably scheduled the job with non-serializable arguments."
                    "Raw data:" s)
          nil)
        (throw e)))))

(defn- pop-payload [queue-name]
  (some-> (db/queue-pop {:queue_name queue-name})
          first
          (update :data edn-safe-read-string)))

;; Not fault tolerant
(defn- pop-recurring-aux []
  (let [now    (t/now)
        window (to-sql-time (t/plus now (t/millis (:recurring-window-ms master-cf))))]
    (when-let [payload (first (db/recurring-next {:now (to-sql-time now) :next window}))]
      (if (not (resolve-worker->fn (:name payload)))
          (do
            (log/warnf "No worker registered for recurring job %s, removing it!"
                       (:name payload))
            (db/recurring-delete! payload)
            (pop-recurring-aux))
          (let [next     (to-sql-time (recurring-next (to-date-time (:next payload))
                                                      (:interval payload)
                                                      (:cronspec payload)
                                                      (:timezone payload)))
                inserted (db/queue-insert! {:name       (:name payload)
                                            :state      "running"
                                            :queue_name "default"
                                            :priority   (to-sql-time (t/now))
                                            :tries      1
                                            :data       (prn-str [])})]
            (db/recurring-update-next! (-> (select-keys payload [:id])
                                           (assoc :next next)))
            (assoc payload :id (:id inserted)))))))

(defn- pop-recurring []
  (try
    (pop-recurring-aux)
    (catch Throwable e
      (log/warn e "Error retrieving recurring job.")
      nil)))

(defn- pop-queue-aux [queue-name]
  (or (when (and (= queue-name "default")
                 (> (System/currentTimeMillis)
                    (+ @recurring-checked (:recurring-ms master-cf))))
        (or (pop-recurring)
            (do
              (reset! recurring-checked (System/currentTimeMillis))
              nil)))
      (pop-payload queue-name)))

(defn- pop-queue [queue-name]
  (try
    (pop-queue-aux queue-name)
    (catch Throwable e
      (log/warnf e "Error retrieving queue entry %s" queue-name)
      nil)))

(defn- process-one-queue
  "Find available job runner"
  [queue-name job-ch keep-running-queue?]
  (when-let [sent? (first (alts!! [[job-ch :ping] (timeout 1000)]))]
    (loop []
      (when @keep-running-queue?
        (let [payload (pop-queue queue-name)] ; Pop from the queue
          (if payload                 ; Send the job to be processed
              (>!! job-ch (select-keys payload [:data :name :id]))
              (do                     ; Sleep if queue is empty
                (Thread/sleep (:poll-ms master-cf))
                (recur))))))))

(defn- run-queue
  "Run the master work queue.  This function never returns in normal operation."
  [pool-size queue-name job-ch keep-running-queue?]
  (try
    (while @keep-running-queue?
      (log/debug "run-queue loop")
      (process-one-queue queue-name job-ch keep-running-queue?))
    (finally
      (dotimes [_ pool-size]
        (>!! job-ch :stop)))))

(defn run [pool-size queue-name]
  (let [keep-running-queue? (atom true)
        t (future
            (log/infof "Backlog starting")
            (let [pool (Executors/newFixedThreadPool pool-size (make-thread-factory))
                  job-ch (chan)]
              (start-runners pool pool-size job-ch)
              (run-queue pool-size queue-name job-ch keep-running-queue?)
              (.shutdown pool)
              (log/infof "Shutdown complete")))]
    (fn []
      (reset! keep-running-queue? false)
      @t)))

(defn cancel-all
  "Cancels all queued jobs so that they will never run.
   Be careful, this is dangerous!"
  []
  (db/queue-cancel-all-jobs!))

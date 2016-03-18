(ns backtick.engine
  "@ctdean @jimbru"
  (:require
   [backtick.cleaner :as cleaner]
   [backtick.conf :refer [master-cf]]
   [backtick.db :as db]
   [clj-time.core :as t]
   [clj-time.coerce :refer [to-sql-time to-date-time]]
   [clojure.core.async :as async :refer [chan alts! alts!! >!! <! >! go-loop timeout]]
   [clojure.edn :as edn]
   [clojure.tools.logging :as log]
   [iter.core :refer [iter iter*]])
  (:import java.util.concurrent.Executors))

(defn recurring-add
  "Add a recurring job to the database. The job is started for the first time
   interval ms from now."
  [name interval]
  (let [real-interval (max (:recurring-resolution-ms master-cf) interval)
        next (to-sql-time (t/plus (t/now) (t/millis real-interval)))]
    (db/recurring-upsert-interval {:name name :interval (int real-interval) :next next})))

(defn recurring-map
  "All the recurring jobs as a map."
  []
  (iter (foreach job (db/recurring-all))
        (collect-map (:name job) job)))

(defn add
  "Add a job to the queue"
  [time name data]
  (assert (or (nil? data) (seq data)) "Job data must be a seq or nil.")
  (db/queue-insert<! {:name name
                      :priority (to-sql-time time)
                      :state "queued"
                      :tries 0
                      :data (prn-str data)}))

(def workers (atom {}))

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
        f (@workers name)]
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
  (iter* (foreach r (range pool-size))
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

(defn- pop-payload []
  (some-> (db/queue-pop)
          first
          (update :data edn-safe-read-string)))

;; Not fault tolerant
(defn- pop-recurring-aux []
  (let [now (t/now)
        window (to-sql-time (t/plus now (t/millis (:recurring-window-ms master-cf))))]
    (when-let [payload (first (db/recurring-next {:now (to-sql-time now) :next window}))]
      (if (not (@workers (:name payload)))
          (do
            (log/warnf "No worker registered for recurring job %s, removing it!"
                       (:name payload))
            (db/recurring-delete! payload)
            (pop-recurring-aux))
          (let [next (to-sql-time (t/plus (to-date-time (:next payload))
                                          (t/millis (:interval payload))))
                inserted (db/queue-insert<! {:name (:name payload)
                                             :state "running"
                                             :priority (to-sql-time (t/now))
                                             :tries 1
                                             :data (prn-str [])})]
            (db/recurring-update-next! (-> (select-keys payload [:id])
                                           (assoc :next next)))
            (assoc payload :id (:id inserted)))))))

(defn- pop-recurring []
  (try
    (pop-recurring-aux)
    (catch Throwable e
      (log/warn e "Error retrieving recurring job.")
      nil)))

(defn- pop-queue-aux []
  (or (when (> (System/currentTimeMillis)
               (+ @recurring-checked (:recurring-ms master-cf)))
        (if-let [payload (pop-recurring)]
          payload
          (do
            (reset! recurring-checked (System/currentTimeMillis))
            nil)))
      (pop-payload)))

(defn- pop-queue []
  (try
    (pop-queue-aux)
    (catch Throwable e
      (log/warn e "Erron retrieving queue entry")
      nil)))

(defn- process-one-queue
  "Find available job runner"
  [job-ch keep-running-queue?]
  (when-let [sent? (first (alts!! [[job-ch :ping] (timeout 1000)]))]
    (iter* (forever)
           (while @keep-running-queue?)
           (let [payload (pop-queue)] ; Pop from the queue
             (if (not payload)        ; Sleep if queue is empty
                 (Thread/sleep (:poll-ms master-cf))
                 (do                ; Send the job to be processed
                   (>!! job-ch (select-keys payload [:data :name :id]))
                   (break)))))))

(defn- run-queue
  "Run the master work queue.  This function never returns in normal operation."
  [pool-size job-ch keep-running-queue?]
  (try
    (iter* (forever)
           (log/debug "run-queue loop")
           (while @keep-running-queue?)
           (process-one-queue job-ch keep-running-queue?))
    (finally
      (iter* (times pool-size)
             (>!! job-ch :stop)))))

(defn run [pool-size]
  (let [keep-running-queue? (atom true)
        t (future
            (log/infof "Backlog starting")
            (let [pool (Executors/newFixedThreadPool pool-size (make-thread-factory))
                  job-ch (chan)]
              (start-runners pool pool-size job-ch)
              (run-queue pool-size job-ch keep-running-queue?)
              (.shutdown pool)
              (log/infof "Shutdown complete")))]
    (fn []
      (reset! keep-running-queue? false)
      @t)))

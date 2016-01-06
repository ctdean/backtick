(ns backtick.engine
  "@ctdean"
  (:require
   [backtick.conf :refer [master-cf]]
   [backtick.db :as db]
   [clj-time.core :as time]
   [clj-time.coerce :refer [to-sql-time to-date-time]]
   [clojure.core.async :as async :refer [chan alts! alts!!
                                         <!! >!! <! >! close!
                                         go go-loop timeout]]
   [clojure.tools.logging :as log]
   [iter.core :refer [iter iter*]])
  (:import (java.util.concurrent Executors TimeUnit)))

(defn cron-add
  "Add a cron job to the DB.  The job is started for the
   first time interval ms from now."
  [name interval]
  (let [real-interval (max (:cron-resolution-ms master-cf) interval)
        next (to-sql-time (time/plus (time/now) (time/millis real-interval)))]
    (db/cron-upsert-interval {:name name :interval (int real-interval) :next next})))

(defn cron-map
  "All the cron jobs as a map"
  []
  (iter (foreach cron (db/cron-all))
        (collect-map (:name cron) cron)))

(defn add
  "Add a job to the queue"
  [name data]
  (db/queue-insert! {:name name
                     :priority (to-sql-time (time/now))
                     :state "queued"
                     :data [data]}))

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

(def ^:private job-counter (atom 0))

(defn submit-worker [pool ch msg]
  (let [n (swap! job-counter inc)]
    (let [{name :name data :data} msg
          f (@workers name)]
      (log/debugf "Running job %s %s ..." name n)
      (if f
          (.submit pool (fn [] (try
                                 (apply f data)
                                 (catch Throwable e
                                   (log/warnf e "Unable to run job %s" name))
                                 (finally
                                   (log/debugf "Running job %s %s ... done" name n)
                                   (>!! ch :done)
                                   (log/debugf "Running job %s %s ... done sent"
                                               name n)))))
          (do
            (log/errorf "No worker %s registered, discarding job" name)
            (>!! ch :done)
            nil)))))

;;;
;;; job
;;;

(defn- start-jobs [pool pool-size job-ch]
  (log/debugf "start-jobs")
  (iter* (foreach job (range pool-size))
         (go-loop []
           (let [msg (<! job-ch)]
             (condp = msg
               :ping (do
                       (log/debugf "job %s ready" job)
                       (recur))
               :stop (log/debugf "job %s stop" job)
                     (let [done-ch (chan 1)
                           worker (submit-worker pool done-ch msg)]
                       (let [[done? port] (alts! [done-ch (timeout
                                                           (:timeout-ms master-cf))])]
                         (if done?
                             (db/queue-finish! (select-keys msg [:id]))
                             (do
                               (when worker
                                 (.cancel worker true))
                               (log/infof "job %s timeout: %s" job (:name msg)))))
                       (log/debugf "start-jobs: waiting")
                       (recur)))))))

(def ^:private cron-checked (atom 0))

(defn- pop-payload []
  (first (db/queue-pop)))

;; Not fault tolerant
(defn- pop-cron-aux []
  (let [now (time/now)
        window (to-sql-time (time/plus now (time/millis (:cron-window-ms master-cf))))]
    (when-let [payload (first (db/cron-next {:now (to-sql-time now) :next window}))]
      (if (not (@workers (:name payload)))
          (do
            (log/warnf "No cron worker %s registered, removing job" (:name payload))
            (db/cron-delete! payload)
            (pop-cron-aux))
          (let [next (to-sql-time (time/plus (to-date-time (:next payload))
                                             (time/millis (:interval payload))))]
            (db/queue-insert! {:name (:name payload)
                               :state "running"
                               :priority (to-sql-time (time/now))
                               :data [[]]})
            (db/cron-update-next! (-> (select-keys payload [:id])
                                      (assoc :next next)))
            payload)))))

(defn- pop-cron []
  (try
    (pop-cron-aux)
    (catch Throwable e
      (log/warn e "Error retrieving cron entry")
      nil)))

(defn- pop-queue-aux []
  (or (when (> (System/currentTimeMillis) (+ @cron-checked (:cron-ms master-cf)))
        (if-let [payload (pop-cron)]
          payload
          (do
            (reset! cron-checked (System/currentTimeMillis))
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
              (start-jobs pool pool-size job-ch)
              (run-queue pool-size job-ch keep-running-queue?)
              (.shutdown pool)
              (log/infof "Shutdown complete")))]
    (fn []
      (reset! keep-running-queue? false)
      @t)))

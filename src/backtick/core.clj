(ns backtick.core
  "@ctdean"
  (:require
   [clams.conf :as conf]
   [clj-time.coerce :refer [to-sql-time]]
   [clj-time.core :as time]
   [clojure.core.async :as async :refer [chan alts! alts!!
                                         <!! >!! <! >! close!
                                         go go-loop timeout]]
   [clojure.tools.logging :as log]
   [jdbc.pool.c3p0 :as pool]
   [iter.core :refer [iter iter*]]
   [yesql.core :refer [defqueries]]
   )
  (:import (java.util.concurrent Executors TimeUnit))
  (:gen-class))

;;;
;;; config
;;;

(defn- load-config []
  (let [cf {:db-url             (conf/get :bt-database-url)
            :poll-ms            (conf/get :bt-poll-ms           1000)
            :cron-ms            (conf/get :bt-cron-ms           (* 60 1000))
            :timeout-ms         (conf/get :bt-timeout-ms        5000)
            :revive-check-ms    (conf/get :bt-revive-check-ms   (* 5 60 1000))
            :remove-check-ms    (conf/get :bt-remove-check-ms   (* 60 60 1000))
            :max-completed-ms   (conf/get :bt-max-completed-ms  (* 7 24 60 60 1000))
            :cron-window-ms     (conf/get :bt-cron-window-ms    1000)
            :max-tries          (conf/get :bt-max-tries         16)
            }]
    cf))

(def ^:private master-cf (load-config))

;; Convert a Heroku jdbc URL
(defn- format-jdbc-url [url]
  (let [u (clojure.string/replace url
                                  #"^postgres\w*://([^:]+):([^:]+)@(.*)"
                                  "jdbc:postgresql://$3?user=$1&password=$2")]
    (if (re-find #"[?]" u)
        u
        (str u "?_ignore=_ignore"))))

(def spec
  (pool/make-datasource-spec
   {:connection-uri (format-jdbc-url (:db-url master-cf))}))

(defqueries "sql/backtick.sql"
  {:connection spec})


;;;
;;; Workers
;;;

(defn- now []
  (to-sql-time (time/now)))

(def ^:private workers (atom {}))

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

(defn- submit-worker [pool ch msg]
  (let [n (swap! job-counter inc)]
    (log/infof "Running job %s ..." n)
    (let [{name :name data :data} msg
          f (@workers name)]
      (if f
          (.submit pool (fn [] (try
                                 (apply f data)
                                 (finally
                                   (log/infof "Running job %s ... done" n)
                                   (>!! ch :done)
                                   (log/infof "Running job %s ... done sent" n)))))
          (do
            (log/errorf "No worker %s registered, discarding job" name)
            (>!! ch :done)
            nil)))))

(defn register [name f]
  (swap! workers assoc name f))

(defn unregister [name]
  (swap! workers dissoc name))

(defn registered-workers []
  @workers)

(defn register-cron
  "Register or re-register a scheduled job.  The job is started for the
   first time interval ms from now."
  [name interval f]
  (register name (fn [_] (f)))
  (let [next (to-sql-time (time/plus (time/now) (time/millis interval)))]
    (clojure.pprint/cl-format true "--- ~s\n"  {:name name :interval interval :next next})
    (cron-upsert-interval {:name name :interval (int interval) :next next})))

(defn registered-crons []
  (iter (foreach cron (cron-all))
        (collect-map (:name cron) cron)))

(defn perform
  ([name] (perform name nil))
  ([name args]
   (if (not (@workers name))
       (log/errorf "No worker %s registered, not queuing job" name)
       (queue-insert! {:name name :priority (now) :state "queued" :data args}))))

;;;
;;; job
;;;

(defn- start-jobs [pool pool-size job-ch]
  (log/infof "run-jobs")
  (iter* (foreach job (range pool-size))
         (go-loop []
           (log/infof "loop start")
           (let [msg (<! job-ch)]
             (case msg
               :ping (do
                       (log/infof "job %s ready" job)
                       (recur))
               :stop (log/infof "job %s stop" job)
               (let [done-ch (chan 1)
                     worker (submit-worker pool done-ch msg)]
                 (let [[done? port] (alts! [done-ch (timeout (:timeout-ms master-cf))])]
                   (log/infof "Job success %s" done?)
                   (if done?
                       (queue-finish! (:id msg))
                       (do
                         (when worker
                           (.cancel worker true))
                         (log/infof "job %s timeout: %s" job (:name msg)))))
                 (recur)))))))

;;;
;;; Queue
;;;

(def ^:private cron-checked (atom 0))

(defn- pop-payload []
  (queue-pop))

;; Not fault tolerant
(defn- pop-cron []
  (let [now (time/now)
        window (to-sql-time (time/plus now (time/millis (:cron-window-ms master-cf))))]
    (when-let [payload (first (cron-next {:now (to-sql-time now) :next window}))]
      (if (not (@workers (:name payload)))
          (do
            (log/warnf "No cron worker %s registered, removing job" (:name payload))
            (cron-delete payload)
            (pop-cron))
          (let [next (to-sql-time (time/plus (:next payload)
                                             (time/millis (:interval payload))))]
            (queue-insert! {:name (:name payload)
                            :state "running"
                            :priority (now)})
            (cron-update-next! {:id (:id payload) :next next})
            payload)))))

(defn- pop-queue []
  (or (when (> (System/currentTimeMillis) (+ @cron-checked (:cron-ms master-cf)))
        (if-let [payload (pop-cron)]
            payload
            (do
              (reset! cron-checked (System/currentTimeMillis))
              nil)))
      (pop-payload)))

;;; Run the master work queue.  This function never returns in normal
;;; operation.

(def ^:private keep-running-queue? (atom false))

(defn- run-queue [pool-size job-ch]
  (try
    (iter* (forever)
           (log/debug "run-queue loop")
           (while @keep-running-queue?)
           ;; Find available job runner
           (when-let [sent? (first (alts!! [[job-ch :ping] (timeout 1000)]))]
             (iter* (forever)
                    (while @keep-running-queue?)
                    (let [payload (pop-queue)] ; Pop from the queue
                      (if (not payload)        ; Sleep if queue is empty
                          (Thread/sleep (:poll-ms master-cf))
                          (do                ; Send the job to be processed
                            (>!! job-ch (select-keys payload [:data :name :_id]))
                            (break)))))))
    (finally
      (iter* (times pool-size)
             (>!! job-ch :stop)))))

;;;
;;; Entry point
;;;

(defn run [pool-size]
  (if (not (compare-and-set! keep-running-queue? false true))
      (log/infof "Backlog already running")
      (future
        (let [pool (Executors/newFixedThreadPool pool-size (make-thread-factory))
              job-ch (chan)]
          (start-jobs pool pool-size job-ch)
          (run-queue pool-size job-ch)
          (.shutdown pool)
          (log/infof "Shutdown complete")))))

(defn shutdown []
  (compare-and-set! keep-running-queue? true false))

;;;
;;; Helpers
;;;

(defmacro define-worker [name args & body]
  (let [nm (str *ns* "/" name)]
    `(do
       (register ~nm (fn ~args ~@body))
       (defn ~name [& param#]
         (perform ~nm param#)))))

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

(defn- exceeded? [payload]
  (>= (:tries payload)
      (:max-tries master-cf)))

(defn- revive-priority [payload]
  (* (:timeout-ms master-cf)
     (int (Math/pow 2 (- (:tries payload) 2)))))

(defn- revive []
  (let [t (to-sql-time (time/minus (time/now)
                                   (time/millis (* 2 (:timeout-ms master-cf)))))
        killed (queue-killed-jobs {:killtime t})]
    (iter* (foreach payload killed)
           (if (exceeded? payload)
               (queue-abort-job! {:id (:id payload)})
               (queue-requeue-job! {:id (:id payload)
                                    :priority (revive-priority payload)})))))

(defn- remove-old []
  (let [t (to-sql-time (time/plus (time/now) (:max-completed-ms master-cf)))]
    (queue-delete-old-jobs! {:finished t})))

(define-cron revive-killed-jobs (:revive-check-ms master-cf) []
  (revive))

(define-cron remove-old-jobs (:remove-check-ms master-cf) []
  (remove-old))

(defn -main [& args]
  (log/info "Starting backlog")
  (run 8))

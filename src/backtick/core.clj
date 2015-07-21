(ns backtick.core
  "@ctdean"
  (:require
   [clams.conf :as conf]
   [clj-time.coerce :as coerce]
   [clj-time.core :as time]
   [clojure.core.async :as async :refer [chan alts! alts!!
                                         <!! >!! <! >! close!
                                         go go-loop timeout]]
   [clojure.pprint :as pprint]
   [clojure.tools.logging :as log]
   [iter.core :refer [iter iter*]]
   [monger.collection :as mc]
   [monger.core :as mg]
   monger.joda-time
   [monger.operators :refer [$set $setOnInsert $inc $lt $ne]]
   )
  (:import (java.util.concurrent Executors TimeUnit)))

;;;
;;; config
;;;

(defn load-config []
  (let [cf {:mongo-url          (conf/get :bt-mongo-url)
            :coll               (conf/get :bt-coll           "bt_queue")
            :cron               (conf/get :bt-cron           "bt_cron")
            :poll-ms            (conf/get :bt-poll-ms        1000)
            :cron-ms            (conf/get :bt-cron-ms        (* 60 1000))
            :timeout            (conf/get :bt-timeout        5000)
            :cron-window-ms     (conf/get :bt-cron-window-ms 1000)
            }
        mongo-cnx (mg/connect-via-uri (:mongo-url cf))
        conn (:conn mongo-cnx)
        db (:db mongo-cnx)]
    (assoc cf :db db)))

(def master-cf (load-config))

;;;
;;; Workers
;;;

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

(defn- submit-worker [pool ch msg]
  (log/infof "Running job")
  (let [{worker :worker data :data} msg
        f (@workers worker)]
    (if f
        (.submit pool (fn [] (try (f data) (finally (>!! ch :done)))))
        (do
          (log/errorf "No worker %s registered, discarding job" worker)
          (>!! ch :done)
          nil))))

(defn register [name f]
  (swap! workers assoc name f))

(defn register-cron
  "Register or re-register a scheduled job."
  [name interval f]
  (register name (fn [_] (f)))
  ;; There are three cases here:
  ;;  1.  A new job => We should insert it into the DB
  ;;  2.  An existing job, with a new interval => Change the next and
  ;;      interval fields.
  ;;  3.  A existing job, with the same interval => Do nothing
  (let [t (time/now)]
    (when (not (mc/find-and-modify (:db master-cf)  ; Change the interval if needed
                                   (:cron master-cf)
                                   {:worker name :interval {$ne interval}}
                                   {$set {:next t :interval interval}}
                                   {:new true}))
      (mc/find-and-modify (:db master-cf) ; Insert new job if needed
                          (:cron master-cf)
                          {:worker name}
                          {$setOnInsert {:worker name :next t :interval interval :x 3}}
                          {:upsert true}))))

(defn perform [name args]
  (if (not (@workers name))
      (log/errorf "No worker %s registered, not queuing job" name)
      (mc/insert (:db master-cf)
                 (:coll master-cf)
                 {:worker name :data args})))

;;;
;;; job
;;;

(defn- start-jobs [pool pool-size job-ch]
  (log/infof "run-jobs")
  (iter* (foreach job (range pool-size))
         (go-loop []
           (let [msg (<! job-ch)]
             (case msg
               :ping (do
                       (log/infof "job %s ready" job)
                       (recur))
               :stop (log/infof "job %s stop" job)
               (let [done-ch (chan 1)
                     worker (submit-worker pool done-ch msg)]
                 (let [[done? port] (alts! [done-ch (timeout (:timeout master-cf))])]
                   (when-not done?
                     (when worker (.cancel worker true))
                     (log/infof "job %s timeout: %s" job (:worker msg))))
                 (recur)))))))

;;;
;;; Queue
;;;

(def ^:private cron-checked (atom 0))

(defn- pop-payload []
  (mc/find-and-modify (:db master-cf)
                      (:coll master-cf)
                      {:state nil}
                      {$set {:state "running"} $inc {:tries 1}}
                      {:sort {:_id 1}}))

(defn- pop-cron []
  (let [now (time/now)
        window (time/plus now (time/millis (:cron-window-ms master-cf)))]
    (when-let [payload (mc/find-and-modify (:db master-cf)
                                           (:cron master-cf)
                                           {:next {$lt now}}
                                           {$set {:next window}}
                                           {})]
      (if (not (@workers (:name payload)))
          (do
            (log/warnf "No cron worker %s registered, removing job" (:name payload))
            (mc/remove (:db master-cf) (:cron master-cf) {:_id (:_id payload)})
            (pop-cron))
          (let [next (time/plus (:next payload) (time/millis (:interval payload)))]
            (mc/insert (:db master-cf)
                       (:coll master-cf)
                       (assoc payload :state "running" :tries 1))
            (mc/find-and-modify (:db master-cf)
                                (:cron master-cf)
                                {:_id (:_id payload)}
                                {$set {:next next}}
                                {})
            payload)))))

(defn- pop-queue []
  (or (when (> (System/currentTimeMillis) (+ @cron-checked (:cron-ms master-cf)))
        (if-let [payload (pop-cron)]
            payload
            (do
              (reset! cron-checked (System/currentTimeMillis))
              nil)))
      (pop-payload)))

(defn- run-queue [pool-size job-ch]
  (try
    (iter* (times)
           (>!! job-ch :ping)           ; Find available job runner
           (iter* (times)
                  (with payload (pop-queue)) ; Pop from the queue
                  (if (not payload)     ; Sleep if queue is empty
                      (Thread/sleep (:poll-ms master-cf))
                      (do               ; Send the job to be processed
                        (>!! job-ch (select-keys payload [:data :worker]))
                        (break)))))
    (finally
      (iter* (times pool-size)
             (>!! job-ch :stop)))))

;;;
;;; Entry point
;;;

(defn run [pool-size]
  (let [pool (Executors/newFixedThreadPool pool-size (make-thread-factory))
        job-ch (chan)]
    (start-jobs pool pool-size job-ch)
    (run-queue pool-size job-ch)
    (.shutdown pool)
    (log/infof "Shutdown complete")))

;;;
;;; Helpers
;;;

(defmacro define-worker [name args & body]
  (let [nm (str *ns* "/" name)]
    `(do
       (register ~nm (fn ~args ~@body))
       (defn ~name [param#]
         (perform ~nm param#)))))

(defmacro define-cron [name interval args & body]
  (assert (= args []) "Cron function takes no arguments")
  (let [nm (str *ns* "/" name)]
    `(do
       (register-cron ~nm ~interval (fn ~args ~@body)))))

(ns backtick.core
  "@ctdean"
  (:require
   [clams.conf :as conf]
   [clojure.core.async :as async :refer [chan alts! alts!!
                                         <!! >!! <! >! close! go go-loop timeout]]
   [clojure.pprint :as pprint]
   [clojure.tools.logging :as log]
   [iter.core :refer [iter iter*]]
   [monger.collection :as mc]
   [monger.operators :refer [$set $inc]]
   [monger.core :as mg]
   )
  (:import (java.util.concurrent Executors TimeUnit)))

;;;
;;; config
;;;

(defn load-config []
  (let [cf (merge {:coll "bt_queue"
                   :poll-ms 1000
                   :timeout 5000}
                  (conf/get :backtick))
        mongo-cnx (mg/connect-via-uri (:mongo-url cf))
        conn (:conn mongo-cnx)
        db (:db mongo-cnx)]
    (assoc cf :db db)))

(def master-cf (load-config))

;;;
;;; Runner
;;;

;;;
;;; Workers
;;;

(def workers (atom {}))

(def factory-counter (atom 0))

(defn make-thread-factory []
  (let [thread-counter (atom 0)
        name (swap! factory-counter inc)]
    (reify java.util.concurrent.ThreadFactory
      (newThread [_ r]
        (new Thread r (format "workq-thread-%s-%s"
                              name
                              (swap! thread-counter inc)))))))

(defn submit-worker [pool ch msg]
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

(defn perform [name args]
  (if (not (@workers name))
      (log/errorf "No worker %s registered, discarding job" name)
      (mc/insert (:db master-cf)
                 (:coll master-cf)
                 {:worker name :data args})))

;;;
;;; job
;;;

(defn start-jobs [pool pool-size job-ch]
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

(defn pop-payload []
  (mc/find-and-modify (:db master-cf)
                      (:coll master-cf)
                      {:state nil}
                      {$set {:state "running"} $inc {:tries 1}}
                      {:sort {:_id 1}}))

(defn run-queue [pool-size job-ch]
  (try
    (iter* (times)
           (>!! job-ch :ping)        ; Find available job runner
           (iter* (times)
                  (with payload (pop-payload))
                  (if (not payload)
                      (Thread/sleep (:poll-ms master-cf))
                      (do
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

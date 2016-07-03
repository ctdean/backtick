(ns backtick.conf
  "@ctdean"
  (:require [conf.core :as conf]))

(defn- load-config []
  (let [db (conf/get :database-url)
        cf {:db-url                   (conf/get :bt-database-url        db)
            :poll-ms                  (conf/get :bt-poll-ms             1000)
            :recurring-ms             (conf/get :bt-recurring-ms        (* 30 1000))
            :recurring-resolution-ms  (conf/get :bt-recurring-ms        (* 60 1000))
            :timeout-ms               (conf/get :bt-timeout-ms          5000)
            :retry-ms                 (conf/get :bt-retry-ms            10000)
            :revive-check-ms          (conf/get :bt-revive-check-ms     (* 5 60 1000))
            :remove-check-ms          (conf/get :bt-remove-check-ms     (* 60 60 1000))
            :max-completed-ms         (conf/get :bt-max-completed-ms    (* 7 24 60 60 1000))
            :recurring-window-ms      (conf/get :bt-recurring-window-ms 1000)
            :max-tries                (conf/get :bt-max-tries           16)
            :pool-size                (conf/get :bt-pool-size           16)
            }]
    cf))

(def master-cf (load-config))

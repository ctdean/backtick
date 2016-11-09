(ns backtick.db
  "@ctdean"
  (:require
   [backtick.conf :refer [master-cf]]
   [clj-time.coerce :refer [to-sql-time]]
   [clj-time.core :as time]
   [clojure.string :as string]
   [jdbc.pool.c3p0 :as pool]
   [yesql.core :refer [defqueries]])
  (:import (java.util.concurrent Executors TimeUnit)))

;;;
;;; config
;;;

;; Convert a Heroku jdbc URL
(defn- format-jdbc-url [url]
  (let [u (string/replace url
                          #"^postgres\w*://([^:]+):([^:]+)@(.*)"
                          "jdbc:postgresql://$3?user=$1&password=$2")]
    (if (re-find #"[?]" u)
        u
        (str u "?_ignore=_ignore"))))

(def spec
  (let [dburl (:db-url master-cf)]
    (when (nil? dburl)
      (throw (Exception. "Database URL is not set! Aborting.")))
    (pool/make-datasource-spec
     {:connection-uri (format-jdbc-url dburl)
      :initial-pool-size 3})))

(defqueries "sql/backtick.sql"
  {:connection spec})

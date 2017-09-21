(ns backtick.db
  "@ctdean"
  (:require
   [backtick.conf :refer [master-cf]]
   [clj-time.coerce :refer [to-sql-time]]
   [clj-time.core :as time]
   [clojure.string :as string]
   [common.db.util :refer [format-jdbc-url]]
   [hugsql.core :as hugsql]
   [jdbc.pool.c3p0 :as pool])
  (:import (java.util.concurrent Executors TimeUnit)))

(def default-spec
  {:min-pool-size     3
   :max-pool-size     15
   :initial-pool-size 3})

(def datasource
  (let [dburl (:db-url master-cf)]
    (when (nil? dburl)
      (throw (Exception. "Database URL is not set! Aborting.")))
    (pool/make-datasource-spec
     (merge default-spec
            {:connection-uri (format-jdbc-url dburl)}))))

(defn define-hug-sql-with-connection [connection filename]
  (doseq [[id {f :fn {doc :doc} :meta}] (hugsql/map-of-db-fns filename)]
    (intern *ns*
            (with-meta (symbol (name id)) {:doc doc})
            (fn
              ([] (f connection {}))
              ([params] (f connection params))
              ([conn params] (f conn params))
              ([conn params opts & command-opts]
               (apply f conn params opts command-opts))))))

(define-hug-sql-with-connection datasource "sql/backtick.sql")

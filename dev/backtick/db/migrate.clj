(ns backtick.db.migrate
  (:require [conf.core :as conf]
            [ragtime.jdbc :as jdbc]
            [ragtime.repl :as repl]))

(defn load-config []
  {:datastore (jdbc/sql-database (conf/get :database-url))
   :migrations (jdbc/load-resources "backtick/migrations")})

(defn migrate []
  (repl/migrate (load-config)))

(defn rollback []
  (repl/rollback (load-config)))

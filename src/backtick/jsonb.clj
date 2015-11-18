(ns backtick.jsonb
  "Read and write jsonb columns in PG

   @ctdean"
  (:require
   [cheshire.core :as json]
   [clojure.java.jdbc :as jdbc])
  (:import org.postgresql.util.PGobject))

(defn- value-to-jsonb-pgobject [value]
  (doto (PGobject.)
    (.setType "jsonb")
    (.setValue (json/encode value))))

(extend-protocol jdbc/ISQLValue
  clojure.lang.Seqable
  (sql-value [value] (value-to-jsonb-pgobject value)))

(extend-protocol jdbc/IResultSetReadColumn
  PGobject
  (result-set-read-column [pgobj metadata idx]
    (let [type  (.getType pgobj)
          value (.getValue pgobj)]
      (case type
        "jsonb" (json/decode value)
        value))))

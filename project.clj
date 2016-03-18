(defproject ctdean/backtick
  "0.5.2"
  :description "Background job processing for Clojure using Postgres"
  :dependencies
  [
   [clams "0.2.6" :exclusions [ring]]
   [clj-time "0.11.0"]
   [clojure.jdbc/clojure.jdbc-c3p0 "0.3.2"]
   [ctdean/iter "0.10.3" :exclusions [medley]]
   [org.clojure/clojure "1.7.0"]
   [org.clojure/core.async "0.2.374"]
   [org.clojure/java.jdbc "0.4.2"]
   [org.clojure/test.check "0.9.0"]
   [org.clojure/tools.logging "0.3.1"]
   [org.postgresql/postgresql "9.4.1208"]
   [org.slf4j/slf4j-log4j12 "1.7.16"]
   [yesql "0.5.2"]
   ]
  :jar-exclusions [#"^migrations/"]
  :plugins [[com.jakemccrary/lein-test-refresh "0.10.0"]]
  :profiles {:test {:jvm-opts ["-Dclams.env=test"]}
             :production {:jvm-opts ["-Dclams.env=production"]}})

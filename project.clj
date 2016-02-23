(defproject ctdean/backtick
  "0.4.7"
  :description "Background job processing for Clojure using Postgres"
  :dependencies
  [
   [clams "0.2.6" :exclusions [ring]]
   [clojure.jdbc/clojure.jdbc-c3p0 "0.3.2"]
   [ctdean/iter "0.10.2"]
   [org.clojure/clojure "1.7.0"]
   [org.clojure/core.async "0.2.374"]
   [org.clojure/java.jdbc "0.4.2"]
   [org.postgresql/postgresql "9.3-1102-jdbc41"]
   [org.clojure/test.check "0.7.0"]
   [org.clojure/tools.logging "0.3.1"]
   [org.slf4j/slf4j-log4j12 "1.7.7"]
   [yesql "0.5.1"]
   ]
  :jar-exclusions [#"^migrations/"]
  :plugins [[com.jakemccrary/lein-test-refresh "0.10.0"]]
  :profiles {:test {:jvm-opts ["-Dclams.env=test"]}
             :production {:jvm-opts ["-Dclams.env=production"]}})

(defproject ctdean/backtick
  "0.7.4"
  :description "Background job processing for Clojure using Postgres"
  :dependencies
  [
   [clj-time "0.12.0"]
   [clojure.jdbc/clojure.jdbc-c3p0 "0.3.2"]
   [conf "0.9.1" :exclusions [org.clojure/clojure]]
   [org.clojure/clojure "1.8.0"]
   [org.clojure/core.async "0.2.391"]
   [org.clojure/java.jdbc "0.6.1"]
   [org.clojure/test.check "0.9.0"]
   [org.clojure/tools.logging "0.3.1"]
   [org.postgresql/postgresql "9.4.1210"]
   [org.slf4j/slf4j-log4j12 "1.7.21"]
   [st/common "0.10.6"]
   [yesql "0.5.2"]
   ]
  :jar-exclusions [#"^migrations/"]
  :plugins [[com.jakemccrary/lein-test-refresh "0.10.0"]]
  :profiles {:test {:jvm-opts ["-Dconf.env=test"]}
             :production {:jvm-opts ["-Dconf.env=production"]}})

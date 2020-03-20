(defproject ctdean/backtick
  "1.5.2"
  :description "A background job processor"
  :license :eclipse
  :dependencies
  [
   [cider/cider-nrepl "0.14.0" :scope "test"]
   [clj-cron-parse "0.1.4" :exclusions [org.clojure/clojure]]
   [clj-time "0.14.0" :exclusions [org.clojure/clojure]]
   [clojure.jdbc/clojure.jdbc-c3p0 "0.3.2" :exclusions [org.clojure/clojure]]
   [com.carouselapps/to-jdbc-uri "0.5.0" :exclusions [org.clojure/clojure]]
   [com.layerware/hugsql "0.4.6" :exclusions [org.clojure/clojure]]
   [conf "0.11.0" :exclusions [org.clojure/clojure]]
   [org.clojure/clojure "1.8.0"]
   [org.clojure/core.async "0.3.443" :exclusions [org.clojure/clojure]]
   [org.clojure/java.jdbc "0.7.1" :exclusions [org.clojure/clojure]]
   [org.clojure/test.check "0.9.0" :exclusions [org.clojure/clojure]]
   [org.clojure/tools.logging "0.4.0" :exclusions [org.clojure/clojure]]
   [org.postgresql/postgresql "9.4.1211"]
   [treasuryprime/common "1.3.0"]
   ]
  :plugins [[s3-wagon-private "1.3.2"]
            [com.jakemccrary/lein-test-refresh "0.22.0"]]
  :profiles {:test {:jvm-opts ["-Dconf.env=test"]}}
  :repositories
  [["tprime" {:url "s3p://treasuryprime-jars/releases/" :no-auth true}]]
  :test-refresh {:quiet true
                 :changes-only true}
  )

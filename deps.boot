;;;; -*- clojure -*-
;;;; Build dependencies
;;;;
;;;; @ctdean

'[
  [adzerk/boot-test "1.2.0" :scope "test"]
  [clj-cron-parse "0.1.4" :exclusions [org.clojure/clojure]]
  [clj-time "0.14.0" :exclusions [org.clojure/clojure]]
  [cider/cider-nrepl "0.14.0" :scope "test"]
  [clojure.jdbc/clojure.jdbc-c3p0 "0.3.2" :exclusions [org.clojure/clojure]]
  [com.carouselapps/to-jdbc-uri "0.5.0" :exclusions [org.clojure/clojure]]
  [com.layerware/hugsql "0.4.6" :exclusions [org.clojure/clojure]]
  [conf "0.11.0" :exclusions [org.clojure/clojure]]
  [metosin/boot-alt-test "0.3.2" :scope "test"]
  [org.clojure/core.async "0.3.443" :exclusions [org.clojure/clojure]]
  [org.clojure/java.jdbc "0.7.1" :exclusions [org.clojure/clojure]]
  [org.clojure/test.check "0.9.0" :exclusions [org.clojure/clojure]]
  [org.clojure/tools.logging "0.4.0" :exclusions [org.clojure/clojure]]
  [org.postgresql/postgresql "9.4.1211"]
  [ronin/common "1.2.1"]
  ]

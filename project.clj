(defproject backtick "0.1.0-SNAPSHOT"
  :description "Background job processing for Clojure"
  :dependencies
    [
     [clams "0.2.0"]
     [com.novemberain/monger "3.0.0-rc2"]
     [ctdean/iter "0.7.0"]
     [org.clojure/clojure "1.7.0"]
     [org.clojure/core.async "0.1.346.0-17112a-alpha"]
     [org.clojure/test.check "0.7.0"]
     [org.clojure/tools.logging "0.3.1"]
     [org.slf4j/slf4j-log4j12 "1.7.7"]
     ]
    :plugins [
              [com.jakemccrary/lein-test-refresh "0.10.0"]
              ]
    :profiles {
               :test {:jvm-opts ["-Dclams.env=test"]}
               :production {:jvm-opts ["-Dclams.env=production"]}
               })

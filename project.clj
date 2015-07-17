(defproject backtick "0.1.0-SNAPSHOT"
  :description "Background job processing for Clojure"
  :dependencies
    [
     [com.novemberain/monger "3.0.0-rc2"]
     [clams "0.2.0"]
     [ctdean/iter "0.5.1"]
     [org.clojure/clojure "1.7.0"]
     [org.clojure/core.async "0.1.346.0-17112a-alpha"]
     [org.clojure/tools.logging "0.3.1"]
     [org.slf4j/slf4j-log4j12 "1.7.7"]
     ]
    :dev {
          :dependencies [cider/cider-nrepl "0.9.1"]
          :repl-options {:nrepl-middleware
                         [cider.nrepl.middleware.apropos/wrap-apropos
                          cider.nrepl.middleware.classpath/wrap-classpath
                          cider.nrepl.middleware.complete/wrap-complete
                          cider.nrepl.middleware.debug/wrap-debug
                          cider.nrepl.middleware.format/wrap-format
                          cider.nrepl.middleware.info/wrap-info
                          cider.nrepl.middleware.inspect/wrap-inspect
                          cider.nrepl.middleware.macroexpand/wrap-macroexpand
                          cider.nrepl.middleware.ns/wrap-ns
                          cider.nrepl.middleware.pprint/wrap-pprint
                          cider.nrepl.middleware.refresh/wrap-refresh
                          cider.nrepl.middleware.resource/wrap-resource
                          cider.nrepl.middleware.stacktrace/wrap-stacktrace
                          cider.nrepl.middleware.test/wrap-test
                          cider.nrepl.middleware.trace/wrap-trace
                          cider.nrepl.middleware.undef/wrap-undef]}}
)

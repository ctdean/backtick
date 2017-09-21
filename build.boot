;;;;
;;;; build.boot
;;;;
;;;; Chris Dean

(def project 'ctdean/backtick)
(def version "1.1.0")

(set-env!
 :source-paths #{"test" "src"}
 :resource-paths #{"resources" "src"}
 :dependencies (load-file "deps.boot")
)

(task-options!
 pom {:project project
      :version version}
 push {:repo "clojars"}
 sift {:include #{#"\.jar$"}})

(require 'common.db.migrate)
(deftask migrate []
  (#'common.db.migrate/run-migrate))

(require '[adzerk.boot-test :refer [test]])
(require '[metosin.boot-alt-test :refer [alt-test]])

(deftask cider
  "CIDER profile"
  []
  (require 'boot.repl)
  (swap! @(resolve 'boot.repl/*default-middleware*)
         concat '[cider.nrepl/cider-middleware
                  refactor-nrepl.middleware/wrap-refactor])
  identity)

(deftask run-repl
  "Run the project with an interactive repl."
  []
  (require 'app.main)
  (comp
   (repl :init-ns 'app.main)
   (with-pass-thru _
     ((resolve 'app.main/-main)))))

(deftask run-cider
  "Run the project with a cider repl."
  []
  (require 'app.main)
  (comp
   (cider)
   (repl :server true)
   (with-pass-thru _
     ((resolve 'app.main/-main)))
   (wait)))

(deftask deploy
  "Build the jar."
  []
  (comp
   (pom)
   (jar)
   (push :repo "clojars")))

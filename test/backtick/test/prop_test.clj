(ns backtick.test.prop-test
  (:require
   [backtick.core :as bt]
   [backtick.test.fixtures :as fixtures]
   [clojure.core.async :as async :refer [alts!! chan go timeout <!! >!! <! >!]]
   [clojure.test :refer :all]
   [clojure.test.check.clojure-test :refer :all]
   [clojure.test.check :as tc]
   [clojure.test.check.generators :as gen]
   [clojure.test.check.properties :as prop]
   [clojure.tools.logging :as log]
   [iter.core :refer [iter iter*]]
   ))

(use-fixtures :once fixtures/wrap-fixture-data)

(defn exec-workers [ints]
  (clojure.pprint/cl-format true "--- try ~s\n" ints)
  (let [state (atom #{})
        ch (chan)
        name (str "prop-worker-" (rand-int 1e6))]
    (try
      (bt/register name (fn [val]
                          (log/infof "exec-worker %s" val)
                          (swap! state conj (inc val))
                          (>!! ch val)))
      (clojure.pprint/cl-format true "--- ~s\n" (bt/registered-workers))
      (iter* (foreach i ints)
             (bt/perform name [i]))
      (let [done (chan)]
        (go
          (iter* (times (count ints))
                 (<!! ch))
          (>! done :done))
        (alts!! [done (timeout 1000)]))
      (finally (bt/unregister name)))
    @state))

(defonce runner (future (bt/run 16)))

(defspec exec-test 100
  (prop/for-all [ints (gen/vector gen/int)]
                (= (set (map inc ints))
                   (exec-workers ints))))

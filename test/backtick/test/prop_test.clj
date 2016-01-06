(ns backtick.test.prop-test
  (:require
   [backtick.core :as bt]
   [backtick.test.fixtures :as fixtures]
   [clojure.core.async :refer [alts!! chan go timeout <!! >!! <! >!]]
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
  (let [state (atom #{})
        ch (chan)
        name (str "prop-worker-" (rand-int 1e6))]
    (try
      (let [f (fn [val]
                (log/infof "TEST exec-worker %s" val)
                (swap! state conj (inc val))
                (>!! ch val))]
        (bt/register name f)
        (iter* (foreach i ints)
               (bt/schedule f i))
        (let [done (chan)]
          (go
            (iter* (times (count ints))
                   (<!! ch))
            (>! done :done))
          (alts!! [done (timeout 10000)])))
      (finally (bt/unregister name)))
    @state))

(defonce runner1 (bt/start))
(defonce runner2 (bt/start))

(defspec exec-test
  10
  (prop/for-all [ints (gen/vector gen/int)]
                (= (set (map inc ints))
                   (exec-workers ints))))

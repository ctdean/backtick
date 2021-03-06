(ns backtick.test.prop-test
  (:require
   [backtick.core :as bt]
   [backtick.registry :as registry]
   [clojure.core.async :refer [alts!! chan go timeout <!! >!! <! >!]]
   [clojure.test :refer :all]
   [clojure.test.check.clojure-test :refer :all]
   [clojure.test.check.generators :as gen]
   [clojure.test.check.properties :as prop]
   [clojure.tools.logging :as log]))

(defn wrap-run-bt-instance [f]
  (let [runner1 (bt/start)
        runner2 (bt/start)]
    (f)
    (runner1) ; shutdown
    (runner2)))

(use-fixtures :each wrap-run-bt-instance)

(defn exec-workers [ints]
  (let [state (atom #{})
        ch (chan)
        name (str "prop-worker-" (rand-int 1e6))]
    (try
      (let [f (fn [val]
                (log/infof "TEST exec-worker %s" val)
                (swap! state conj (inc val))
                (>!! ch val))]
        (registry/register name f)
        (doseq [i ints]
          (bt/schedule f i))
        (let [done (chan)]
          (go
            (dotimes [_ (count ints)]
              (<!! ch))
            (>! done :done))
          (alts!! [done (timeout 10000)])))
      (finally (registry/unregister name)))
    @state))

(defspec exec-test
  10
  (prop/for-all [ints (gen/vector gen/int)]
                (= (set (map inc ints))
                   (exec-workers ints))))

(deftest higher-arity-test
  (let [p (promise)
        worker (fn [a b c] (deliver p (/ (+ a b) c)))]
    (registry/register "higher-arity-worker" worker)
    (bt/schedule worker 3 7 5)
    (is (= @p 2))))

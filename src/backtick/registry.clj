(ns backtick.registry
  "Registry of backtick workers @ctdean"
  (:require
   [clojure.tools.logging :as log]))

(def ^:private workers (atom {}))

(defn register
  "Registers a Backtick worker. Workers must be registered in order to run jobs.
   The worker may be referenced in future calls by its unique name string."
  [name f]
  (assert (string? name) "Worker name must be a string.")
  ;; We store both the name => fn mapping (needed by submit-worker)
  ;; and fn => name mapping (needed by schedule)
  (swap! workers assoc
         name f
         f name)
  name)

(defn unregister
  "Unregisters a Backtick worker."
  [name]
  (swap! workers dissoc name (@workers name))
  name)

(defn registered-workers
  "Returns a map of all registered workers, keyed by name."
  []
  (->> @workers
       (filter #(string? (key %)))
       (into {})))

(defn- resolve-worker-aux [v worker]
  (if (nil? v)
      (do
        (log/errorf (str "Worker %s not registered. Call register on this "
                         "function before attempting to schedule it.")
                    worker)
        nil)
      (do
        (log/errorf "Unexpected value %s for worker %s in map. Scheduling failed."
                    v
                    worker)
        nil)))

(defn resolve-worker->name
  "Lookup a valid worker name given a registered name or registered function"
  [worker]
  (let [v (@workers worker)]
    (cond
     (string? v) v
     (fn? v)     worker
     :else (resolve-worker-aux v worker))))

(defn resolve-worker->fn
  "Lookup a valid worker function given a registered name or registered function"
  [worker]
  (let [v (@workers worker)]
    (cond
     (string? v) worker
     (fn? v)     v
     :else (resolve-worker-aux v worker))))

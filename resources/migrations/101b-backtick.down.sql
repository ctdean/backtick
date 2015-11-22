;; -*- clojure -*-

(require
 '[backtick.core :refer [master-cf]]
 '[monger.collection :as mc])

;; Remove the main indexes
(let [index-name (str (:coll master-cf) "_state_index")]
  (mc/drop-index (:db master-cf) (:coll master-cf) index-name))
(let [index-name (str (:coll master-cf) "_pri_index")]
  (mc/drop-index (:db master-cf) (:coll master-cf) index-name))
(let [index-name (str (:coll master-cf) "_finished_index")]
  (mc/drop-index (:db master-cf) (:coll master-cf) index-name))
(let [index-name (str (:coll master-cf) "_pop_index")]
  (mc/drop-index (:db master-cf) (:coll master-cf) index-name))

;; Remove the cron indexes
(let [index-name (str (:cron master-cf) "_worker_index")]
  (mc/drop-index (:db master-cf) (:cron master-cf) index-name))
(let [index-name (str (:cron master-cf) "_next_index")]
  (mc/drop-index (:db master-cf) (:cron master-cf) index-name))

(ns bing-webmaster-bulk-url-removal.background.storage
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [cljs.core.async :refer [<! >! chan close!]]
            [cljs-time.core :as t]
            [cljs-time.coerce :as tc]
            [chromex.logging :refer-macros [log info warn error group group-end]]
            [chromex.protocols.chrome-storage-area :as storage-area]
            [chromex.ext.storage :as storage]))


(def ^:dynamic *DONE-FLAG* "**D0N3-FL@G**")

(defn store-victims!
  "status: pending, removed, removing, error
  CSV format : url, removal-method, url-type

  removal-method: 'remove-url' vs 'clear-cached'
  url-type: 'url-only' vs 'prefix'
  "
  [{:keys [data]}]
  (let [local-storage (storage/get-local)
        data (concat data [["poison-pill" *DONE-FLAG*]])]
    (go-loop [[[url optional-removal-method optional-url-type :as curr] & more] data
              idx 0]
      (let [optional-removal-method (or (if (empty? optional-removal-method) nil optional-removal-method) "remove-url")
            optional-url-type (or (if (empty? optional-url-type) nil optional-url-type) "url-only")]
       (if (nil? curr)
         (log "DONE storing victims")
         (let [[[items] error] (<! (storage-area/get local-storage url))]
           (if error
             (error (str "fetching " url ":") error)
             (do (log "setting url: " url " | method: " optional-removal-method)
                 (log "setting url: " url " | url-type " optional-url-type)
                 (storage-area/set local-storage (clj->js {url {"submit-ts" (tc/to-long (t/now))

                                                                "remove-ts" nil
                                                                "removal-method" optional-removal-method
                                                                "url-type" optional-url-type
                                                                "status" "pending"
                                                                "idx" idx}
                                                           }))))
           (recur more (inc idx)))
         )))
    ))

(defn update-storage [url & args]
  {:pre [(even? (count args))]}
  (let [kv-pairs (partition 2 args)
        local-storage (storage/get-local)
        ch (chan)]
    (go
      (let [[[items] error] (<! (storage-area/get local-storage url))]
        (if error
          (error (str "fetching " url ":") error)
          (let [entry (->> (js->clj items) vals first)
                r {url (->> kv-pairs
                            (reduce (fn [accum [k v]]
                                      (assoc accum k v))
                                    entry))
                   }]
            (storage-area/set local-storage (clj->js r))
            (>! ch r)
            ))))
    ch))

(defn current-removal-attempt
  "NOTE: There should only be one item that's undergoing removal.
  Return nil if not found.
  Return URL if found.
  "
  []
  (let [local-storage (storage/get-local)]
    (go
      (let [[[items] error] (<! (storage-area/get local-storage))]
        (->> items
             js->clj
             (remove (fn [[k v]] (= k "license")))
             (filter (fn [[k v]]
                       (= "removing" (get v "status"))))
             first)
        ))
    ))

(defn fresh-new-victim []
  (let [local-storage (storage/get-local)
        ch (chan)]
    (go
      (let [[[items] error] (<! (storage-area/get local-storage))
            [victim-url victim-entry] (->> (or items '())
                                           js->clj
                                           (remove (fn [[k v]] (= k "license")))
                                           (filter (fn [[k v]]
                                                     (let [status (get v "status")]
                                                       (= "pending" status))))
                                           (sort-by (fn [[_ v]] (get v "idx")))
                                           first)
            _ (when-not (nil? victim-entry) (<! (update-storage victim-url "status" "removing")))
            victim (<! (current-removal-attempt))]
        (if (nil? victim)
          (close! ch)
          (>! ch victim))
        ))
    ch))

(defn next-victim []
  (let [;;local-storage (storage/get-local)
        ch (chan)]
    (go
      (let [victim (<! (current-removal-attempt))
            victim (if (empty? victim)
                     (<! (fresh-new-victim))
                     victim)
            ]
        (if (nil? victim)
          (close! ch)
          (>! ch victim))
        ))
    ch))

(defn store-license [{:keys [email key] :as license}]
  (let [local-storage (storage/get-local)]
    (go
      (storage-area/set local-storage (clj->js {"license" license}))
      )))

(defn get-license []
  (let [local-storage (storage/get-local)]
    (go
      (let [[[items] error] (<! (storage-area/get local-storage "license"))]
        (-> items js->clj (get "license"))
        ))))

(defn clear-victims! []
  (let [local-storage (storage/get-local)]
    (go
      (let [curr-license (<! (get-license))]
        (<! (storage-area/clear local-storage))
        (<! (store-license curr-license))
        )
      )))

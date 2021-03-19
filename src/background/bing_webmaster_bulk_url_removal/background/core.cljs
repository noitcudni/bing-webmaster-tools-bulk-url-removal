(ns bing-webmaster-bulk-url-removal.background.core
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [goog.string :as gstring]
            [goog.string.format]
            [cljs.core.async :refer [<! chan]]
            [chromex.logging :refer-macros [log info warn error group group-end]]
            [chromex.chrome-event-channel :refer [make-chrome-event-channel]]
            [chromex.protocols.chrome-port :refer [on-disconnect! post-message! get-sender]]
            [chromex.ext.tabs :as tabs]
            [chromex.ext.runtime :as runtime]
            [chromex.ext.browser-action :refer-macros [set-badge-text set-badge-background-color]]
            [bing-webmaster-bulk-url-removal.content-script.common :as common]
            [bing-webmaster-bulk-url-removal.background.storage :refer [next-victim clear-victims! store-victims! *DONE-FLAG*]]))

(def clients (atom []))

; -- clients manipulation ---------------------------------------------------------------------------------------------------

(defn add-client! [client]
  (log "BACKGROUND: client connected" (get-sender client))
  (swap! clients conj client))

(defn popup-predicate [client]
  (re-find #"popup.html" (-> client
                             get-sender
                             js->clj
                             (get "url"))))

(defn get-content-client []
  (->> @clients
       (filter (complement popup-predicate))
       first ;;this should only be one popup
       ))

(defn remove-client! [client]
  (log "BACKGROUND: client disconnected" (get-sender client))
  (let [remove-item (fn [coll item] (remove #(identical? item %) coll))]
    (swap! clients remove-item client)))

; -- client event loop ------------------------------------------------------------------------------------------------------
(defn fetch-next-victim [client]
  (go
    (let [[victim-url victim-entry] (<! (next-victim))
          _ (prn "BACKGROUND: victim-url: " victim-url)
          _ (prn "BACKGROUND: victim-entry: " victim-entry)]
      (cond (and (= victim-url "poison-pill") (= (get victim-entry "block-type") *DONE-FLAG*))
            (do (prn "DONE!!!")
                (js/alert "DONE with bulk url removals!"))

            (and victim-url victim-entry)
            (post-message! client
                           (common/marshall {:type :remove-url
                                             :victim victim-url
                                             :block-type (get victim-entry "block-type")
                                             :url-type (get victim-entry "url-type")
                                             })))
      )))

(defn run-client-message-loop! [client]
  (log "BACKGROUND: starting event loop for client:" (get-sender client))
  (go-loop []
    (when-some [message (<! client)]
      (let [{:keys [type] :as whole-edn} (common/unmarshall message)
            _ (prn "whole-edn: " whole-edn) ;;xxx
            ]
        (cond (= type :open-file-finder) (if-let [client (get-content-client)]
                                           (post-message! client (common/marshall {:type :open-file-finder}))
                                           (js/alert "Make sure you are on Google Search Console's Removals page.\n If you already are, please refresh the page and try again."))

              (= type :init-victims) (do
                                       (prn whole-edn)
                                       (<! (clear-victims!))
                                       (set-badge-text #js{"text" ""})
                                       (<! (store-victims! whole-edn))
                                       (post-message! (get-content-client) (common/marshall {:type :done-init-victims}))
                                       )
              (= type :next-victim) (<! (fetch-next-victim client))
              ))
      (recur))
    (log "BACKGROUND: leaving event loop for client:" (get-sender client))
    (remove-client! client)))

; -- event handlers ---------------------------------------------------------------------------------------------------------

(defn handle-client-connection! [client]
  (add-client! client)
  (run-client-message-loop! client))

(defn tell-clients-about-new-tab! []
  (doseq [client @clients]
    (post-message! client "a new tab was created")))

; -- main event loop --------------------------------------------------------------------------------------------------------

(defn process-chrome-event [event-num event]
  (log (gstring/format "BACKGROUND: got chrome event (%05d)" event-num) event)
  (let [[event-id event-args] event]
    (case event-id
      ::runtime/on-connect (apply handle-client-connection! event-args)
      ::tabs/on-created (tell-clients-about-new-tab!)
      nil)))

(defn run-chrome-event-loop! [chrome-event-channel]
  (log "BACKGROUND: starting main event loop...")
  (go-loop [event-num 1]
    (when-some [event (<! chrome-event-channel)]
      (process-chrome-event event-num event)
      (recur (inc event-num)))
    (log "BACKGROUND: leaving main event loop")))

(defn boot-chrome-event-loop! []
  (let [chrome-event-channel (make-chrome-event-channel (chan))]
    (tabs/tap-all-events chrome-event-channel)
    (runtime/tap-all-events chrome-event-channel)
    (run-chrome-event-loop! chrome-event-channel)))

; -- main entry point -------------------------------------------------------------------------------------------------------

(defn init! []
  (log "BACKGROUND: init")
  (boot-chrome-event-loop!))

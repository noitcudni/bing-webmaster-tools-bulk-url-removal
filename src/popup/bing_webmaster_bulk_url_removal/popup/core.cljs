(ns bing-webmaster-bulk-url-removal.popup.core
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [cljs.core.async :refer [<! >! put! chan] :as async]
            [chromex.logging :refer-macros [log info warn error group group-end]]
            [chromex.protocols.chrome-port :refer [post-message!]]
            [chromex.ext.browser-action :refer-macros [set-badge-text]]
            [reagent.core :as reagent :refer [atom]]
            [re-com.core :as recom]
            [testdouble.cljs.csv :as csv]
            [domina :refer [single-node nodes]]
            [domina.xpath :refer [xpath]]
            [bing-webmaster-bulk-url-removal.content-script.common :as common]
            [bing-webmaster-bulk-url-removal.background.storage :refer [clear-victims! print-victims get-bad-victims]]
            [chromex.ext.runtime :as runtime :refer-macros [connect]]))

(def upload-chan (chan 1 (map (fn [e]
                                (let [target (.-currentTarget e)
                                      file (-> target .-files (aget 0))]
                                  (set! (.-value target) "")
                                  file
                                  )))))

(def read-chan (chan 1 (map #(-> % .-target .-result js->clj))))

; -- a message loop ---------------------------------------------------------------------------------------------------------

(defn process-message! [message]
  (log "POPUP: got message:" message))

(defn run-message-loop! [message-channel]
  (log "POPUP: starting message loop...")
  (go-loop []
    (when-some [message (<! message-channel)]
      (process-message! message)
      (recur))
    (log "POPUP: leaving message loop")))


(defn connect-to-background-page! [background-port]
  (run-message-loop! background-port))

(defn current-page [background-port]
  (let []
    [recom/v-box
     :width "380px"
     :align :center
     :children [[:div {:style {:display "none"}}
                 [:input {:id "bulkCsvFileInput" :type "file"
                          :on-change (fn [e]
                                       ;; (reset! my-status :running)
                                       ;; (reset! cached-bad-victims-atom nil)
                                       (put! upload-chan e))}]]
                [recom/v-box
                 :align :start
                 :style {:padding "10px"}
                 :children [[recom/title :label "Instructions:" :level :level1]
                            [recom/label :label "- Go to Bing Webmaster Tools"]
                            [recom/label :label "- Select the proper domain from the dropdown on the left."]
                            [recom/label :label "- Select Configuration > Block URLs on the left pane."]
                            [recom/label :label "- Upload your csv file by clicking on the ''Submit CSV File Button. Check out the example csv below."]
                            [recom/hyperlink-href
                             :label "CSV Format Documentation"
                             :href "https://github.com/noitcudni/bing-webmaster-bulk-url-removal/#csv-format"
                             :target "_blank"]
                            [recom/hyperlink-href
                             :label "example 1"
                             :href "examples/most-common.csv"]
                            [recom/hyperlink-href
                             :label "example 2"
                             :href "examples/optional-columns.csv"]
                            ]
                 ]
                [recom/v-box
                 :gap "10px"
                 :children [[recom/button
                             :label "Submit CSV File"
                             :tooltip [recom/v-box
                                       :children [[recom/label :label "Tooltip goes here"]]
                                       ]
                             :style {:width "200px"
                                     :background-color "#007bff"
                                     :color "white"}
                             :on-click (fn [_]
                                         (post-message! background-port (common/marshall {:type :open-file-finder}))
                                         ;; (-> "//input[@id='bulkCsvFileInput']" xpath single-node .click)
                                         )]
                            [recom/button
                             :label "Clear cache"
                             :style {:width "200px"}
                             :on-click (fn [_]
                                         (clear-victims!)
                                         (set-badge-text #js{"text" ""})
                                         ;; (reset! cached-bad-victims-atom nil)
                                         )]
                            [recom/button
                             :label "View cache"
                             :tooltip [recom/v-box
                                       :children [[recom/label :label "Go to the chrome developer console"]
                                                  [recom/label :label "Press me to see debugging information"]
                                                  ]]
                             :style {:width "200px"}
                             :on-click (fn [_]
                                         (print-victims)
                                         (go
                                           (let [bad-victims (<! (get-bad-victims))]
                                             (prn "bad-victims: "  bad-victims)
                                             )))]
                            ]]
                [recom/gap :size "30px"]
                ]
     ]))

(defn mount-root [background-port]
  (reagent/render [current-page background-port] (.getElementById js/document "app")))


; -- main entry point -------------------------------------------------------------------------------------------------------
(defn init! []
  (let [_ (log "POPUP: init")
        background-port (runtime/connect)]
    (connect-to-background-page! background-port)

    ;; handle onload
    (go-loop []
      (let [reader (js/FileReader.)
            file (<! upload-chan)]
        (set! (.-onload reader) #(put! read-chan %))
        (.readAsText reader file)
        (recur)))

    ;; handle reading the file
    (go-loop []
      (let [file-content (<! read-chan)
            _ (prn "file-content: " (clojure.string/trim file-content))
            csv-data (->> (csv/read-csv (clojure.string/trim file-content))
                          ;; trim off random whitespaces
                          (map (fn [[url method url-type]]
                                 (->> [(clojure.string/trim url)
                                       (when method (clojure.string/trim method))
                                       (when url-type (clojure.string/trim url-type))]
                                      (filter (complement nil?))
                                      ))))]
        (post-message! background-port (common/marshall {:type :init-victims
                                                         :data csv-data
                                                         }))

        (recur)))

    (mount-root background-port)))

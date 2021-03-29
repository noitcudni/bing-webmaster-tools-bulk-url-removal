(ns bing-webmaster-bulk-url-removal.content-script.core
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [cljs.core.async :refer [<! >! chan put!] :as async]
            [cognitect.transit :as t]
            [cljs-http.client :as http]
            [clojure.string]
            [testdouble.cljs.csv :as csv]
            [domina.xpath :refer [xpath]]
            [domina :refer [single-node nodes append! prepend! set-styles!]]
            [chromex.logging :refer-macros [log info warn error group group-end]]
            [chromex.protocols.chrome-port :refer [post-message!]]
            [bing-webmaster-bulk-url-removal.content-script.common :as common]
            [chromex.ext.runtime :as runtime :refer-macros [connect]]))

(def upload-chan (chan 1 (map (fn [e]
                                (let [target (.-currentTarget e)
                                      file (-> target .-files (aget 0))]
                                  (set! (.-value target) "")
                                  file
                                  )))))

(def read-chan (chan 1 (map #(-> % .-target .-result js->clj))))

(defn sync-node-helper
  "This is unfortunate. alts! doens't close other channels"
  [dom-fn & xpath-strs]
  (go-loop []
    (let [n (->> xpath-strs
                 (map (fn [xpath-str]
                        (dom-fn (xpath xpath-str))
                        ))
                 (filter #(some? %))
                 first)]
      (if (nil? n)
        (do (<! (async/timeout 300))
            (prn "recurring.. for " xpath-strs);;xxx
            (recur))
        n)
      )))

(def sync-single-node (partial sync-node-helper single-node))
(def sync-nodes (partial sync-node-helper nodes))

; -- a message loop ---------------------------------------------------------------------------------------------------------
(defn enforce-language []
  ;; gear button
  ;; "//i[@data-icon-name='Settings']")

  ;; accordion
  ;; $x("//div[@class='accordionItemHeaderContent']//div[contains(text(), 'Display language')]")

  ;; the container that contains the language drop down
  ;; "//div[@class='accordionItemHeaderContent']/div[contains(text(), 'Display language')]/../../../../"
  ;; "//div[@class='contentInfo']/div"
  (go
    ;; click on the gear button
    (.click (<! (sync-single-node  "//i[@data-icon-name='Settings']")))
    ;; click on the accordion
    (.click (<! (sync-single-node "//div[@class='accordionItemHeaderContent']//div[contains(text(), 'Display language')]")))
    ;; TODO: can we get rid of this timeout?
    (<! (async/timeout 1300))
    ;; click on the dropdown
    (.click (<! (sync-single-node "//div[@class='contentInfo']/div/*[1]")))
    ;; select english
    (.click (<! (sync-single-node "//div[text()='English (United States)'][1]")))
    ))


(defn mount-root []
  (append! (xpath "//div[@id='root']") (str "<div id='status-display-container' style='z-index:100;position:absolute;top: 200px;right: 600px; display:none'>"
                                            "<div style='background-color:#e2e2e2'>Don't refresh when the extension is running</div>"
                                            "<div style='background-color:#e2e2e2'>An alert will pop up once everything is done.</div>"
                                            "<div id='status-display' style='max-width: 500px;max-height: 200px;overflow-x: auto;overflow-y: auto;'>"
                                            "</div>"
                                            "</div>"
                                            ))
  )

(defn exec-new-removal-request
  [url url-type block-type]
  ;; Add button
  (let [ch (chan)
        _ (prn ">> url-type: " url-type)
        _ (prn ">> block-type: " block-type)
        ]
   (go
     (cond (and (not= url-type  "page") (not= url-type "directory"))
           (>! ch :erroneous-url-type)
           (and (not= block-type "url-and-cache") (not= block-type "cache-only"))
           (>! ch :erroneous-block-type)
           :else
           ;; NOTE: try hitting the ajax directly
           ;; https://www.bing.com/webmasters/api/configure/blockurl/add
           ;; {"siteUrl":"siteUrl","url":{"Value":"url-goes-here"},"urlType":"Page","blockType":"CacheOnly"}
           ;; TODO: implement some type of feedback mechanism
           (do
             (let [site-name-dom (<! (sync-single-node "//div[@class='userSiteName']"))
                  site-name  (.-textContent site-name-dom)
                  url-type-param (if (= url-type "page") "Page" "Directory")
                  block-type-param (if (= block-type "url-and-cache") "FullRemoval" "CacheOnly")
                  {success? :success
                   {body-status :Status} :body} (<! (http/post "https://www.bing.com/webmasters/api/configure/blockurl/add"
                                                               {:json-params
                                                                {
                                                                 :siteUrl (str "https://" site-name)
                                                                 :urlType url-type-param
                                                                 :blockType block-type-param
                                                                 :url {:Value url}}
                                                                }))
                  ]

              (if (and (= body-status "SUCCESSFUL") success?)
                (>! ch :success)
                (>! ch :failed)
                ))

             ;; (.click (<! (sync-single-node "//div[@class='floatRight']//button//span[contains(text(), 'Add URL to block')]")))
             ;; ;; enter the url
             ;; (let [n (<! (sync-single-node "//input[@aria-label='Enter URL']"))]
             ;;   (domina/set-value! n url)
             ;;   (let [evt (.createEvent js/document "HTMLEvents")
             ;;         _ (.initEvent evt "change" false true)]
             ;;     (.dispatchEvent n evt)
             ;;     ))
             )

           ;; (.click (<! (sync-single-node "//div[@class='floatRight']//button//span[contains(text(), 'Add URL to block')]")))
           ;; ;; enter the url
           ;; (let [n (<! (sync-single-node "//input[@aria-label='Enter URL']"))]
           ;;   (set! (.-value n) url)
           ;;   #_(domina/set-value! n url))

           ;; ;; check on the right url-type radio
           ;; (let [page-radio (<! (sync-single-node "//span[text()='Page']/../../input"))
           ;;       directory-radio (<! (sync-single-node "//span[text()='Directory']/../../input"))]
           ;;  (if (= url-type "page")
           ;;    (do (set! (.-checked page-radio) true)
           ;;        (set! (.-checked page-radio) false))
           ;;    (do (set! (.-checked directory-radio) true)
           ;;        (set! (.-checked directory-radio) false))
           ;;    ))


           ))
   ch))

(defn update-ui
  [url status]
  (set-styles! (xpath "//div[@id='status-display-container']")
               {:z-index 100
                :position "absolute"
                :top  "200px"
                :right "600px"
                :display "block"})

  (append! (xpath "//div[@id='status-display']")
           (str "<div style='clear:both'>"
                (if (= status "success")
                  "<div style='float: left; background-color: #9ccc9c; padding: 2px; margin: 1px'>"
                  "<div style='float: left; background-color: #ff8b8e; padding: 2px; margin: 1px'>")
                status
                ": </div>"

                "<div style='float: left; background-color: #F5FFFA; padding: 2px; margin: 1px'>"
                url
                "</div>"

                "</div>")
           ;; (str "<div>"  status " : " url "</div>")
           ))

(defn process-message! [chan message]
  (let [{:keys [type] :as whole-msg} (common/unmarshall message)]
    (cond (= type :open-file-finder) (.click (-> "//input[@id='bulkCsvFileInput']" xpath single-node))
          (= type :done-init-victims) (go
                                        (prn "done-init-victim")
                                        ;; (<! (enforce-language))
                                        (post-message! chan (common/marshall {:type :next-victim})))
          (= type :remove-url) (do (prn ">> handling :remove-url")
                                   (go
                                     (let [{:keys [victim block-type url-type]} whole-msg
                                           _ (prn "whole-msg: " whole-msg)
                                           request-status (<! (exec-new-removal-request victim url-type block-type))
                                           ]
                                       (if (= :success request-status)
                                         (do
                                           (update-ui victim "success")
                                           (post-message! chan (common/marshall {:type :success
                                                                                 :url victim})))
                                         (do
                                           (update-ui victim "error")
                                           (post-message! chan (common/marshall {:type :skip-error
                                                                                 :reason request-status
                                                                                 :url victim
                                                                                 })))
                                         )
                                       ))
                                   )
          )
    ))





; -- main entry point -------------------------------------------------------------------------------------------------------

(defn init! []
  (let [background-port (runtime/connect)]

    (go
      (common/connect-to-background-page! background-port process-message!))

    ;; handle onload
    (go-loop []
      (let [reader (js/FileReader.)
            file (<! upload-chan)]
        (set! (.-onload reader) #(put! read-chan %))
        (.readAsText reader file)
        (recur)))

    ;; handle read the file
    (go-loop []
      (let [file-content (<! read-chan)
            _ (prn "file-content: " (clojure.string/trim file-content))
            file-content (->> (-> file-content
                                  ;; split by ^M character
                                  (clojure.string/split #"\r"))
                              (map clojure.string/trim)
                              (remove (fn [x]
                                        (zero? (count x))))
                              (clojure.string/join "\n"))
            _ (prn "sanitized file-content: " (clojure.string/trim file-content))
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

    (.addEventListener
     js/window
     "DOMContentLoaded"
     (fn []
       (.log js/console "DOMContentLoaded callback")
       (append! (xpath "//body") "<div style='dislay:none'><input id='bulkCsvFileInput' type='file' /></div>")
       (set! (.. (-> "//input[@id='bulkCsvFileInput']" xpath single-node) -onchange)
             (fn [e]
               (prn ">> got the file")
               (put! upload-chan e)))
       (mount-root)
       ))

    ))

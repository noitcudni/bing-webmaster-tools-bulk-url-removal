(ns bing-webmaster-bulk-url-removal.content-script.core
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [cljs.core.async :refer [<! >! chan put!] :as async]
            [cognitect.transit :as t]
            [clojure.string]
            [testdouble.cljs.csv :as csv]
            [domina.xpath :refer [xpath]]
            [domina :refer [single-node nodes append!]]
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

; -- a message loop ---------------------------------------------------------------------------------------------------------

(defn process-message! [chan message]
  (let [{:keys [type] :as whole-msg} (common/unmarshall message)]
    (cond (= type :open-file-finder) (.click (-> "//input[@id='bulkCsvFileInput']" xpath single-node))
          (= type :done-init-victims) (post-message! chan (common/marshall {:type :next-victim}))
          )
    ))


; -- a simple page analysis  ------------------------------------------------------------------------------------------------


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
               (put! upload-chan e))
             ))
     )))

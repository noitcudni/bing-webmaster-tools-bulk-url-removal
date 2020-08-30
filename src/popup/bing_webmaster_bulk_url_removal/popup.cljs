(ns bing-webmaster-bulk-url-removal.popup
  (:require-macros [chromex.support :refer [runonce]])
  (:require [bing-webmaster-bulk-url-removal.popup.core :as core]))

(runonce
  (core/init!))

(ns bing-webmaster-bulk-url-removal.content-script
  (:require-macros [chromex.support :refer [runonce]])
  (:require [bing-webmaster-bulk-url-removal.content-script.core :as core]))

(runonce
  (core/init!))

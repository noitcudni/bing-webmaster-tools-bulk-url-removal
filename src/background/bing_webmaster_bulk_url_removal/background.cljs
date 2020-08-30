(ns bing-webmaster-bulk-url-removal.background
  (:require-macros [chromex.support :refer [runonce]])
  (:require [bing-webmaster-bulk-url-removal.background.core :as core]))

(runonce
  (core/init!))

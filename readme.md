# Bing Webmaster Tools - Bulk Url Removal Chrome Extension

## Install from Google Webstore
https://chrome.google.com/webstore/detail/bing-webmaster-tools-bulk/knhcaipgfilkpgkejhmjbgklpalkaahi

## Installation
1. Install Java.
2. Install [leiningen](http://leiningen.org).
3. Either `git clone git@github.com:noitcudni/bing-webmaster-tools-bulk-url-removal.git` or download the zip file from [github](https://github.com/noitcudni/bing-webmaster-tools-bulk-url-removal/archive/master.zip) and unzip it.
4. `cd` into the project root directory.
  * Run in the terminal
  ```bash
  lein release && lein package
  ```
5. Go to **chrome://extensions/** and turn on Developer mode.
6. Click on **Load unpacked extension . . .** and load the extension.

## Usage
1. Create a list of urls to be removed and store them in a file. See below for format.
2. Go to Bing Webmaster Tools (https://www.bing.com/webmasters)
4. Click on Configration->Block URLs on the left panel.
5. Open up the extension popup by clicking on the blue trash can icon.
6. Click on the "Submit CSV File" button to upload your csv file. It will start running automatically.

## Local Storage
The extension uses chrome's local storage to keep track of state for each URL. You can use the **Clear cache** button to clear your local storage content to start anew.

## CSV Format
url (required), block-type (optional: `url-and-cache`, `cache-only`), url-type (optional: `page`, `directory`) <br />
By default `block-type` is set to `url-and-cache`, and `url-type` is set to `page`

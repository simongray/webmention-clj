(ns ^:no-doc dk.simongray.webmention.discovery
  "The URLs that the Link headers of a response name by a relation type,
  e.g. the endpoint of Webmention. Those of the elements of an HTML page are
  html/links of html-pieces.

  Nothing here is particular to Webmention. IndieAuth, Micropub and WebSub
  discover their endpoints in the same way, by other relation types, so
  this could move to a library that they share."
  (:require [clojure.string :as str]
            [dk.simongray.wary-fetch.headers :as headers]
            [dk.simongray.whatwg-url :as url]))

;; RFC 8288, section 3.3: a set of types separated by whitespace, in any
;; case
(defn relation?
  "Whether `rel` is one of the relation types in `s`, a list of them
  separated by whitespace, in any case."
  [rel s]
  (boolean (some #{rel} (str/split (str/lower-case (str s))
                                   #"[\t\n\f\r ]+"))))

;; RFC 8288, section 3
(defn header-urls
  "The URLs of the links with the relation type `rel` in the Link headers of
  the response `headers`, in their order, resolved against `base`, the URL
  of the response."
  [headers base rel]
  (let [absolute (url/resolver base)]
    (keep (fn [link]
            (when (relation? rel (:rel link))
              (absolute (:href link))))
          (headers/links headers))))

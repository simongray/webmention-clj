(ns ^:no-doc dk.simongray.webmention.discovery
  "The URLs that a response names by a relation type, e.g. the endpoint of
  Webmention: in its Link headers, and in the elements of an HTML page.

  Nothing here is particular to Webmention. IndieAuth, Micropub and WebSub
  discover their endpoints in the same way, by other relation types and in
  other elements, so this could move to a library that they share."
  (:require [clojure.string :as str]
            [dk.simongray.html-pieces.url :as url]
            [dk.simongray.wary-fetch.headers :as headers]))

;; RFC 8288, section 3.3; HTML Standard, section 4.6.7: a set of types
;; separated by whitespace, in any case
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

;; HTML Standard, sections 4.2.4 and 4.6.1: a link element or a hyperlink
;; without an href names nothing
(defn page-urls
  "The URLs that the `elements` of a page whose tag is one of `tags` name
  with the relation type `rel`, in document order, resolved against
  `base`."
  [elements base rel tags]
  (let [absolute (url/resolver base)]
    (keep (fn [[tag attrs]]
            (when (and (contains? tags tag)
                       (:href attrs)
                       (relation? rel (:rel attrs)))
              (absolute (:href attrs))))
          elements)))

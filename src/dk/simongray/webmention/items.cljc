(ns ^:no-doc dk.simongray.webmention.items
  "What a page shows of the items of a page's microformats, as
  microformats-clj reads them: their http and https URLs, an h-card as a
  map, and the answer of an RSVP."
  (:require [clojure.string :as str]
            [dk.simongray.microformats :as mf]
            [dk.simongray.wary-fetch.url :as url]))

(defn typed?
  "Whether the microformat `item` is of the type `t`, e.g. h-card."
  [t item]
  (boolean (some #{t} (:type item))))

(defn urls
  "The http and https URLs that the property `k` of the `item` names, as
  mf/urls gives them, since a page links them: each URL, and the url of
  each embedded item, e.g. of an h-cite."
  [item k]
  (filter url/web-url? (mf/urls item k)))

(defn card
  "The h-card `item` as a map of its :name, :url and :photo, those it has.
  A URL that isn't http or https is left out, since a page links it."
  [item]
  (let [web #(when (url/web-url? %) %)]
    (not-empty (into {} (filter val)
                     {:name  (mf/property item :name)
                      :url   (web (mf/property item :url))
                      :photo (web (mf/property item :photo))}))))

(def rsvp-values
  "The answers of an RSVP."
  #{"yes" "no" "maybe" "interested"})

(defn rsvp
  "The answer of the RSVP `item`, in lower case, or nil."
  [item]
  (some-> (mf/property item :rsvp) str/trim str/lower-case rsvp-values))

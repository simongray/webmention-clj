(ns ^:no-doc dk.simongray.webmention.items
  "The items of a page's microformats, as microformats-clj parses them, and
  the values of their properties."
  (:require [clojure.string :as str]
            [dk.simongray.wary-fetch.url :as url]
            [dk.simongray.webmention.source :as source]))

(defn typed?
  "Whether the microformat `item` is of the type `t`, e.g. h-card."
  [t item]
  (boolean (some #{t} (:type item))))

(defn value
  "The plain value of the property value `v`: a string as it is, else the
  :value of an embedded item, an e- property or an img with alt text."
  [v]
  (if (map? v) (:value v) v))

(defn property
  "The first plain value of the property `k` of the `item` that isn't
  empty, or nil."
  [item k]
  (some #(not-empty (value %)) (get-in item [:properties k])))

(defn urls
  "The URLs that the values of the property `k` of the `item` name,
  normalized: each URL, and the url of each embedded item, e.g. of an
  h-cite. A value that isn't a valid URL names none."
  [item k]
  (for [v     (get-in item [:properties k])
        u     (if (map? v)
                (cons (:value v) (get-in v [:properties :url]))
                [v])
        :let  [n (when (string? u) (source/normalized u))]
        :when (url/web-url? n)]
    n))

(defn all
  "Every microformat in the parsed `mf2`, at any depth: each item at the
  top of the page, then those in its properties and its children."
  [mf2]
  (let [inner (fn [{:keys [properties children]}]
                (concat (filter :type (mapcat val properties)) children))]
    (mapcat #(tree-seq map? inner %) (:items mf2))))

(defn card
  "The h-card `item` as a map of its :name, :url and :photo, those it has.
  A URL that isn't http or https is left out, since a page links it."
  [item]
  (let [web #(when (url/web-url? %) %)]
    (not-empty (into {} (filter val) {:name  (property item :name)
                                      :url   (web (property item :url))
                                      :photo (web (property item :photo))}))))

(def rsvp-values
  "The answers of an RSVP."
  #{"yes" "no" "maybe" "interested"})

(defn rsvp
  "The answer of the RSVP `item`, in lower case, or nil."
  [item]
  (some-> (property item :rsvp) str/trim str/lower-case rsvp-values))

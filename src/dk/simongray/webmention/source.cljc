(ns ^:no-doc dk.simongray.webmention.source
  "The document at the source of a Webmention: what kind it is, whether it
  links the target, and whether it says that it's gone."
  (:require [clojure.string :as str]
            [dk.simongray.html-pieces :as html]
            [dk.simongray.wary-fetch.headers :as headers]
            [dk.simongray.wary-fetch.json :as json]
            [dk.simongray.webmention.page :as page]
            [dk.simongray.whatwg-url :as url]))

;; RFC 9110, section 8.3: a document without a media type can be
;; examined; RFC 6839, section 3.1: the +json and +xml suffixes
(defn kind
  "The kind of the document in the `response`, :html, :json or :text, or
  nil for one whose links can't be read. Without a media type, a document
  with markup is HTML."
  [{:keys [headers body] :as response}]
  (let [media-type (headers/media-type (headers/header headers "content-type"))]
    (cond
      (#{"text/html" "application/xhtml+xml"} media-type)
      :html

      (some->> media-type (re-find #"^application/([^/]+\+)?json$"))
      :json

      (some->> media-type (re-find #"^text/|[/+]xml$"))
      :text

      (some? media-type)
      nil

      (html/markup? (str body))
      :html

      :else
      :text)))

(defn normalized
  "The URL `s` normalized as a browser normalizes it, e.g. with its domain
  in lower case and without a default port, or `s` itself when it isn't a
  valid URL."
  [s]
  (or (url/resolve nil s) s))

(defn without-fragment
  "The URL `s` without its fragment."
  [s]
  (first (str/split s #"#" 2)))

(defn same-page?
  "Whether the URLs `a` and `b` are of the same page, once they're
  normalized and without their fragments."
  [a b]
  (= (without-fragment (normalized a)) (without-fragment (normalized b))))

;; Webmention 3.2.2, and issue 91 of the spec for the attributes:
;; https://github.com/w3c/webmention/issues/91
(defn html-links?
  "Whether the attributes of the `elements` of a page, resolved against
  `base`, have the URL `target` as a value."
  [elements base target]
  (let [t (normalized target)]
    (boolean (some #{t} (page/attribute-urls elements base)))))

(defn- json-values
  "The values of the JSON `s` at any depth, the keys of its objects left
  out, or nil when `s` isn't JSON or is nested too deep."
  [s]
  (some->> (json/read-json s {:key-fn identity})
           (tree-seq coll? #(if (map? %) (vals %) (seq %)))))

;; Webmention 3.2.2
(defn json-links?
  "Whether a value of the JSON `s` is the URL `target`, or else, when `s`
  isn't JSON, whether its text has the URL in it."
  [s target]
  (if-let [values (json-values s)]
    (let [t (normalized target)]
      (boolean (some #(and (string? %) (= t (normalized %))) values)))
    (str/includes? s target)))

;; https://indieweb.org/meta_http-equiv_status, which webmention.rocks
;; takes for a deletion too
(defn status
  "The HTTP status that a meta element with http-equiv=\"Status\" among the
  `elements` of a page gives, e.g. 410 for a post that was deleted from a
  static site, or nil."
  [elements]
  (some (fn [[tag {:keys [http-equiv content]}]]
          (when (and (= :meta tag)
                     (= "status" (some-> http-equiv str/trim str/lower-case)))
            (some->> content (re-find #"^\s*(\d{3})") second parse-long)))
        elements))

(ns ^:no-doc dk.simongray.webmention.page
  "The HTML of a whole page, as html-pieces parses it: its elements in
  document order, the URL that its relative URLs resolve against, the URLs
  in its attributes and its title."
  (:require [clojure.string :as str]
            [dk.simongray.html-pieces :as html]
            [dk.simongray.whatwg-url :as url]))

(defn- candidates
  "The URLs in the value `v` of the attribute `k`: one, or a list of them
  for the attributes that hold several."
  [k v]
  (cond
    ;; HTML Standard, section 4.8.4.2: candidates separated by commas, each
    ;; a URL and then perhaps a descriptor
    (= :srcset k)
    (keep #(re-find #"[^\t\n\f\r ]+" %) (str/split v #","))

    (contains? html/url-list-attributes k)
    (str/split (str/trim v) #"[\t\n\f\r ]+")

    :else
    [v]))

(defn attribute-urls
  "Every URL in the attributes of the `elements` whose value is a URL, as
  html-pieces names them, resolved against `base`, e.g. the href of an a
  and the src of an img."
  [elements base]
  (let [url?     (into html/url-attributes html/url-list-attributes)
        absolute (url/resolver base)]
    (for [[_ attrs] elements
          [k v]     attrs
          :when     (and (url? k) (string? v))
          s         (candidates k v)
          :let      [u (absolute s)]
          :when     u]
      u)))

(defn title
  "The text of the first title element among the `elements` of a page,
  without whitespace at its ends, or nil."
  [elements]
  (some (fn [[tag _ & children]]
          (when (= :title tag)
            (not-empty (str/trim (apply str (filter string? children))))))
        elements))

;; HTML Standard, sections 4.2.3 and 4.12.3: the first base element with an
;; href, and no content of a template
(defn parse
  "The elements of the HTML or Hiccup `x` of the page at `url`, and the base
  URL of their relative URLs, as a map of :nodes, :elements and :base."
  [x url]
  (let [nodes (html/parse x)]
    {:nodes    nodes
     :elements (html/elements nodes)
     :base     (html/base-url nodes {:base-url url})}))

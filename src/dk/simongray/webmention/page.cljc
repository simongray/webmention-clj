(ns ^:no-doc dk.simongray.webmention.page
  "The HTML of a whole page, as html-pieces parses it: its elements in
  document order, the URL that its relative URLs resolve against, and the
  URLs in its attributes."
  (:require [clojure.string :as str]
            [dk.simongray.html-pieces :as html]
            [dk.simongray.html-pieces.url :as url]))

;; HTML Standard, section 4.12.3: the content of a template isn't part of
;; the document
(defn elements
  "Every element of the Hiccup `nodes` of a page, in document order, but
  those inside a template."
  [nodes]
  (let [branch? #(and (vector? %) (not= :template (first %)))]
    (->> nodes
         (mapcat #(tree-seq branch? (partial drop 2) %))
         (filter vector?))))

;; HTML Standard, section 4.2.3: the first base element with an href
(defn base-url
  "The URL that relative URLs among the `elements` of the page at `base`
  resolve against: the href of its first base element, or else `base`."
  [elements base]
  (or (some (fn [[tag {:keys [href]}]]
              (when (and (= :base tag) href)
                (url/resolve base href)))
            elements)
      base))

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

(defn hyperlinks
  "The URLs that the a and area elements among the `elements` link to,
  resolved against `base`, in document order."
  [elements base]
  (let [absolute (url/resolver base)]
    ;; HTML Standard, section 4.6.1
    (keep (fn [[tag {:keys [href]}]]
            (when (and (#{:a :area} tag) href)
              (absolute href)))
          elements)))

(defn title
  "The text of the first title element among the `elements` of a page,
  without whitespace at its ends, or nil."
  [elements]
  (some (fn [[tag _ & children]]
          (when (= :title tag)
            (not-empty (str/trim (apply str (filter string? children))))))
        elements))

(defn parse
  "The elements of the HTML or Hiccup `x` of the page at `url`, and the base
  URL of their relative URLs, as a map of :nodes, :elements and :base."
  [x url]
  (let [nodes    (html/parse x)
        elements (elements nodes)]
    {:nodes    nodes
     :elements elements
     :base     (base-url elements url)}))

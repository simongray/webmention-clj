(ns ^:no-doc dk.simongray.webmention.content
  "The content and the name of an h-entry as a page shows them: the
  content sanitized, as Hiccup and as text, perhaps cut to an excerpt, and
  the name of an article."
  (:require [clojure.string :as str]
            [dk.simongray.html-pieces :as html]
            [dk.simongray.microformats :as mf]))

;;; The content

(def option-keys
  "The options of mention/summary that aren't those of html-pieces."
  [:max-length :any-name? :page-title?])

(defn- cut
  "The text `s` cut back to the end of a word within `n` characters, which
  is empty when its first word is longer. A word is never cut, so neither
  is a character of two UTF-16 units, e.g. an emoji."
  [s n]
  (let [head (subs s 0 n)]
    (if (re-find #"^\s" (subs s n))
      (str/trimr head)
      (str/replace head #"(^|\s+)\S*$" ""))))

(defn- blank?
  "Whether the Hiccup `nodes` have no text but whitespace."
  [nodes]
  (every? #(if (string? %) (str/blank? %) (blank? (drop 2 %))) nodes))

(defn- cut-nodes
  "The Hiccup `nodes` with `n` characters of text at most, in whole words,
  as a pair of the nodes and the number of characters left, which is
  negative once a text has been cut. Whitespace alone doesn't count, and
  an element whose text is cut away is left out."
  [nodes n]
  (reduce (fn [[kept left] node]
            (cond
              (neg? left)
              (reduced [kept left])

              (vector? node)
              (let [[tag attrs & children] node
                    [kids left]            (cut-nodes children left)]
                [(cond-> kept
                   (not (and (neg? left) (blank? kids)))
                   (conj (into [tag attrs] kids)))
                 left])

              (or (str/blank? node) (<= (count node) left))
              [(conj kept node) (cond-> left
                                  (not (str/blank? node)) (- (count node)))]

              :else
              (let [s (cut node left)]
                [(cond-> kept (seq s) (conj s)) -1])))
          [[] n]
          nodes))

(defn- ellipsis
  "The Hiccup `nodes` with an ellipsis after their last text."
  [nodes]
  (if-let [i (last (keep-indexed #(when-not (blank? [%2]) %1) nodes))]
    (update nodes i (fn [node]
                      (if (string? node)
                        (str (str/trimr node) "…")
                        (let [[tag attrs & children] node]
                          (into [tag attrs] (ellipsis (vec children)))))))
    (conj nodes "…")))

(defn- excerpt
  "The Hiccup `nodes` cut back to `n` characters of text, in whole words,
  with an ellipsis after the last text when some was cut."
  [nodes n]
  (let [[kept left] (cut-nodes nodes n)]
    (seq (cond-> kept (neg? left) ellipsis))))

;; Webmention 4.1: what's shown of a source is sanitized
(defn sanitized
  "The content of the `item`, or else its summary, by `opts`, as a map of
  its :text and its :hiccup, sanitized by html-pieces with the options in
  `opts`, with its URLs resolved against `base`, and its text cut back to
  the :max-length of `opts`. Nil when it has neither."
  [item base {:keys [max-length] :as opts}]
  (when-let [v (some #(first (get-in item [:properties %]))
                     [:content :summary])]
    (let [x      (if (map? v) (:hiccup v (:value v)) v)
          ;; the text of a p- property is text, even when it holds a <
          hiccup (cond-> (html/hiccup x (merge {:base-url    base
                                                :shorthand?  false
                                                :plain-text? (string? x)}
                                               (apply dissoc opts option-keys)))
                   max-length (excerpt max-length))
          text   (html/text hiccup)]
      (when-not (str/blank? text)
        {:text text :hiccup hiccup}))))

;;; The name

(defn- squeezed
  "The text `s` without whitespace at its ends, and with a space for each
  run of whitespace in it."
  [s]
  (some-> s str/trim (str/replace #"\s+" " ")))

;; Post Type Discovery 5: an article has a name that isn't the start of its
;; content, and a note has none
(defn- title
  "The name of the `item` when it's a title rather than the start of its
  content, or nil."
  [item]
  (let [n    (squeezed (mf/property item :name))
        text (squeezed (or (mf/property item :content)
                           (mf/property item :summary)))]
    (when (and (seq n) (seq text) (not (str/starts-with? text n)))
      n)))

(defn named
  "The name of the `entry` on the page whose title element has the text
  `page-title`, by the :any-name? and :page-title? of `opts`, or nil."
  [entry page-title {:keys [any-name? page-title?] :as opts}]
  (or (if any-name?
        (squeezed (mf/property entry :name))
        (title entry))
      (when page-title?
        page-title)))

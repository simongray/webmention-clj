(ns ^:no-doc dk.simongray.webmention.authorship
  "The author of an h-entry, by the authorship algorithm at
  https://indieweb.org/authorship-spec, and the h-cards that can represent
  a page, by https://microformats.org/wiki/representative-h-card-parsing."
  (:require [dk.simongray.microformats :as mf]
            [dk.simongray.wary-fetch.url :as url]
            [dk.simongray.webmention.items :as items]
            [dk.simongray.webmention.source :as source]))

(defn- feed-of
  "The h-feed at the top of the parsed `mf2` with the `entry` among its
  children, or nil."
  [mf2 entry]
  (some #(when (and (items/typed? "h-feed" %) (some #{entry} (:children %)))
           %)
        (:items mf2)))

;; the authorship algorithm, steps 2 to 4
(defn- author-of
  "The author that `v`, the value of an author property, names, as a map
  of the :card of an h-card or of a name, or of the URL of the author's
  :page."
  [v]
  (let [s (items/value v)]
    (cond
      (and (map? v) (items/typed? "h-card" v)) {:card (items/card v)}
      (url/web-url? s)                         {:page s}
      (not-empty s)                            {:card {:name s}})))

;; the authorship algorithm, steps 1 to 6
(defn author
  "The author that the page at `url` with the parsed `mf2` names for its
  `entry`, as a map of the :card of an h-card or of a name, or of the URL
  of the author's :page, or nil."
  [mf2 url entry]
  (let [author     #(first (get-in % [:properties :author]))
        permalink? (some #{(source/normalized url)} (items/urls entry :url))
        rel-author (first (get-in mf2 [:rels "author"]))]
    (or (some-> (or (author entry) (author (feed-of mf2 entry))) author-of)
        ;; a rel=author link of the page, for older pages
        (when (and permalink? (url/web-url? rel-author))
          {:page rel-author}))))

(defn cards
  "The h-cards in the parsed `mf2` of the page at `url` that can represent
  it, by the steps of representative h-card parsing, in their order: the
  first whose url and uid are the URL of the page, the first whose url is
  that of a rel=me link of the page, and the only h-card, if its url is
  that of the page."
  [mf2 url]
  (let [page   (source/normalized url)
        all    (filter #(items/typed? "h-card" %) (items/all mf2))
        me     (set (map source/normalized (get-in mf2 [:rels "me"])))
        named? (fn [k item] (some #{page} (items/urls item k)))]
    [(first (filter #(and (named? :url %) (named? :uid %)) all))
     (first (filter #(some me (items/urls % :url)) all))
     (when (= 1 (count all))
       (first (filter #(named? :url %) all)))]))

(defn page-card
  "The first h-card in the parsed `mf2` whose url is `page`, as items/card
  gives it, or nil."
  [mf2 page]
  (let [p (source/normalized page)]
    (some->> (items/all mf2)
             (filter #(and (items/typed? "h-card" %)
                           (some #{p} (items/urls % :url))))
             first
             items/card)))

;; the authorship algorithm, step 7, which takes the first two steps of
;; representative h-card parsing alone
(defn fetched-card
  "The h-card that represents the author of the HTML page in the
  `response`, as items/card gives it, or nil."
  [{:keys [status url body] :as response}]
  (when (and (<= 200 status 299) (= :html (source/kind response)))
    (some-> (some identity (take 2 (cards (mf/parse (str body) url) url)))
            items/card)))

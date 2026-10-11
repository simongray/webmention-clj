(ns dk.simongray.webmention.mention
  "What a receiver shows of a mention, read from the microformats of its
  source: its kind, its author and its content.

  The kind follows the Response Type Algorithm of Post Type Discovery, the
  W3C Note of 18 January 2018 at https://www.w3.org/TR/post-type-discovery/.
  The author follows the authorship algorithm at
  https://indieweb.org/authorship-spec, and the h-card of a page follows
  https://microformats.org/wiki/representative-h-card-parsing."
  (:require [clojure.string :as str]
            [dk.simongray.html-pieces :as html]
            [dk.simongray.microformats :as mf]
            [dk.simongray.wary-fetch :as http]
            [dk.simongray.wary-fetch.async :as async]
            [dk.simongray.wary-fetch.url :as url]
            [dk.simongray.webmention :as webmention]
            [dk.simongray.webmention.source :as source]))

(defn- typed?
  "Whether the microformat `item` is of the type `t`, e.g. h-card."
  [t item]
  (boolean (some #{t} (:type item))))

(defn- value
  "The plain value of the property value `v`: a string as it is, else the
  :value of an embedded item, an e- property or an img with alt text."
  [v]
  (if (map? v) (:value v) v))

(defn- property
  "The first plain value of the property `k` of the `item` that isn't
  empty, or nil."
  [item k]
  (some #(not-empty (value %)) (get-in item [:properties k])))

(defn- urls
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

(defn items
  "Every microformat in the parsed `mf2`, at any depth: each item at the
  top of the page, then those in its properties and its children."
  [mf2]
  (let [inner (fn [{:keys [properties children]}]
                (concat (filter :type (mapcat val properties)) children))]
    (mapcat #(tree-seq map? inner %) (:items mf2))))

(defn- compact
  "The map `m` without its nil values."
  [m]
  (into {} (remove (comp nil? val)) m))

(defn card
  "The h-card `item` as a map of its :name, :url and :photo, those it has.
  A URL that isn't http or https is left out, since a page links it."
  [item]
  (let [web #(when (url/web-url? %) %)]
    (not-empty (compact {:name  (property item :name)
                         :url   (web (property item :url))
                         :photo (web (property item :photo))}))))

;; Webmention 3.2.4 calls it the primary object of the source, e.g. its
;; h-entry, and leaves it at that
(defn entry
  "The h-entry that the page at `url` with the parsed `mf2` is about: the
  one whose url is that of the page, or else the first at the top of the
  page or in a feed there. Nil when it has none."
  [mf2 url]
  (let [page    (source/normalized url)
        top     (mapcat #(if (typed? "h-feed" %) (:children %) [%])
                        (:items mf2))
        entries (filter #(typed? "h-entry" %) top)]
    (or (some #(when (some #{page} (urls % :url)) %) entries)
        (first entries))))

;;; Kind

(def rsvp-values
  "The answers of an RSVP."
  #{"yes" "no" "maybe" "interested"})

(defn- rsvp
  "The answer of the RSVP `item`, in lower case, or nil."
  [item]
  (some-> (property item :rsvp) str/trim str/lower-case rsvp-values))

;; Post Type Discovery 4, with bookmark-of after in-reply-to as a choice of
;; our own: the Note leaves bookmarks to a later version, and a bookmark
;; that's a reply too is a reply, as indieblog has it
(def response-properties
  "The kinds of response and the property by which a response is of each,
  in the order that they count."
  [[:repost :repost-of]
   [:like :like-of]
   [:reply :in-reply-to]
   [:bookmark :bookmark-of]])

(defn kind
  "The kind of response that the h-entry `entry` is to `target`: :rsvp,
  :repost, :like, :reply, :bookmark, or else :mention.

  A property counts when one of its values is the target, as a URL or as
  the url of an embedded h-cite, so that a reply to another page that
  mentions the target is a mention of it. An RSVP is a reply to the
  target with an rsvp of yes, no, maybe or interested. Without a target,
  any URL counts."
  ([entry]
   (kind entry nil))
  ([entry target]
   (let [t   (some-> target source/normalized)
         to? (fn [k]
               (let [us (urls entry k)]
                 (if t (some #{t} us) (seq us))))]
     (or (when (and (rsvp entry) (or (nil? t) (to? :in-reply-to)))
           :rsvp)
         (some (fn [[response k]] (when (to? k) response))
               response-properties)
         :mention))))

;;; Author

(defn- feed-of
  "The h-feed at the top of the parsed `mf2` with the `entry` among its
  children, or nil."
  [mf2 entry]
  (some #(when (and (typed? "h-feed" %) (some #{entry} (:children %))) %)
        (:items mf2)))

;; the authorship algorithm, steps 2 to 4
(defn- author-of
  "The author that `v`, the value of an author property, names, as a map
  of the :card of an h-card or of a name, or of the URL of the author's
  :page."
  [v]
  (let [s (value v)]
    (cond
      (and (map? v) (typed? "h-card" v)) {:card (card v)}
      (url/web-url? s)                   {:page s}
      (not-empty s)                      {:card {:name s}})))

;; the authorship algorithm, steps 1 to 6
(defn- named-author
  "The author that the page at `url` with the parsed `mf2` names for its
  `entry`, as a map of the :card of an h-card or of a name, or of the URL
  of the author's :page, or nil."
  [mf2 url entry]
  (let [author     #(first (get-in % [:properties :author]))
        permalink? (some #{(source/normalized url)} (urls entry :url))
        rel-author (first (get-in mf2 [:rels "author"]))]
    (or (some-> (or (author entry) (author (feed-of mf2 entry))) author-of)
        ;; a rel=author link of the page, for older pages
        (when (and permalink? (url/web-url? rel-author))
          {:page rel-author}))))

(defn- cards
  "The h-cards in the parsed `mf2` of the page at `url` that can represent
  it, by the steps of representative h-card parsing, in their order: the
  first whose url and uid are the URL of the page, the first whose url is
  that of a rel=me link of the page, and the only h-card, if its url is
  that of the page."
  [mf2 url]
  (let [page   (source/normalized url)
        all    (filter #(typed? "h-card" %) (items mf2))
        me     (set (map source/normalized (get-in mf2 [:rels "me"])))
        named? (fn [k item] (some #{page} (urls item k)))]
    [(first (filter #(and (named? :url %) (named? :uid %)) all))
     (first (filter #(some me (urls % :url)) all))
     (when (= 1 (count all))
       (first (filter #(named? :url %) all)))]))

(defn representative-card
  "The representative h-card of the page at `url` with the parsed `mf2`, as
  card gives it, or nil when it has none.

  It's the first h-card whose url and uid are the URL of the page, or else
  the first whose url is that of a rel=me link of the page, or else the
  only h-card, if its url is that of the page."
  [mf2 url]
  (some-> (some identity (cards mf2 url)) card))

(defn- page-card
  "The first h-card in the parsed `mf2` whose url is `page`, as card gives
  it, or nil."
  [mf2 page]
  (let [p (source/normalized page)]
    (some->> (items mf2)
             (filter #(and (typed? "h-card" %) (some #{p} (urls % :url))))
             first
             card)))

;; the authorship algorithm, step 7, which takes the first two steps of
;; representative h-card parsing alone
(defn- fetched-card
  "The h-card that represents the author of the HTML page in the
  `response`, as card gives it, or nil."
  [{:keys [status url body] :as response}]
  (when (and (<= 200 status 299) (= :html (source/kind response)))
    (some-> (some identity (take 2 (cards (mf/parse (str body) url) url)))
            card)))

(defn author!
  "The author of the mention in the `report` of a verification, by the
  authorship algorithm, as card gives it, with `opts`.

  When the page names its author by the URL of the author's page alone,
  it fetches that page for the h-card whose url and uid are that of the
  page, or else whose url is that of a rel=me link there. Failing that, it
  takes the h-card with that url on the page of the mention, and else the
  author is the :url alone. The `opts` are those of
  webmention/default-options. In ClojureScript, it gives a promise."
  ([report]
   (author! report {}))
  ([{:keys [mf2 url source] :as report} opts]
   (let [base                      (or url source)
         {:keys [page] :as author} (named-author mf2 base (entry mf2 base))]
     (if-not page
       ;; a promise in ClojureScript too
       (async/then (:card author) identity)
       (async/then
        (async/attempt #(http/fetch! page (merge webmention/default-options
                                                 opts)))
        (fn [{:keys [value]}]
          (or (some-> value fetched-card)
              (page-card mf2 page)
              {:url page})))))))

;;; Content

(def option-keys
  "The options of summary that aren't those of html-pieces."
  [:max-length :any-name? :page-title?])

(defn content-element
  "The `element` of a mention's content as your page shows it:

  - a heading as a paragraph, so that it stays out of the page's outline
  - an image as its alt text, so that it doesn't load from another site
  - a link with rel=\"nofollow ugc\", as content that a stranger wrote"
  [[tag attrs & children :as element]]
  (case tag
    (:h1 :h2 :h3 :h4 :h5 :h6) (into [:p {}] children)
    :img                      (not-empty (:alt attrs))
    :a                        (assoc-in element [1 :rel] "nofollow ugc")
    element))

(defn- paragraphs
  "The plain text `s` as Hiccup: a paragraph for each part between blank
  lines, with a line break at each line."
  [s]
  (for [p (str/split (str/trim s) #"\n\s*\n")]
    (into [:p {}] (interpose [:br {}] (str/split-lines p)))))

(defn- cut
  "The text `s` cut back to the end of a word within `n` characters."
  [s n]
  (let [head (subs s 0 n)]
    (if (re-find #"^\s" (subs s n))
      (str/trimr head)
      (str/replace head #"\s+\S*$" ""))))

(defn- cut-nodes
  "The Hiccup `nodes` with `n` characters of text at most, as a pair of the
  nodes and the number of characters left, which is negative once a text
  has been cut, and then ends in an ellipsis."
  [nodes n]
  (reduce (fn [[kept left] node]
            (cond
              (neg? left)
              (reduced [kept left])

              (vector? node)
              (let [[tag attrs & children] node
                    [kids left]            (cut-nodes children left)]
                [(conj kept (into [tag attrs] kids)) left])

              (<= (count node) left)
              [(conj kept node) (- left (count node))]

              :else
              [(conj kept (str (cut node left) "…")) -1]))
          [[] n]
          nodes))

(defn- excerpt
  "The Hiccup `nodes` cut back to `n` characters of text, with an ellipsis
  where a text was cut."
  [nodes n]
  (seq (first (cut-nodes nodes n))))

;; Webmention 4.1: what's shown of a source is sanitized
(defn- content
  "The content of the `item`, or else its summary, by `opts`, as a map of
  its :text and its sanitized :hiccup, with its URLs resolved against
  `base`, and its text cut back to the :max-length of `opts`. Nil when it
  has neither."
  [item base {:keys [max-length] :as opts}]
  (when-let [v (some #(first (get-in item [:properties %]))
                     [:content :summary])]
    (let [x      (if (map? v) (:hiccup v (:value v)) v)
          hiccup (cond-> (html/hiccup (if (string? x) (paragraphs x) x)
                                      (merge {:element-fn content-element
                                              :base-url   base
                                              :shorthand? false}
                                             (apply dissoc opts option-keys)))
                   max-length (excerpt max-length))
          text   (html/text hiccup)]
      (when-not (str/blank? text)
        {:text text :hiccup hiccup}))))

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
  (let [n    (squeezed (property item :name))
        text (squeezed (or (property item :content) (property item :summary)))]
    (when (and (seq n) (seq text) (not (str/starts-with? text n)))
      n)))

(defn- named
  "The name of the `entry` on the page whose title element has the text
  `page-title`, by the :any-name? and :page-title? of `opts`, or nil."
  [entry page-title {:keys [any-name? page-title?] :as opts}]
  (or (if any-name?
        (squeezed (property entry :name))
        (title entry))
      (when page-title?
        page-title)))

(defn summary
  "The mention in the `report` of a verification, as a map of what a page
  shows of it, by `opts`:

  - :source and :target, as in the report
  - :kind, as kind gives it
  - :url, the URL of the mention, which can differ from the source, e.g.
    for a post that a bridge sent on, and else the URL of the source
  - :author, as card gives it, or the :url of the author's page alone, for
    which author! finds the rest
  - :name, the title of an article
  - :published, as the page has it
  - :content, the :text and the sanitized :hiccup of the content, or else
    of the summary
  - :in-reply-to, the URLs that it replies to
  - :rsvp, the answer of an RSVP

  Each key is left out when the page has nothing for it. The `opts` are
  these, and those of html-pieces for the Hiccup of the content, with
  content-element as the :element-fn unless they give another:

  - :max-length, how many characters of text the content keeps at most,
    the breaks between its paragraphs not counted. It's cut back to the
    end of a word, with an ellipsis, e.g. for an excerpt of a reply.
  - :any-name?, true for the name of any entry, which for a note is its
    text, rather than for the title of an article alone
  - :page-title?, true for the :title of the report as the name, that of
    the title element of the page, when the entry has none"
  ([report]
   (summary report {}))
  ([{:keys [mf2 url source target] page-title :title :as report} opts]
   (let [base   (or url source)
         e      (entry mf2 base)
         author (named-author mf2 base e)
         k      (if e (kind e target) :mention)]
     (compact {:source      source
               :target      target
               :kind        k
               :url         (or (first (urls e :url)) base)
               :author      (if-let [page (:page author)]
                              {:url page}
                              (:card author))
               :name        (named e page-title opts)
               :published   (property e :published)
               :content     (content e base opts)
               :in-reply-to (not-empty (vec (urls e :in-reply-to)))
               :rsvp        (when (= :rsvp k) (rsvp e))}))))

(comment
  (def report
    {:source  "https://them.example/notes/1"
     :target  "https://me.example/posts/hello"
     :url     "https://them.example/notes/1"
     :outcome :verified
     :mf2     (mf/parse (str "<article class=h-entry>"
                             "<a class='p-author h-card' href=/>Jane</a>"
                             "<div class=e-content><h2>Yes!</h2><p>Nice "
                             "<a class=u-in-reply-to "
                             "href=https://me.example/posts/hello>post</a>"
                             "</div></article>")
                        "https://them.example/notes/1")})

  (summary report)
  (author! report)
  #_.)

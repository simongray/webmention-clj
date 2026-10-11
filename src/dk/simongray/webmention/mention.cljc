(ns dk.simongray.webmention.mention
  "What you need to show a mention, read from the microformats of its
  source: its kind, its author and its content.

  The kind follows the Response Type Algorithm of Post Type Discovery, the
  W3C Note of 18 January 2018 at https://www.w3.org/TR/post-type-discovery/.
  The author follows the authorship algorithm at
  https://indieweb.org/authorship-spec, and the h-card of a page follows
  https://microformats.org/wiki/representative-h-card-parsing, as
  microformats-clj reads them.

  The steps behind these functions are in dk.simongray.webmention.items
  and dk.simongray.webmention.content, which aren't part of the API."
  (:require [dk.simongray.microformats :as mf]
            [dk.simongray.wary-fetch :as http]
            [dk.simongray.wary-fetch.async :as async]
            [dk.simongray.webmention :as webmention]
            [dk.simongray.webmention.content :as content]
            [dk.simongray.webmention.items :as items]
            [dk.simongray.webmention.source :as source]))

;; Webmention 3.2.4 calls it the primary object of the source, e.g. its
;; h-entry, and leaves it at that
(defn entry
  "The h-entry that the page at `url` with the parsed `mf2` is about: the
  one whose url is that of the page, or else the first at the top of the
  page or in a feed there. Nil when it has none."
  [mf2 url]
  (let [page    (source/normalized url)
        top     (mapcat #(if (items/typed? "h-feed" %) (:children %) [%])
                        (:items mf2))
        entries (filter #(items/typed? "h-entry" %) top)]
    (or (some #(when (some #{page} (items/urls % :url)) %) entries)
        (first entries))))

;;; Kind

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
               (let [us (items/urls entry k)]
                 (if t (some #{t} us) (seq us))))]
     (or (when (and (items/rsvp entry) (or (nil? t) (to? :in-reply-to)))
           :rsvp)
         (some (fn [[response k]] (when (to? k) response))
               response-properties)
         :mention))))

;;; Author

(defn representative-card
  "The representative h-card of the page at `url` with the parsed `mf2`, as
  a map of its :name, :url and :photo, those it has, or nil when it has
  none.

  It's the first h-card whose url and uid are the URL of the page, or else
  the first whose url is that of a rel=me link of the page, or else the
  only h-card, if its url is that of the page."
  [mf2 url]
  (some-> (mf/representative-card mf2 url) items/card))

(defn- microformats
  "The microformats of the HTML page in the 2xx `response`, or nil."
  [{:keys [status url body] :as response}]
  (when (and (<= 200 status 299) (= :html (source/kind response)))
    (mf/parse (str body) url)))

(defn author!
  "The author of the mention in the `report` of a verification, by the
  authorship algorithm, as a map of its :name, :url and :photo, with
  `opts`.

  When the page names its author by the URL of the author's page alone,
  it fetches that page for the h-card whose url and uid are that of the
  page, or else whose url is that of a rel=me link there. Failing that, it
  takes the h-card with that url on the page of the mention, and else the
  author is the :url alone. The `opts` are those of
  webmention/default-options. In ClojureScript, it gives a promise."
  ([report]
   (author! report {}))
  ([{:keys [mf2 url source] :as report} opts]
   (let [base                (or url source)
         {:keys [card page]} (mf/author mf2 base (entry mf2 base))]
     (if-not page
       ;; a promise in ClojureScript too
       (async/then (some-> card items/card) identity)
       (async/then
        (async/attempt #(http/fetch! page (merge webmention/default-options
                                                 opts)))
        (fn [{:keys [value]}]
          ;; the authorship algorithm, step 7
          (or (some-> (mf/author-card mf2 page (some-> value microformats))
                      items/card)
              {:url page})))))))

;;; Content

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

(defn summary
  "The mention in the `report` of a verification, as a map of what a page
  shows of it, by `opts`:

  - :source and :target, as in the report
  - :kind, as kind gives it
  - :url, the URL of the mention, which can differ from the source, e.g.
    for a post that a bridge sent on, and else the URL of the source
  - :author, the :name, :url and :photo of the author's h-card, or the
    :url of the author's page alone, for which author! finds the rest
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
         author (mf/author mf2 base e)
         k      (if e (kind e target) :mention)
         opts   (merge {:element-fn content-element} opts)]
     (into {} (filter val)
           {:source      source
            :target      target
            :kind        k
            :url         (or (first (items/urls e :url)) base)
            :author      (if-let [page (:page author)]
                           {:url page}
                           (some-> (:card author) items/card))
            :name        (content/named e page-title opts)
            :published   (mf/property e :published)
            :content     (content/sanitized e base opts)
            :in-reply-to (not-empty (vec (items/urls e :in-reply-to)))
            :rsvp        (when (= :rsvp k) (items/rsvp e))}))))

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

(ns dk.simongray.webmention.mention-test
  "What a receiver shows of a mention: its kind, its author and its
  content. The microformats themselves are parsed by microformats-clj,
  which has the spec's test suite, so these tests pin down what's read
  from them. Several come from indieblog, whose code this is."
  (:require [clojure.test :refer [deftest is testing]]
            [dk.simongray.microformats :as mf]
            [dk.simongray.wary-fetch.async :as async]
            [dk.simongray.webmention.mention :as mention]
            [dk.simongray.webmention.test-support :refer [run]]))

(def source "https://them.example/notes/1")
(def target "https://me.example/posts/hello")

(defn- parsed
  "The microformats of the HTML `s` at `url`, the source by default, as
  verify! parses them."
  ([s]
   (parsed s source))
  ([s url]
   (mf/parse s url {:proposed-backcompat? true})))

(defn- report
  "The report of a verified source with the HTML `s`."
  [s]
  {:source source :target target :url source :outcome :verified :mf2 (parsed s)})

(defn- summary
  "The summary of the mention in a source with the HTML `s`."
  [s]
  (mention/summary (report s)))

(deftest the-entry-of-a-page
  (let [entry #(get-in (mention/entry (parsed %) source) [:properties :name])]
    (is (= ["Second"] (entry (str "<div class=h-entry><p class=p-name>First</p></div>"
                                  "<div class=h-entry><p class=p-name>Second</p><a class=u-url href=/notes/1>#</a></div>")))
        "the one whose url is the page")
    (is (= ["First"] (entry (str "<div class=h-entry><p class=p-name>First</p></div>"
                                 "<div class=h-entry><p class=p-name>Second</p></div>")))
        "or else the first")
    (is (= ["In a feed"] (entry "<div class=h-feed><div class=h-entry><p class=p-name>In a feed</p></div></div>")))
    (is (= ["Ours"] (entry (str "<div class=h-entry><p class=p-name>Ours</p>"
                                "<div class='p-comment h-entry'><p class=p-name>A comment</p></div></div>")))
        "not a comment inside it")
    (is (nil? (entry "<p class=h-card>Jane</p>")))))

(deftest kinds
  (let [kind #(:kind (summary (str "<div class=h-entry>" % "</div>")))
        to   #(str "<a class=" % " href=" target ">x</a>")]
    (testing "Post Type Discovery's response types"
      (is (= :reply (kind (to "u-in-reply-to"))))
      (is (= :like (kind (to "u-like-of"))))
      (is (= :repost (kind (to "u-repost-of"))))
      (is (= :bookmark (kind (to "u-bookmark-of"))))
      (is (= :rsvp (kind (str (to "u-in-reply-to") "<data class=p-rsvp value=Yes>I'll come</data>"))))
      (is (= :mention (kind (str "<a href=" target ">as seen here</a>")))))
    (testing "in their order"
      (is (= :like (kind (str (to "u-in-reply-to") (to "u-like-of")))))
      (is (= :repost (kind (str (to "u-like-of") (to "u-repost-of")))))
      (is (= :reply (kind (str (to "u-in-reply-to") "<data class=p-rsvp value=perhaps></data>")))
          "an rsvp without a valid answer"))
    (testing "of the target alone"
      (is (= :mention (kind (str "<a class=u-in-reply-to href=https://other.example/>re</a>"
                                 "<a href=" target ">and this</a>"))))
      (is (= :mention (kind "<a class=u-like-of href=https://other.example/>♥</a>"))))
    (testing "by an embedded h-cite, and normalized"
      (is (= :reply (kind (str "<div class='u-in-reply-to h-cite'><a class=u-url href=" target ">the post</a></div>"))))
      (is (= :reply (kind "<a class=u-in-reply-to href=HTTPS://ME.example:443/posts/hello>re</a>"))))
    (testing "without a target"
      (is (= :like (mention/kind (mention/entry (parsed "<div class=h-entry><a class=u-like-of href=/x>♥</a></div>") source))))
      (is (= :rsvp (mention/kind (mention/entry (parsed "<div class=h-entry><data class=p-rsvp value=no></data></div>") source)))))))

(deftest a-summary
  (is (= {:source      source
          :target      target
          :kind        :reply
          :url         "https://them.example/notes/1"
          :author      {:name "Jane Doe" :url "https://them.example/" :photo "https://them.example/jane.jpg"}
          :published   "2026-07-14T10:00:00+0200"
          :content     {:text   "Nice post!"
                        :hiccup (list [:p {} "Nice " [:a {:href target :rel "nofollow ugc"} "post"] "!"])}
          :in-reply-to [target]}
         (summary (str "<article class=h-entry>"
                       "<div class='p-author h-card'><a class='p-name u-url' href=/>Jane Doe</a>"
                       "<img class=u-photo src=/jane.jpg alt=''></div>"
                       "<time class=dt-published datetime=2026-07-14T10:00:00+0200>today</time>"
                       "<div class=e-content><p>Nice <a class=u-in-reply-to href=" target ">post</a>!</p></div>"
                       "<a class=u-url href=/notes/1>#</a></article>")))))

(deftest the-url-of-a-mention
  (is (= "https://mastodon.example/@jane/1"
         (:url (summary "<div class=h-entry><a class=u-url href=https://mastodon.example/@jane/1>#</a></div>")))
      "its own, e.g. of a post that a bridge sent on")
  (is (= source (:url (summary "<div class=h-entry>Hi</div>"))) "or else the source's")
  (is (= {:source source :target target :kind :mention :url source} (summary "<p>No microformats"))))

(deftest nested-microformats
  (testing "a property inside a nested microformat belongs to that, not to the entry"
    (is (= "The Real Post Title"
           (:name (summary "<article class=h-entry>
                              <a class='p-author h-card' href=/me><span class=p-name>Jane Doe</span></a>
                              <h1 class=p-name>The Real Post Title</h1>
                              <div class=e-content>Its text</div>
                            </article>")))
        "an author's p-name is not the name of the post")
    (is (= "Jane Doe"
           (get-in (summary "<article class=h-entry>
                              <a class='p-author h-card' href=/me><span class=p-name>Jane Doe</span>
                                <span class='p-org h-card'><span class=p-name>Acme Corp</span></span></a>
                            </article>")
                   [:author :name]))
        "an author's p-org h-card does not shadow the author's own name")
    (is (= "2026-07-14"
           (:published (summary "<article class=h-entry>
                                   <time class=dt-published datetime=2026-07-14>today</time>
                                   <blockquote class=h-cite><time class=dt-published datetime=1999-01-01>then</time></blockquote>
                                 </article>")))
        "an embedded h-cite does not supply the entry's own properties")))

(deftest names
  (testing "the name of an article, by Post Type Discovery"
    (is (= "Slept from 10:45pm to 5:57am"
           (:name (summary "<div class=h-entry><data class=p-name value='Slept from 10:45pm to 5:57am'></data>
                              <p class=e-content>Not much, then</p></div>"))))
    (is (nil? (:name (summary "<div class=h-entry><p class=p-name>A note of mine</p>
                                 <p class=e-content>A note   of mine, and more</p></div>")))
        "not when the name is the start of the content")
    (is (nil? (:name (summary "<div class=h-entry><h1 class=p-name>Hi</h1></div>")))
        "nor when there's no content")
    (is (nil? (:name (summary "<div class=h-entry>a bare note</div>")))
        "nor when the name is implied")))

(deftest content
  (let [content #(:content (summary (str "<div class=h-entry>" % "</div>")))]
    (testing "the paragraphs and lines of a reply stay apart, however tight the HTML"
      (is (= "One.\n\nTwo\nthree." (:text (content "<div class=e-content><p>One.</p><p>Two<br>three.</p></div>")))))
    (testing "sanitized, with content-element"
      (is (= '([:p {} "A heading"] [:p {} "A cat" ", and " [:a {:href "https://them.example/x" :rel "nofollow ugc"} "a link"]])
             (:hiccup (content (str "<div class=e-content><h2>A heading</h2><p><img src=/cat.png alt='A cat'>"
                                    "<img src=/tracker.png>, and <a href=/x onclick=steal()>a link</a>"
                                    "<script>steal()</script></p></div>"))))))
    (testing "a summary as the fallback, and plain text as text"
      (is (= {:text "In short: use <b>." :hiccup '([:p {} "In short: use <b>."])}
             (content "<p class=p-summary>In short: use &lt;b&gt;.</p>"))))
    (testing "with other options of html-pieces"
      (is (= '([:h2 {} "A heading"])
             (:hiccup (:content (mention/summary (report "<div class=h-entry><div class=e-content><h2>A heading</h2></div></div>")
                                                 {:element-fn identity}))))))
    (is (nil? (content "<div class=e-content>  </div>")))
    (is (nil? (content "")))))

(deftest microformats1
  (testing "an older WordPress theme's hentry, its date and author taken by the
            rules that the h-entry wiki page proposes"
    (is (= {:published "2012-03-04T10:00:00+00:00"
            :author    {:url "https://them.example/author/jane/"}
            :content   {:text "Hi" :hiccup '([:p {} "Hi"])}}
           (select-keys (summary "<article class=hentry>
                                    <h1 class=entry-title>Old post</h1>
                                    <time class=entry-date datetime='2012-03-04T10:00:00+00:00'>March 4</time>
                                    by <a href='/author/jane/' rel=author>Jane</a>
                                    <div class=entry-content><p>Hi</p></div>
                                  </article>")
                        [:published :author :content])))))

(deftest the-urls-that-a-page-links
  (testing "only http and https, since a page links them"
    (is (= {:name "Jane"}
           (:author (summary "<div class=h-entry><div class='p-author h-card'><span class=p-name>Jane</span>
                              <a class=u-url href='javascript:steal()'>me</a><img class=u-photo src='data:image/png;base64,AA'></div></div>"))))
    (is (= source (:url (summary "<div class=h-entry><a class=u-url href='javascript:steal()'>#</a></div>"))))))

(def card-page
  "The URL of the page of an author."
  "https://jane.example/")

(deftest representative-cards
  (let [card #(mention/representative-card (parsed % card-page) card-page)]
    (is (= {:name "Jane" :url card-page}
           (card (str "<p class=h-card><a class=p-name href=https://other.example/>Other</a></p>"
                      "<p class=h-card><a class='p-name u-url u-uid' href=/>Jane</a></p>")))
        "the one whose url and uid are the page")
    (is (= {:name "Jane" :url "https://social.example/@jane"}
           (card "<p class=h-card><a class='p-name u-url' rel=me href=https://social.example/@jane>Jane</a></p>"))
        "or else the one whose url is a rel=me link")
    (is (= {:name "Jane" :url card-page} (card "<p class=h-card><a class='p-name u-url' href=/>Jane</a></p>"))
        "or else the only one, with the page as its url")
    (is (nil? (card (str "<p class=h-card><a class='p-name u-url' href=/>Jane</a></p>"
                         "<p class=h-card><a class='p-name u-url' href=/>Jane again</a></p>")))
        "but not one of several")
    (is (nil? (card "<p class=h-card>Jane</p>")))
    (is (= {:name "Jane" :url card-page}
           (card "<div class=h-feed><div class='p-author h-card'><a class='p-name u-url u-uid' href=/>Jane</a></div></div>"))
        "an h-card at any depth")))

(defn- sender
  "A :send that answers each request by the `responses`, a map of URLs to
  HTML pages, or else with a 404."
  [responses]
  (fn [{:keys [url]}]
    (if-let [body (get responses url)]
      {:status 200 :url url :headers {"content-type" "text/html"} :body body}
      {:status 404 :url url :headers {} :body ""})))

(deftest authorship
  (let [author! (fn [html pages]
                  (mention/author! (report html) {:send (sender pages)}))
        expect  (fn [expected html pages]
                  #(async/then (author! html pages) (fn [author] (is (= expected author) html))))
        home    "<p class=h-card><a class='p-name u-url u-uid' href=/>Jane Doe</a><img class=u-photo src=/me.jpg></p>"]
    (run (expect {:name "Jane" :url "https://them.example/"}
                 "<div class=h-entry><a class='p-author h-card' href=/>Jane</a></div>" {})
         (expect {:name "Jane"} "<div class=h-entry><span class=p-author>Jane</span></div>" {})
         (expect {:name "Jane" :url "https://them.example/"}
                 "<div class=h-feed><a class='p-author h-card' href=/>Jane</a><div class=h-entry>Hi</div></div>" {})
         ;; an author by the URL of a page, which is fetched
         (expect {:name "Jane Doe" :url card-page :photo "https://jane.example/me.jpg"}
                 (str "<div class=h-entry><a class=u-author href=" card-page ">Jane</a></div>")
                 {card-page home})
         (expect {:name "Jane" :url card-page}
                 (str "<div class=h-entry><a class=u-author href=" card-page ">Jane</a></div>"
                      "<p class=h-card><a class='p-name u-url' href=" card-page ">Jane</a></p>")
                 {})
         (expect {:url card-page}
                 (str "<div class=h-entry><a class=u-author href=" card-page ">Jane</a></div>") {})
         ;; a rel=author link of a permalink page
         (expect {:name "Jane Doe" :url card-page :photo "https://jane.example/me.jpg"}
                 (str "<a rel=author href=" card-page ">Jane</a><div class=h-entry><a class=u-url href=" source ">#</a></div>")
                 {card-page home})
         (expect nil (str "<a rel=author href=" card-page ">Jane</a><div class=h-entry>Not a permalink</div>")
                 {card-page home})
         (expect nil "<p>No microformats" {}))))

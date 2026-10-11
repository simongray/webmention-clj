webmention-clj
==============

[![Clojars Project](https://img.shields.io/clojars/v/dk.simongray/webmention-clj.svg)](https://clojars.org/dk.simongray/webmention-clj)

This is a Clojure and ClojureScript implementation of
[Webmention](https://www.w3.org/TR/webmention/), the W3C standard by which
a page tells another page that it links to it, e.g. a reply on one blog
to a post on another. It covers both roles of the standard, the sender
and the receiver, and reads what a receiver shows of a mention: its
kind, its author and its content.

- **The whole standard.** The code follows the Recommendation of 12
  January 2017 and cites the section of each rule it implements. Its tests
  include the scenarios of the [webmention.rocks](https://webmention.rocks)
  test suite, run locally.
- **Plain data.** Requests and reports are maps, any HTTP client can send
  the requests, and the endpoint is a Ring handler.
- **Nothing in the background.** The endpoint gives its work to you as
  jobs, plain maps, and you run them when and where you like.
- **Safe by default.** Requests only go to the public internet, with
  limits on time and size, the endpoint doesn't fetch a source while it
  answers, and the content of a mention comes sanitized.

The same code runs on the JVM, in Node and in the browser.

> This library was spun out of [indieblog](https://github.com/simongray/indieblog),
> the software of [simon.grays.blog](https://simon.grays.blog), which sends,
> receives and shows Webmentions. It was developed with assistance from
> frontier LLMs.

Getting started
---------------

It requires Clojure 1.11+ and Java 11+. For the latest release, add it
from [Clojars](https://clojars.org/dk.simongray/webmention-clj) to the
`:deps` in your `deps.edn`:

```clojure
dk.simongray/webmention-clj {:mvn/version "0.1.0"}
```

For changes that aren't released yet, use the SHA of the latest commit on
`master` instead:

```clojure
dk.simongray/webmention-clj
{:git/url "https://github.com/simongray/webmention-clj"
 :git/sha "…"}
```

For ClojureScript, shadow-cljs only reads Git dependencies from
`deps.edn`, so also set `:deps true` in your `shadow-cljs.edn`.

### Send

When you publish a page, send a Webmention to each page that it links
to. The `send!` function finds the endpoint of the target and posts the
Webmention to it:

```clojure
(require '[dk.simongray.webmention :as webmention])

(webmention/send! "https://simon.grays.blog/posts/2026/hello"
                  "https://webmention.rocks/test/1")
;; => {:source   "https://simon.grays.blog/posts/2026/hello"
;;     :target   "https://webmention.rocks/test/1"
;;     :endpoint "https://webmention.rocks/test/1/webmention"
;;     :outcome  :sent
;;     :status   202}
```

The `:outcome` is `:sent`, `:no-endpoint` or `:failed`. To send to every
link of a page, give its HTML or Hiccup to `links`, leave out the links
to your own site, and send them all with `send-all!`:

```clojure
(require '[clojure.string :as str])

(def post
  "https://simon.grays.blog/posts/2026/hello")

(def targets
  (->> (webmention/links "<p>Hi <a href='https://webmention.rocks/test/1'>there</a>" post)
       (remove #(str/starts-with? % "https://simon.grays.blog/"))))

(webmention/send-all! post targets)
```

Keep the targets that a Webmention was sent to. When you update or
delete the page, send it again to those targets as well as to the links
it has now, so that each target can update or remove what it shows of
it. A deleted page should answer 410 Gone.

### Receive

An endpoint consists of its Ring handler, a predicate of the targets
that it takes Webmentions of, and a function of yours that runs its
jobs, e.g. on a thread pool or from a queue in a database:

```clojure
(def mentions
  (atom {}))

(defn keep-mention!
  "Verify the source of the Webmention `job`, and keep or delete its
  mention."
  [job]
  (let [{:keys [source target outcome] :as report} (webmention/verify! job)]
    (case outcome
      :verified         (swap! mentions assoc [source target] report)
      (:unlinked :gone) (swap! mentions dissoc [source target])
      :failed           nil)))

(def endpoint
  (webmention/handler
   {:target-pred #(str/starts-with? % "https://simon.grays.blog/posts/")
    :schedule!   #(future (keep-mention! %))}))
```

Name the endpoint in a Link header of each page that takes Webmentions,
or in its head:

```html
<link rel="webmention" href="https://simon.grays.blog/webmention">
```

The handler checks each Webmention at once and answers 202, or 400 with
the reason in plain text. It gives the job of a Webmention that it takes,
a map of its `:source` and `:target`, to `:schedule!`. Then `verify!`
fetches the source and gives a report of whether it links the target, as
`:verified`, `:unlinked`, `:gone` or `:failed`. The report of an HTML
source that links the target also has its `:mf2`, its microformats as
[microformats-clj](https://github.com/simongray/microformats-clj) parses
them. A sender sends the same Webmention again when the source changes,
so keep one mention for each source and target.

The handler answers 201 with the URL of a status page when you give it a
`:status-url-fn`. It can also verify the source before it answers, with
a `:report!` in place of `:schedule!`, which the standard allows but
doesn't recommend. To take Webmentions from a form on your own page, wrap
the handler, e.g. to send a browser back to the page after a 202, or to
show it the reason of a 400 on a page of your own.

### Show a mention

The `mention/summary` function reads what a page shows of a mention
from the report of a verified source: its kind, by
[Post Type Discovery](https://www.w3.org/TR/post-type-discovery/), its
author, by the [authorship algorithm](https://indieweb.org/authorship-spec),
and its content:

```clojure
(require '[dk.simongray.webmention.mention :as mention])

(mention/summary report)
;; => {:source      "https://jane.example/notes/2026/10/1"
;;     :target      "https://simon.grays.blog/posts/2026/hello"
;;     :kind        :reply
;;     :url         "https://jane.example/notes/2026/10/1"
;;     :author      {:name "Jane Doe" :url "https://jane.example/"}
;;     :published   "2026-10-11T12:00:00+02:00"
;;     :content     {:text   "Nice post!"
;;                   :hiccup ([:p {} "Nice " [:a {:href "https://simon.grays.blog/posts/2026/hello"
;;                                                :rel  "nofollow ugc"}
;;                                            "post"] "!"])}
;;     :in-reply-to ["https://simon.grays.blog/posts/2026/hello"]}
```

The `:kind` is `:reply`, `:like`, `:repost`, `:bookmark`, `:rsvp` or
`:mention`. Link a mention by its `:url` rather than its source, since
the source of a post that a bridge such as Bridgy sent on is a page of
the bridge. The `:hiccup` of the content is sanitized by
[html-pieces](https://github.com/simongray/html-pieces), with its
headings as paragraphs, its images as their alt text, and
`rel="nofollow ugc"` on its links. To show it in another way, give
`summary` an `:element-fn` of your own. Show it with a renderer that
escapes text, e.g. Replicant or `hiccup2.core/html`.

When a page names its author by a URL alone, e.g. with
`<a class="u-author" href="https://jane.example/">`, the `:author` is
that `:url`, and `author!` fetches the author's page for its
representative h-card:

```clojure
(mention/author! report)
;; => {:name "Jane Doe" :url "https://jane.example/" :photo "https://jane.example/me.jpg"}
```

The `mention/representative-card` function gives the h-card of any page
whose microformats you've parsed, e.g. the homepage of someone who signs
in with their domain.

### Options

The requests of `discover!`, `send!` and `verify!` go through
`http/send-public!` of [wary-fetch](https://github.com/simongray/wary-fetch),
which only sends to the public internet. To send them with another HTTP
client, give a `:send` function of your own. The options that have
defaults, e.g. the limits of each request, are in
`webmention/default-options`, and the docstrings describe the others.

Principles
----------

- **Standards first.** The code follows the Recommendation and cites its
  sections, and a comment marks each choice of its own. Where the
  Recommendation is unclear, the code follows the editor's draft and the
  discussions in its issues.
- **Plain data.** Requests, jobs and reports are maps, and every function
  that sends takes a `:send` of your own.
- **One codebase.** The library is written in `.cljc` and depends on
  [wary-fetch](https://github.com/simongray/wary-fetch) for requests,
  [html-pieces](https://github.com/simongray/html-pieces) for pages and
  [microformats-clj](https://github.com/simongray/microformats-clj) for
  their microformats.

Development
-----------

```bash
clojure -X:test               # the tests on the JVM
clojure -X:test:rocks         # the discovery tests of webmention.rocks
npm install                   # once, for the Node tests
clojure -M:cljs compile test  # the tests in Node
```

On the JVM, a test also runs a sender, a receiver and the scenarios of
webmention.rocks against each other over HTTP on localhost. The tests of
webmention.rocks that need a public URL are run by hand, as
[doc/webmention-rocks.md](doc/webmention-rocks.md) describes.

License
-------

The webmention-clj project is licensed under the [MIT licence](LICENSE).

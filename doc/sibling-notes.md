Notes for the sibling libraries
===============================

What webmention-clj needed from wary-fetch, html-pieces and
microformats-clj, what it did instead, and what they could offer. A note
whose need a release has met says what it offered, and what webmention-clj
uses now. The code that each note mentions is in webmention-clj, unless
the note says otherwise.


1. Endpoint discovery
---------------------

**Needed.** The endpoint of a target: the first Link header with
rel=webmention, and else the first link or a element with it, in
document order, resolved against the URL after the redirects and the
page's base. IndieAuth, Micropub and WebSub discover their endpoints in
the same way, by other relation types and elements: WebSub reads only
the link elements in the head of a page, and IndieAuth only link
elements.

**Offered.** The `html/links` of html-pieces 0.4.0, by a link type, the
tags that count and the head alone, which `sender/page-endpoint` uses.
The Link headers are still read by `header-urls` in the namespace
`dk.simongray.webmention.discovery`, by `headers/links` of wary-fetch.

**Could offer.** The URLs of a relation type in the Link headers, beside
`headers/links` in wary-fetch, so that each protocol's discovery is the
headers first and then `html/links`.


2. A POST that follows only 307 and 308
---------------------------------------

**Needed.** A form posted to an endpoint that moved. The `send!` of
wary-fetch follows a 301 or a 302 to a POST as browsers do, with a GET
without the body, so the moved endpoint gets a GET, and its 200 reads as
a success. Issue 89 of the Webmention spec has a sender follow only 307
and 308, which keep the method and the body.

**Did.** The POST goes with `:max-redirects 0`, and a 307 or a 308 sends
it on by hand, never from https to http. That's the `send-on!` of
websub-clj again, which follows a hub's redirects in the same way.

**Could offer.** An option of a request in wary-fetch that follows only
the redirects that keep its method and body, and gives any other
redirect as the response.


3. Two URL resolvers
--------------------

**Needed.** The hrefs of a page, the targets of Link headers and the
Location of an answer, resolved alike on every platform.

**Did.** Every URL is resolved by `url/resolve` of html-pieces, which
follows the URL Standard in `.cljc`. The `url/absolute-url` of wary-fetch
uses `java.net.URI` on the JVM, with fixes, and `URL` in JavaScript, so
its results differ by platform. On the JVM it keeps the case of a
domain and a default port, e.g. `HTTPS://EXAMPLE.com:443/x`, and it reads
`\\x` against `https://example.com/` as a path, where browsers and
html-pieces read the domain `x`. wary-fetch resolves the Location of a
redirect with it too.

**Could offer.** One resolver for both. wary-fetch has no dependencies,
so it would have to take the URL Standard's resolver from html-pieces
rather than depend on it, or html-pieces could take wary-fetch's once it
follows the URL Standard on the JVM.


4. A Link header with an empty target
-------------------------------------

**Needed.** A Link header of `<>; rel=webmention`, which names the URL
of the response itself, as an empty href does in a page.

**Did.** Nothing. The `headers/links` of wary-fetch 0.3.0 still leaves
out a link whose target is empty, and no test of webmention.rocks has
one.

**Could offer.** A link with an empty `:href` for `<>` from wary-fetch,
as RFC 8288 allows a URI reference to be empty.


5. Reading a Ring request
-------------------------

**Needed.** The fields of the form that a sender posts to an endpoint,
from the `:form-params` of a Ring request or else from a body of limited
size in UTF-8, and answers in plain text.

**Offered.** The `bytes/encode`, `bytes/decode` and `url/query-pairs` of
wary-fetch 0.3.0, which the namespace `dk.simongray.webmention.ring` now
uses. Ring helpers themselves stay out of wary-fetch, as Simon decided,
so the rest of that namespace is a copy of websub-clj's.


6. The URLs in a page's attributes
----------------------------------

**Needed.** Every URL in an attribute of a source page, since a link to
the target can be the href of an a, the src of an img or one candidate of
a srcset, as issue 91 of the Webmention spec says.

**Did.** The function `page/attribute-urls` takes the tables
`url-attributes` and `url-list-attributes` of html-pieces, and splits a
srcset and the other lists itself, since the `checked-list` of
html-pieces isn't public and drops a list whole when one URL in it isn't
allowed.

**Could offer.** A public function of html-pieces that gives the URLs in
the value of an attribute.


7. Walking a page
-----------------

**Needed.** The elements of a page in document order, without the
content of a template, as microformats-clj needed too (note 6 of its
notes for html-pieces).

**Offered.** The `html/elements` and `html/base-url` of html-pieces
0.4.0, which `page/parse` uses on the tree it has parsed. They parse
the Hiccup again, which copies it, at a cost of a few milliseconds a
page.


8. Representative h-card and authorship
---------------------------------------

**Needed.** The author of a mention, by the authorship algorithm, and the
representative h-card of the author's page. IndieAuth needs the
representative h-card too, for the profile of someone who signs in.

**Offered.** The `mf/representative-card`, `mf/author` and
`mf/author-card` of microformats-clj 0.2.0, with the fetch of the
author's page left to the caller. The `representative-card` and `author!`
of `dk.simongray.webmention.mention` use them, and make each h-card a map
of its `:name`, `:url` and `:photo`.


9. Microformats in document order
---------------------------------

**Needed.** The first h-card of a page, as representative h-card parsing
and the authorship algorithm have it, among the h-cards at any depth.

**Offered.** The `mf/items` of microformats-clj 0.2.0, in exact document
order, which its parser now records.


10. Reading a parsed item
-------------------------

**Needed.** The plain value of a property, whether it's text or an
embedded item, the URLs of a property, the url of an embedded h-cite
among them, and the items of a type at any depth.

**Offered.** The `mf/value`, `mf/property`, `mf/urls` and `mf/items` of
microformats-clj 0.2.0. The namespace `dk.simongray.webmention.items`
keeps what's particular to showing a mention: URLs kept to http and
https, an h-card as a map, and the answer of an RSVP.


11. Plain text as Hiccup
------------------------

**Needed.** The Hiccup of a p-summary, which is plain text and can hold a
`<`. The `hiccup` of html-pieces read a string with a tag in it as HTML,
so `"Use <b> for bold."` gave `("Use " [:b {} " for bold."])`.

**Offered.** The `:plain-text?` option of html-pieces 0.4.0, which
`content/sanitized` gives for a string.


12. A body cut at its limit
---------------------------

**Needed.** The start of a target or a source that's larger than
`:max-bytes`. Webmention 4.2 has a receiver limit how much of a source it
downloads, not refuse a large one, and the Link header of a large target
names its endpoint before its body begins.

**Offered.** The `:truncate?` option of wary-fetch 0.3.0, which
`default-options` sets. A source that's cut is `:failed` rather than
`:unlinked` when its start doesn't link the target.

Notes for the sibling libraries
===============================

What webmention-clj needed from wary-fetch, html-pieces and
microformats-clj, what it did instead, and what they could offer. The
code that each note mentions is in webmention-clj, unless the note says
otherwise.


1. Endpoint discovery
---------------------

**Needed.** The endpoint of a target: the first Link header with
rel=webmention, and else the first link or a element with it, in
document order, resolved against the URL after the redirects and the
page's base. IndieAuth, Micropub and WebSub discover their endpoints in
the same way, by other relation types and elements: WebSub reads only
the link elements in the head of a page, and IndieAuth only link
elements. The websub-clj library has its own version, and indieauth-clj
is getting one too.

**Did.** The namespace `dk.simongray.webmention.discovery` has nothing
particular to Webmention: `header-urls` reads the URLs of a relation type
from the Link headers, by `headers/links` of wary-fetch, and `page-urls`
those of a page's elements of the given tags. The `discover` function of
`dk.simongray.webmention` takes the first of either. The `:rels` of
microformats-clj have the URLs of a page in document order too, but they
take `<area>` as well, and they come with a parse of all the page's
microformats.

**Could offer.** A function of microformats-clj that gives the URLs of a
relation type in a page's tree, by tags and perhaps by the head alone,
without the rest of the parse. Rels are its business, and it already
reads a page with html-pieces and resolves URLs by the URL Standard.
The Link headers could be given to it as the maps that
`headers/links` gives, so that it needn't depend on wary-fetch, and the
discovery of each protocol would be a line or two: the headers first,
and then the page.


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

**Did.** Nothing. The `headers/links` of wary-fetch leaves out a link
whose target is empty, and no test of webmention.rocks has one.

**Could offer.** A link with an empty `:href` for `<>` from wary-fetch,
as RFC 8288 allows a URI reference to be empty.


5. Reading a Ring request
-------------------------

**Needed.** The fields of the form that a sender posts to an endpoint,
from the `:form-params` of a Ring request or else from a body of limited
size in UTF-8, and answers in plain text.

**Did.** The namespace `dk.simongray.webmention.ring` is a copy of that
of websub-clj, with UTF-8 functions of its own, since the bytes namespace
of wary-fetch isn't public and has no decode.

**Could offer.** The body of a Ring request within a limit, and the
fields of a form, from wary-fetch if they fit beside the Ring handler of
its proxy, so that websub-clj and webmention-clj share them.


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

**Did.** The function `page/elements` walks the tree with `tree-seq` and
skips templates.

**Could offer.** The public functions for reading the tree that
microformats-clj asks of html-pieces.


8. Representative h-card and authorship
---------------------------------------

**Needed.** The author of a mention, by the authorship algorithm, and the
representative h-card of the author's page. The README of
microformats-clj leaves both out, as specs of their own. IndieAuth needs
the representative h-card too, for the profile of someone who signs in.

**Did.** The namespace `dk.simongray.webmention.mention` has
`representative-card`, a function of a page's parsed microformats and
its URL, and `author!`, which fetches the author's page. The steps of
both that read a page are in `dk.simongray.webmention.authorship`.

**Could offer.** In microformats-clj, `representative-card` and the
steps of the authorship algorithm that read a page, which give either
the author or the URL of the author's page to fetch, so that the fetch
stays with the caller.


9. Microformats in document order
---------------------------------

**Needed.** The first h-card of a page, as representative h-card parsing
and the authorship algorithm have it, among the h-cards at any depth.

**Did.** A walk of each item, the items in its properties and then its
children. The properties of an item are a map, so the walk doesn't know
which of them comes first in the page, and the first h-card is only
nearly the first in document order.

**Could offer.** Every microformat of a page in document order, from
microformats-clj, whose parser knows the order while it walks the page.


10. Reading a parsed item
-------------------------

**Needed.** The plain value of a property, whether it's text or an
embedded item, the URLs of a property, the url of an embedded h-cite
among them, and the items of a type at any depth.

**Did.** The namespace `dk.simongray.webmention.items`, much as
indieblog had its functions.

**Could offer.** A few functions in microformats-clj for reading the
items that `mf/parse` gives.


11. Plain text as Hiccup
------------------------

**Needed.** The Hiccup of a p-summary, which is plain text and can hold a
`<`. The `hiccup` of html-pieces reads a string with a tag in it as HTML,
so `"Use <b> for bold."` gives `("Use " [:b {} " for bold."])`.

**Did.** The text is made into Hiccup of paragraphs and line breaks
first, which `hiccup` takes as text.

**Could offer.** An option of html-pieces that reads a string as plain
text whatever it holds, or its function for paragraphs, which is
private.


12. A body cut at its limit
---------------------------

**Needed.** The start of a target or a source that's larger than
`:max-bytes`. Webmention 4.2 has a receiver limit how much of a source it
downloads, not refuse a large one, and the Link header of a large target
names its endpoint before its body begins.

**Did.** Nothing. The `fetch!` of wary-fetch fails with `::too-large`
when a body is over the limit, so a large target gives `:failed`, and so
does a large source, whose update or deletion then goes unnoticed.

**Could offer.** An option of wary-fetch that gives the body cut at the
limit, with a flag that says it was cut, rather than failing.

The tests of webmention.rocks
=============================

[webmention.rocks](https://webmention.rocks) tests senders and receivers
against the site itself. CI runs none of them, since it shouldn't depend
on the network, but the end-to-end tests run their scenarios on
localhost.

Most of the tests need a page of yours at a public URL: a source that
links the site for the tests of a sender, and a target that names your
endpoint for those of a receiver. You run those by hand.


Discovery
---------

The discovery tests 1 to 23 run without a page of yours, since finding an
endpoint takes a GET alone:

```bash
clojure -X:test:rocks
```

To send each test a Webmention as well, publish a page that links
`https://webmention.rocks/test/1` to `/test/23/page` and send them all:

```clojure
(webmention/send-all! "https://your.example/posts/rocks"
                      (map #(str "https://webmention.rocks/test/" %)
                           (concat (range 1 23) ["23/page"])))
```

Each report should be `:sent`, and each test page then shows your page
as a reply.


Updates and deletions
---------------------

The update and deletion tests check that a sender sends a Webmention
again when its page changes:

- **[Update 1](https://webmention.rocks/update/1).** Publish a page that
  links `/update/1` and send it. Add a link to `/update/1/part/2`, and send
  it to both targets.
- **[Update 2](https://webmention.rocks/update/2).** Publish a page that
  links `/update/2` and `/update/2/part/2` and send it. Remove the second
  link, and send it to both targets again, the removed one too.
- **[Delete 1](https://webmention.rocks/delete/1).** Publish a page that
  links `/delete/1` and send it. Make the page answer 410 Gone, and send
  it to the target again.

Each test page lists your page under its successful tests when you're
done.


Receiving
---------

The receiver tests send Webmentions to your endpoint. Sign in at
webmention.rocks with your domain, and give each test the URL of a page
of yours that names your endpoint:

- **[Receiver 1](https://webmention.rocks/receive/1)** sends a valid
  Webmention. The endpoint passes with 202 and no Location, or with 201
  and a Location, when it has a `:status-url-fn`.
- **[Receiver 2](https://webmention.rocks/receive/2)** sends an invalid
  source, an invalid target, and both. The endpoint passes with 400 to
  each.

The source of receiver test 1 links your page, so its job should give a
report that's `:verified`.

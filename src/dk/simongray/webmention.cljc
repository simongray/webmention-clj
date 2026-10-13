(ns dk.simongray.webmention
  "Webmention for senders and receivers: find the endpoint of a target and
  send it a Webmention, take Webmentions at an endpoint of your own, and
  verify their sources.

  The code follows the W3C Recommendation of 12 January 2017 at
  https://www.w3.org/TR/webmention/ and cites its sections, with the
  erratum that the editor's draft at https://webmention.net/draft/ fixes.
  A receiver is a server with a public URL, since a sender posts to its
  endpoint. What you need to show a mention is read from its source by
  dk.simongray.webmention.mention.

  The steps behind these functions are in dk.simongray.webmention.sender
  and dk.simongray.webmention.receiver, after the two roles of the
  standard, and aren't part of the API."
  (:require [clojure.string :as str]
            [dk.simongray.html-pieces :as html]
            [dk.simongray.wary-fetch :as http]
            [dk.simongray.wary-fetch.async :as async]
            [dk.simongray.wary-fetch.url :as url]
            [dk.simongray.webmention.discovery :as discovery]
            [dk.simongray.webmention.receiver :as receiver]
            [dk.simongray.webmention.ring :as ring]
            [dk.simongray.webmention.sender :as sender]))

;;; Options

;; Webmention 3.1.2: a User-Agent that says what the request is for
(def user-agent
  "The User-Agent of the requests that the library sends, unless the
  options name another."
  "webmention-clj/0.3.0")

;; Webmention 3.2.2: the media types whose links the library reads first
(def accept
  "The Accept header of the requests for targets and sources."
  (str "text/html,application/xhtml+xml,application/json;q=0.9,"
       "text/plain;q=0.8,*/*;q=0.1"))

(def default-options
  "The default of each option that has one, which follows Webmention:

  - :send, http/send-public!, so that no request goes to the network of
    the server, as Webmention 4.3 and 4.5 advise
  - :allow-pred, url/public?, for a :send of your own
  - :timeout-seconds, 5, and :max-bytes, 1 MB, the limits of a request for
    a target or a source, as Webmention 4.2 suggests
  - :truncate?, true, so that a target or a source larger than :max-bytes
    is read up to that limit, as Webmention 4.2 has it, rather than refused
  - :max-redirects, 5, how many redirects a request follows
  - :user-agent, user-agent
  - :accept, accept
  - :concurrency, 4, how many targets send-all! notifies at once
  - :max-form-bytes, 64 kB, the limit of a request to an endpoint
  - :proposed-backcompat?, true, the option of microformats-clj that
    reads the date and the author of an older WordPress post"
  {:send                 http/send-public!
   :allow-pred           url/public?
   :timeout-seconds      5
   :max-bytes            (* 1024 1024)
   :truncate?            true
   :max-redirects        5
   :user-agent           user-agent
   :accept               accept
   :concurrency          4
   :max-form-bytes       65536
   :proposed-backcompat? true})

;;; Sending

;; Webmention 3.1.2
(defn discover
  "The Webmention endpoint that the `response` to a request for a target
  names, or nil when it names none.

  The response is a map of :url, :headers and :body, as http/send! gives
  it. The first Link header with rel=webmention counts first, and then the
  first link or a element of an HTML page, in document order. A relative
  URL counts from the URL of the response, after its redirects, and an
  empty href is that URL itself."
  [{:keys [url headers] :as response}]
  (let [endpoint (or (first (discovery/header-urls headers url "webmention"))
                     (sender/page-endpoint response))]
    (when (url/web-url? endpoint)
      endpoint)))

(defn discover!
  "Fetch the `target` with `opts`, and give the :status of the response
  and the :endpoint that discover finds in it, if any. A request that
  fails, or a target that isn't an http or https URL, gives the :error as
  http/failure has it.

  The :not-before of the response, when it has one, is the time until
  which the endpoint can be kept, as Webmention 5.3 has it. The `opts` are
  those of default-options. In ClojureScript, it gives a promise."
  ([target]
   (discover! target {}))
  ([target opts]
   (let [opts (merge default-options opts)]
     (async/then
      (async/attempt #(if (url/web-url? target)
                        (http/fetch! target opts)
                        (throw (ex-info "The target is not an http or https URL"
                                        {:target target}))))
      (fn [{:keys [value error]}]
        (if error
          {:error (http/failure error)}
          (let [{:keys [status not-before]} value
                endpoint                    (when (<= 200 status 299)
                                              (discover value))]
            (cond-> {:status status}
              endpoint   (assoc :endpoint endpoint)
              not-before (assoc :not-before not-before)))))))))

;; Webmention 3.1.3: a form whose fields don't go into the query string of
;; the endpoint
(defn request
  "The request map that tells the Webmention `endpoint` that the page at
  `source` links to the page at `target`."
  [endpoint source target]
  (when-not (every? url/web-url? [endpoint source target])
    (throw (ex-info (str "The endpoint, the source and the target are http "
                         "or https URLs")
                    {:endpoint endpoint :source source :target target})))
  {:method  :post
   :url     endpoint
   :headers {"Content-Type" "application/x-www-form-urlencoded"}
   :body    (url/query-string [["source" source] ["target" target]])})

(defn send!
  "Tell the Webmention endpoint of the `target` that the page at `source`
  links to it, with `opts`, and give the report of the :outcome.

  - :sent, when the endpoint answers with a success, with its :status and,
    for a 201, the :location of a page that tells how the Webmention fares
  - :no-endpoint, when the target names none
  - :failed, with the :status of a target or an endpoint that doesn't
    answer with a success and the start of the endpoint's :message, or the
    :error of a request that failed

  The report also has the :source, the :target, the :endpoint once it's
  known, and the :not-before of the target, if any. The `opts` are those
  of default-options, and an :endpoint that you found before, e.g. while
  the :not-before of the target is still to come, so that the target
  isn't fetched again. A 307 or a 308 from the endpoint sends the
  Webmention on to the endpoint that it names. In ClojureScript, it gives
  a promise."
  ([source target]
   (send! source target {}))
  ([source target opts]
   (when-not (url/web-url? source)
     (throw (ex-info "The source is an http or https URL" {:source source})))
   (let [opts (merge default-options opts)]
     (async/then
      (if (:endpoint opts)
        (select-keys opts [:endpoint])
        (discover! target opts))
      (fn [{:keys [endpoint error status not-before]}]
        (let [report (cond-> {:source source :target target}
                       not-before (assoc :not-before not-before))]
          (cond
            error               (assoc report :outcome :failed :error error)
            endpoint            (sender/post! (request endpoint source target)
                                              (assoc report :endpoint endpoint)
                                              opts)
            (<= 200 status 299) (assoc report :outcome :no-endpoint)
            :else               (assoc report :outcome :failed
                                       :status status))))))))

;; Webmention 3.1.4 and 3.1.5
(defn send-all!
  "Send a Webmention from the page at `source` to each of the `targets`
  with `opts`, as send! does, and give their reports in the same order.

  When the page is updated or deleted, the targets are those that it
  links to now, and each target that it was sent to before, so that the
  target can update or remove what it shows of the page. The :concurrency
  of `opts` is how many targets are notified at once. In ClojureScript, it
  gives a promise."
  ([source targets]
   (send-all! source targets {}))
  ([source targets opts]
   (let [opts (merge default-options opts)]
     (async/in-groups #(send! source % opts)
                      (:concurrency opts)
                      (distinct targets)))))

;; Webmention 3.1.1
(defn links
  "The http and https URLs that the a and area elements of `x`, the HTML or
  the Hiccup of the page at `url`, link to, in document order and without
  duplicates. They're the targets of the page's Webmentions, once you've
  left out e.g. those of your own site."
  [x url]
  ;; HTML Standard, section 4.6.1: the hyperlinks of a page
  (into [] (comp (map #(get-in % [1 :href])) (filter url/web-url?) (distinct))
        (html/links x {:base-url url :link-tags #{:a :area}})))

;;; Receiving

;; Webmention 3.2.2 and 4.2
(defn verify!
  "Fetch the source of the `job` with `opts`, and give the report of the
  :outcome, whether the source links the target.

  - :verified, when it does, with the :mf2 of an HTML page, its
    microformats as microformats-clj parses them, and its :title, the
    text of its title element
  - :unlinked, when it doesn't
  - :gone, when the source answers 410, or its page says 410 in a meta
    element with http-equiv=\"Status\"
  - :failed, with the :status of another answer, or the :error of a
    request that failed

  When the outcome is :unlinked or :gone, delete what you keep of an
  earlier Webmention from the same source to the same target, as
  Webmention 3.2.4 has it. A job is a map of the :source and the :target,
  as the handler gives it to :schedule!, and the report has them too,
  with the final :url of the source, after its redirects.

  Only the start of a source larger than :max-bytes is read, and its
  report has :truncated?. It's :failed rather than :unlinked when its
  start doesn't link the target, since the rest might.

  A link is a URL in an attribute of an HTML page, e.g. the href of an a
  or the src of an img, resolved against the page, a value in JSON, or the
  text of the URL in any other text. The `opts` are those of
  default-options. In ClojureScript, it gives a promise."
  ([job]
   (verify! job {}))
  ([{:keys [source] :as job} opts]
   (let [opts (merge default-options opts)]
     (async/then
      (async/attempt #(async/then (http/fetch! source opts)
                                  (fn [response]
                                    (receiver/verification job response
                                                           opts))))
      (fn [{:keys [value error]}]
        (or value
            (assoc job :outcome :failed :error (http/failure error))))))))

(defn handler
  "A Ring handler for a Webmention endpoint, by `opts`:

  - :target-pred, a function of a target URL, normalized and without its
    fragment, which says whether the endpoint takes Webmentions of it
  - :schedule!, a function of the job of each Webmention that the endpoint
    takes, a map of its :source and :target, normalized as a browser
    normalizes URLs
  - :status-url-fn, a function of a job that gives the URL of a page that
    tells how it fares, for an answer of 201 rather than 202
  - :report!, a function of the report of each Webmention, in place of
    :schedule!, which verifies each source before the handler answers
  - the options of verify!, and the :max-form-bytes of default-options

  The handler checks each Webmention at once, but doesn't fetch its
  source. It answers 202 to a Webmention that it takes, and gives its job
  to :schedule!. Run the job with verify! soon after, e.g. on a thread
  pool or from a queue in a database. A Webmention of the same source and
  target that comes again is how the source tells of an update or a
  deletion, so it's verified again.

  With :report!, the handler verifies the source while the sender waits,
  which Webmention 3.2 allows but doesn't recommend, since a sender can
  then hold up the endpoint. It answers 200 to a source that links the
  target, and 400 to any other.

  The handler answers 400 with the reason in plain text to a Webmention
  that's wrong, 405 to a request that isn't a POST, and 413 to one larger
  than :max-form-bytes. In ClojureScript, a server gives the handler a
  body that it has read, and with :report! the handler gives a promise."
  [{:keys [target-pred schedule! report!] :as opts}]
  (when-not (and (ifn? target-pred) (ifn? (or report! schedule!)))
    (throw (ex-info "An endpoint needs a :target-pred and a :schedule!" {})))
  (let [opts (merge default-options opts)]
    (ring/handler
     (fn [request]
       (let [{:keys [job rejection]} (receiver/received request opts)]
         (cond
           rejection rejection
           report!   (async/then (verify! job opts)
                                 (fn [report]
                                   (report! report)
                                   (receiver/verified report)))
           :else     (do (schedule! job)
                         (receiver/accepted job opts))))))))

(comment
  (discover {:url     "https://example.com/post"
             :headers {"link" "</webmention>; rel=webmention"}})

  (discover {:url     "https://example.com/post"
             :headers {"content-type" "text/html"}
             :body    "<link rel=webmention href=''>"})

  (request "https://example.com/webmention?x=1"
           "https://me.example/reply" "https://example.com/post")

  (links "<p>Re <a href='/post'>this</a> and <a href='#x'>that</a>"
         "https://me.example/reply")

  (def serve
    (handler {:target-pred #(str/starts-with? % "https://example.com/")
              :schedule!   prn}))

  (serve {:request-method :post
          :form-params    {"source" "https://me.example/reply"
                           "target" "https://example.com/post#c1"}})

  (serve {:request-method :post
          :form-params    {"source" "mailto:me@me.example"
                           "target" "https://example.com/post"}})

  ;; the network
  (discover! "https://webmention.rocks/test/1")
  (verify! {:source "https://webmention.rocks/test/1"
            :target "https://webmention.rocks/"})
  #_.)

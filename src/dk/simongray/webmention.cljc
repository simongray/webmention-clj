(ns dk.simongray.webmention
  "Webmention for senders and receivers: find the endpoint of a target and
  send it a Webmention, take Webmentions at an endpoint of your own, and
  verify their sources.

  The code follows the W3C Recommendation of 12 January 2017 at
  https://www.w3.org/TR/webmention/ and cites its sections, with the
  erratum that the editor's draft at https://webmention.net/draft/ fixes.
  A receiver is a server with a public URL, since a sender posts to its
  endpoint. What a receiver shows of a mention is read from its source by
  dk.simongray.webmention.mention."
  (:require [clojure.string :as str]
            [dk.simongray.html-pieces.url :as whatwg]
            [dk.simongray.microformats :as mf]
            [dk.simongray.wary-fetch :as http]
            [dk.simongray.wary-fetch.async :as async]
            [dk.simongray.wary-fetch.headers :as headers]
            [dk.simongray.wary-fetch.url :as url]
            [dk.simongray.webmention.discovery :as discovery]
            [dk.simongray.webmention.page :as page]
            [dk.simongray.webmention.ring :as ring]
            [dk.simongray.webmention.source :as source]))

;; Webmention 3.1.2: a User-Agent that says what the request is for
(def user-agent
  "The User-Agent of the requests that the library sends, unless the
  options name another."
  "webmention-clj/0.1.0")

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
   :max-redirects        5
   :user-agent           user-agent
   :accept               accept
   :concurrency          4
   :max-form-bytes       65536
   :proposed-backcompat? true})

;;; Sending

(defn- page-endpoint
  "The endpoint that the first link or a element with rel=webmention names
  on the HTML page in the `response`, or nil."
  [{:keys [url body] :as response}]
  (when (= :html (source/kind response))
    ;; against the base element of the page too, as a browser resolves an
    ;; href, a choice of our own where Webmention 3.1.2 names the target's
    ;; URL
    (let [{:keys [elements base]} (page/parse (str body) url)]
      (first (discovery/page-urls elements base "webmention" #{:link :a})))))

;; Webmention 3.1.2
(defn discover
  "The Webmention endpoint that the `response` to a request for a target
  names, or nil when it names none.

  The response is a map of :url, :headers and :body, as http/send! gives
  it. The first Link header with rel=webmention counts first, and then the
  first link or a element of an HTML page, in document order. A relative
  URL counts from the URL of the response, after its redirects, and an
  empty one is that URL itself."
  [{:keys [url headers] :as response}]
  (let [endpoint (or (first (discovery/header-urls headers url "webmention"))
                     (page-endpoint response))]
    (when (url/web-url? endpoint)
      endpoint)))

(defn discover!
  "Fetch the `target` with `opts`, and give the :status of the response
  and the :endpoint that discover finds in it, if any. A request that
  fails gives the :error as http/failure has it.

  The :not-before of the response, when it has one, is the time until
  which the endpoint can be kept, as Webmention 5.3 has it. The `opts` are
  those of default-options. In ClojureScript, it gives a promise."
  ([target]
   (discover! target {}))
  ([target opts]
   (let [opts (merge default-options opts)]
     (async/then
      (async/attempt #(http/fetch! target opts))
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

(defn- location
  "The URL in the Location header of the `response` to a request for
  `url`, resolved against it, or nil."
  [url {:keys [headers] :as response}]
  (some->> (headers/header headers "location") (whatwg/resolve url)))

;; Issue 89 of the spec, https://github.com/w3c/webmention/issues/89: only
;; a 307 or a 308 sends a POST on, since a client turns a POST into a GET
;; after a 301, 302 or 303
(defn- redirect
  "The http or https URL that a 307 or a 308 `response` to a POST to `url`
  sends it on to, but never from https to http, or nil."
  [url {:keys [status] :as response}]
  (when (#{307 308} status)
    (let [to (location url response)]
      (when (and (url/web-url? to)
                 (not= ["https" "http"] (map url/scheme [url to])))
        to))))

(defn- post!
  "Send the `request` with `send`, and on to the endpoint that a 307 or a
  308 names, `n` times at most."
  [send request n]
  (async/then
   (send (assoc request :max-redirects 0))
   (fn [response]
     (if-let [to (and (pos? n) (redirect (:url request) response))]
       (post! send (assoc request :url to) (dec n))
       response))))

(defn- message
  "The start of the text `body` of an answer, or nil."
  [body]
  (when (string? body)
    (not-empty (subs body 0 (min 200 (count body))))))

;; Webmention 3.1.3: any 2xx is a success, and a 201 names a status page
(defn- answered
  "The `report` of a Webmention with the endpoint's answer in the
  `response`."
  [report {:keys [status body url] :as response}]
  (let [sent?       (<= 200 status 299)
        status-page (when (= 201 status) (location url response))
        text        (when-not sent? (message body))]
    (cond-> (assoc report :outcome (if sent? :sent :failed) :status status)
      status-page (assoc :location status-page)
      text        (assoc :message text))))

(defn- post-to!
  "Post the Webmention of the `report` to its :endpoint by `opts`, and give
  the report with the answer."
  [{:keys [endpoint source target] :as report}
   {:keys [send max-redirects] :as opts}]
  (async/then
   (async/attempt #(post! send
                          (http/with-options (request endpoint source target)
                                             opts)
                          max-redirects))
   (fn [{:keys [value error]}]
     (if error
       (assoc report :outcome :failed :error (http/failure error))
       (answered report value)))))

(defn- endpoint!
  "The :endpoint of `opts`, or else what discover! finds of the `target`,
  or an :error when it isn't an http or https URL."
  [target opts]
  (cond
    (:endpoint opts)      (select-keys opts [:endpoint])
    (url/web-url? target) (discover! target opts)
    :else                 {:error {:reason (str "The target is not an http "
                                                "or https URL")}}))

(defn send!
  "Tell the Webmention endpoint of the `target` that the page at `source`
  links to it, with `opts`, and give the report of the :outcome.

  - :sent, when the endpoint answers with a success, with its :status and,
    for a 201, the :location of a page that tells how the Webmention fares
  - :no-endpoint, when the target names none
  - :failed, with the :status and the start of the :message of an answer
    that isn't a success, or the :error of a request that failed

  The report also has the :source, the :target, the :endpoint once it's
  known, and the :not-before of the target, if any. The `opts` are those
  of default-options, and :endpoint, one that you found before, e.g.
  while the :not-before of the target is still to come, so that the
  target isn't fetched again. A 307 or a 308 from the endpoint sends the
  Webmention on to the endpoint that it names. In ClojureScript, it gives
  a promise."
  ([source target]
   (send! source target {}))
  ([source target opts]
   (when-not (url/web-url? source)
     (throw (ex-info "The source is an http or https URL" {:source source})))
   (let [opts (merge default-options opts)]
     (async/then
      (endpoint! target opts)
      (fn [{:keys [endpoint error not-before]}]
        (let [report (cond-> {:source source :target target}
                       not-before (assoc :not-before not-before))]
          (cond
            error    (assoc report :outcome :failed :error error)
            endpoint (post-to! (assoc report :endpoint endpoint) opts)
            :else    (assoc report :outcome :no-endpoint))))))))

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
  (let [{:keys [elements base]} (page/parse x url)]
    (into [] (comp (filter url/web-url?) (distinct))
          (page/hyperlinks elements base))))

;;; Verifying

(defn- examined-page
  "What the HTML `s` of the source at `url` says of the `target`, by `opts`:
  an :outcome, and for a verified page its :mf2 and the :title of its
  title element, if it has one."
  [s url target opts]
  (let [{:keys [nodes elements base]} (page/parse s url)]
    (cond
      (= 410 (source/status elements))
      {:outcome :gone}

      (source/html-links? elements base target)
      (let [title (page/title elements)]
        (cond-> {:outcome :verified
                 :mf2     (mf/parse nodes url
                                    (select-keys opts [:proposed-backcompat?]))}
          title (assoc :title title)))

      :else
      {:outcome :unlinked})))

(defn- examined
  "What the document of the 2xx `response` for a source says of the
  `target`, by `opts`, as examined-page has it."
  [target {:keys [url body] :as response} opts]
  (let [s       (str body)
        outcome #(if % :verified :unlinked)]
    (case (source/kind response)
      :html (examined-page s url target opts)
      :json {:outcome (outcome (source/json-links? s target))}
      :text {:outcome (outcome (str/includes? s target))}
      {:outcome :unlinked})))

;; Webmention 3.2.4: a 410, or a source without the link, is a deletion
(defn- verification
  "The report of the `job` by the `response` to the request for its
  source, by `opts`."
  [{:keys [target] :as job} {:keys [status url] :as response} opts]
  (let [report (assoc job :status status :url url)]
    (cond
      (= 410 status)      (assoc report :outcome :gone)
      (<= 200 status 299) (merge report (examined target response opts))
      :else               (assoc report :outcome :failed))))

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
                                    (verification job response opts))))
      (fn [{:keys [value error]}]
        (or value
            (assoc job :outcome :failed :error (http/failure error))))))))

;;; Receiving

;; Webmention 3.2.1
(defn- problem
  "What's wrong with a Webmention from `source` to `target`, by the
  :target-pred of `opts`, or nil."
  [source target {:keys [target-pred] :as opts}]
  (cond
    (not (url/web-url? source))
    "The source must be an http or https URL"

    (not (url/web-url? target))
    "The target must be an http or https URL"

    (source/same-url? source target)
    "The source and the target must be different URLs"

    ;; the fragment counts for nothing
    (not (target-pred (source/without-fragment target)))
    "The target doesn't take Webmentions"))

;; Webmention 3.2 and 3.2.3: 200 once the source is verified, and else
;; 400, since the source is the sender's
(defn- verified
  "The endpoint's answer to a Webmention that it verified at once, by the
  `report` of the verification."
  [{:keys [outcome] :as report}]
  (case outcome
    :verified (ring/response 200 "The source links the target")
    :unlinked (ring/response 400 "The source doesn't link the target")
    :gone     (ring/response 400 "The source is gone")
    (ring/response 400 "The source can't be fetched")))

;; Webmention 3.2: 202 for a Webmention that's verified later, or 201 with
;; the URL of a page that tells how it fares
(defn- accepted
  "The endpoint's answer to a Webmention whose `job` went to :schedule!, by
  the :status-url-fn of `opts`."
  [job {:keys [status-url-fn] :as opts}]
  (if status-url-fn
    (assoc-in (ring/response 201 "The Webmention is accepted")
              [:headers "Location"]
              (status-url-fn job))
    (ring/response 202 "The Webmention is accepted")))

(defn- answer
  "The endpoint's answer to the POST `request`, by `opts`, once the job of
  a Webmention that it takes has gone to :schedule!, or has been verified
  and its report has gone to :report!."
  [{:keys [schedule! report! max-form-bytes] :as opts} request]
  (let [fields (ring/form-fields request max-form-bytes)
        source (ring/field fields "source")
        target (ring/field fields "target")
        reason (problem source target opts)
        job    {:source source :target target}]
    (cond
      (nil? fields) (ring/response 413 "The request is too large")
      reason        (ring/response 400 reason)
      report!       (async/then (verify! job opts)
                                (fn [report]
                                  (report! report)
                                  (verified report)))
      :else         (do (schedule! job)
                        (accepted job opts)))))

(defn handler
  "A Ring handler for a Webmention endpoint, by `opts`:

  - :target-pred, a function of a target URL without its fragment, which
    says whether the endpoint takes Webmentions of it
  - :schedule!, a function of the job of each Webmention that the endpoint
    takes, a map of its :source and :target
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
       (if (= :post (:request-method request))
         (answer opts request)
         ;; RFC 9110, section 15.5.6
         (assoc-in (ring/response 405 "") [:headers "Allow"] "POST"))))))

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

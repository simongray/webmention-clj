(ns ^:no-doc dk.simongray.webmention.sender
  "The steps of sending a Webmention, as Webmention 3.1 has them: finding
  the endpoint on the page of a target, posting the Webmention to it, and
  reading its answer."
  (:require [dk.simongray.html-pieces.url :as whatwg]
            [dk.simongray.wary-fetch :as http]
            [dk.simongray.wary-fetch.async :as async]
            [dk.simongray.wary-fetch.headers :as headers]
            [dk.simongray.wary-fetch.url :as url]
            [dk.simongray.webmention.discovery :as discovery]
            [dk.simongray.webmention.page :as page]
            [dk.simongray.webmention.source :as source]))

;; Webmention 3.1.2
(defn page-endpoint
  "The endpoint that the first link or a element with rel=webmention names
  on the HTML page in the `response`, or nil."
  [{:keys [url body] :as response}]
  (when (= :html (source/kind response))
    ;; against the base element of the page too, as a browser resolves an
    ;; href, a choice of our own where Webmention 3.1.2 names the target's
    ;; URL
    (let [{:keys [elements base]} (page/parse (str body) url)]
      (first (discovery/page-urls elements base "webmention" #{:link :a})))))

(defn location
  "The URL in the Location header of the `response` to a request for
  `url`, resolved against it, or nil."
  [url {:keys [headers] :as response}]
  (some->> (headers/header headers "location") (whatwg/resolve url)))

;; Issue 89 of the spec, https://github.com/w3c/webmention/issues/89: only
;; a 307 or a 308 sends a POST on, since a client turns a POST into a GET
;; after a 301, 302 or 303
(defn redirect
  "The http or https URL that a 307 or a 308 `response` to a POST to `url`
  sends it on to, but never from https to http, or nil."
  [url {:keys [status] :as response}]
  (when (#{307 308} status)
    (let [to (location url response)]
      (when (and (url/web-url? to)
                 (not= ["https" "http"] (map url/scheme [url to])))
        to))))

(defn- send-on!
  "Send the `request` with `send`, and on to the endpoint that a 307 or a
  308 names, `n` times at most."
  [send request n]
  (async/then
   (send (assoc request :max-redirects 0))
   (fn [response]
     (if-let [to (and (pos? n) (redirect (:url request) response))]
       (send-on! send (assoc request :url to) (dec n))
       response))))

(defn- message
  "The start of the text `body` of an answer, or nil."
  [body]
  (when (string? body)
    (not-empty (subs body 0 (min 200 (count body))))))

;; Webmention 3.1.3: any 2xx is a success, and a 201 names a status page
(defn answered
  "The `report` of a Webmention with the endpoint's answer in the
  `response`."
  [report {:keys [status body url] :as response}]
  (let [sent?       (<= 200 status 299)
        status-page (when (= 201 status) (location url response))
        text        (when-not sent? (message body))]
    (cond-> (assoc report :outcome (if sent? :sent :failed) :status status)
      status-page (assoc :location status-page)
      text        (assoc :message text))))

(defn post!
  "Send the `request` of the Webmention in the `report` with `opts`, and
  give the report with the endpoint's answer. A 307 or a 308 sends it on
  to the endpoint that it names, :max-redirects times at most."
  [request report {:keys [send max-redirects] :as opts}]
  (async/then
   (async/attempt #(send-on! send (http/with-options request opts)
                             max-redirects))
   (fn [{:keys [value error]}]
     (if error
       (assoc report :outcome :failed :error (http/failure error))
       (answered report value)))))

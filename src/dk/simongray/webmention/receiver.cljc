(ns ^:no-doc dk.simongray.webmention.receiver
  "The steps of receiving a Webmention, as Webmention 3.2 has them:
  checking the request, verifying the source, and answering the sender."
  (:require [clojure.string :as str]
            [dk.simongray.microformats :as mf]
            [dk.simongray.wary-fetch.url :as url]
            [dk.simongray.webmention.page :as page]
            [dk.simongray.webmention.ring :as ring]
            [dk.simongray.webmention.source :as source]))

;;; The request

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

(defn received
  "The Webmention in the Ring `request` to an endpoint with `opts`, as a map
  of its :job, a map of its :source and :target, or else of the :rejection,
  the answer to a request that the endpoint doesn't take."
  [{:keys [request-method] :as request} {:keys [max-form-bytes] :as opts}]
  (if (not= :post request-method)
    ;; RFC 9110, section 15.5.6
    {:rejection (assoc-in (ring/response 405 "") [:headers "Allow"] "POST")}
    (let [fields (ring/form-fields request max-form-bytes)
          source (ring/field fields "source")
          target (ring/field fields "target")
          reason (problem source target opts)]
      (cond
        (nil? fields)
        {:rejection (ring/response 413 "The request is too large")}

        ;; Webmention 3.2.3: the reason of a 400
        reason
        {:rejection (ring/response 400 reason)}

        :else
        {:job {:source source :target target}}))))

;;; Verification

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

;; Webmention 3.2.2
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
(defn verification
  "The report of the `job` by the `response` to the request for its
  source, by `opts`."
  [{:keys [target] :as job} {:keys [status url] :as response} opts]
  (let [report (assoc job :status status :url url)]
    (cond
      (= 410 status)      (assoc report :outcome :gone)
      (<= 200 status 299) (merge report (examined target response opts))
      :else               (assoc report :outcome :failed))))

;;; The answer

;; Webmention 3.2: 202 for a Webmention that's verified later, or 201 with
;; the URL of a page that tells how it fares
(defn accepted
  "The endpoint's answer to a Webmention whose `job` went to :schedule!, by
  the :status-url-fn of `opts`."
  [job {:keys [status-url-fn] :as opts}]
  (if status-url-fn
    (assoc-in (ring/response 201 "The Webmention is accepted")
              [:headers "Location"]
              (status-url-fn job))
    (ring/response 202 "The Webmention is accepted")))

;; Webmention 3.2 and 3.2.3: 200 once the source is verified, and else
;; 400, since the source is the sender's
(defn verified
  "The endpoint's answer to a Webmention that it verified at once, by the
  `report` of the verification."
  [{:keys [outcome] :as report}]
  (case outcome
    :verified (ring/response 200 "The source links the target")
    :unlinked (ring/response 400 "The source doesn't link the target")
    :gone     (ring/response 400 "The source is gone")
    (ring/response 400 "The source can't be fetched")))

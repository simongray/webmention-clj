(ns ^:no-doc dk.simongray.webmention.ring
  "The Ring requests that a Webmention endpoint takes, and the answers it
  gives."
  (:require [dk.simongray.wary-fetch.bytes :as bytes]
            [dk.simongray.wary-fetch.url :as url])
  #?(:clj (:import [java.io InputStream])))

(defn body-text
  "The text of the `body` of a Ring request, which is a string, bytes, a
  stream or nothing, or nil when it holds more than `max-bytes`. In
  ClojureScript a stream gives nil too, so a server there reads the body
  first."
  [body max-bytes]
  (let [b (cond
            (nil? body)    (bytes/encode "")
            (string? body) (bytes/encode body)
            :else          #?(:clj  (if (bytes? body)
                                      body
                                      (.readNBytes ^InputStream body
                                                   (int (inc max-bytes))))
                              :cljs (when (instance? js/Uint8Array body)
                                      body)))]
    (when (and b (<= (bytes/length b) max-bytes))
      (bytes/decode b))))

;; URL Standard, section 5.1: a name without = has an empty value
(defn- fields
  "The fields of the form `s` in application/x-www-form-urlencoded, as pairs
  of a name and a value."
  [s]
  (for [[k v] (url/query-pairs s)]
    [k (or v "")]))

(defn form-fields
  "The fields of the form that the Ring `request` posts, as pairs of a name
  and a value, from its :form-params or else from a body of `max-bytes` at
  most. Nil when the body is larger."
  [{:keys [form-params body] :as request} max-bytes]
  (if (seq form-params)
    (for [[k v] form-params
          v     (if (sequential? v) v [v])]
      [(name k) v])
    (some-> (body-text body max-bytes) fields)))

(defn field
  "The value of the field `k` in the form `fields`, the last when there are
  several, or nil for an empty one."
  [fields k]
  (not-empty (last (keep (fn [[n v]] (when (= k n) v)) fields))))

(defn response
  "The Ring response of `status` with the plain text `body`, which a
  browser takes as it is rather than sniffing for another media type."
  [status body]
  {:status  status
   :headers {"Content-Type"           "text/plain; charset=utf-8"
             "X-Content-Type-Options" "nosniff"}
   :body    body})

(defn handler
  "A Ring handler that answers by the function `f` of a request, and that
  takes the arguments of a synchronous and of an asynchronous handler. The
  answer of `f` can be a promise in ClojureScript, and so can the answer of
  the synchronous handler then."
  [f]
  (fn
    ([request]
     (f request))
    ([request respond raise]
     #?(:clj  (let [{:keys [answer error]} (try
                                             {:answer (f request)}
                                             (catch Throwable e
                                               {:error e}))]
                (if error
                  (raise error)
                  (respond answer)))
        :cljs (-> (js/Promise.resolve nil)
                  (.then #(f request))
                  (.then respond raise))))))

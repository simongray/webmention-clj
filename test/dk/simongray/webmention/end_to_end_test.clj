(ns dk.simongray.webmention.end-to-end-test
  "A sender, a receiver and the source and targets of their Webmentions,
  which talk over HTTP on localhost, with the scenarios of
  webmention.rocks's discovery tests 1 to 23 and receiver tests 1 and 2."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.wary-fetch :as http]
            [dk.simongray.wary-fetch.url :as url]
            [dk.simongray.webmention :as wm]
            [dk.simongray.webmention.test-support :as support :refer [with-server]]))

(def anywhere
  "The options of requests to localhost."
  {:allow-pred (constantly true)
   :send       http/send!})

(defn- text
  "The Ring response of `status` with the text `body`."
  [status body]
  {:status status :headers {"Content-Type" "text/plain"} :body body})

(defn- html
  "The Ring response of a 200 with the HTML `body` and the `headers`."
  ([body]
   (html body {}))
  ([body headers]
   {:status  200
    :headers (merge {"Content-Type" "text/html; charset=utf-8"} headers)
    :body    body}))

(defn- form
  "The fields of the form that the Ring `request` posts."
  [request]
  (url/query-params (slurp (:body request))))

(defn- rocks
  "A Ring handler for the discovery tests of webmention.rocks on the server
  at the URL in the atom `base`. It keeps the path, the query string, the
  Content-Type and the form of each POST in the atom `posts`.

  Test 23's target redirects to a page with a key that it answers only
  once, as webmention.rocks does, and so does the endpoint that the page
  names."
  [base posts]
  (let [keys (atom #{})
        key! #(let [k (str (random-uuid))] (swap! keys conj k) k)
        use! #(let [[old _] (swap-vals! keys disj %)] (contains? old %))]
    (fn [{:keys [request-method uri query-string headers] :as request}]
      (let [tests (support/discovery-tests @base)
            n     (some-> (re-find #"^/test/(\d+)$" uri) second parse-long)
            page  (re-find #"^/test/23/page/([^/]+)$" uri)
            wm23  (re-find #"^/test/23/page/webmention-endpoint/([^/]+)$" uri)]
        (cond
          (= :post request-method)
          (do (swap! posts conj {:path         (cond-> uri query-string (str "?" query-string))
                                 :content-type (get headers "content-type")
                                 :form         (form request)})
              (if (and wm23 (not (use! (second wm23))))
                (text 400 "Code expired")
                (text 202 "Accepted")))

          (= "/test/23/page" uri)
          {:status 302 :headers {"Location" (str "page/" (key!))} :body ""}

          page
          (if (use! (second page))
            (let [k (key!)]
              (html (str "<a rel=\"webmention\" href=\"webmention-endpoint/" k "\">endpoint</a>")
                    {"Link" (str "<webmention-endpoint/" k ">; rel=webmention")}))
            (text 410 "Code expired"))

          (contains? tests n)
          (let [{:keys [headers html]} (get tests n)]
            {:status 200 :headers (merge {"Content-Type" "text/html"} headers) :body html})

          :else
          (text 404 "Not found"))))))

(deftest discovery-tests-of-webmention-rocks
  (let [base  (atom nil)
        posts (atom [])]
    (with-server (rocks base posts)
      (fn [url]
        (reset! base url)
        (let [source  "https://me.example/notes/1"
              send!   #(wm/send! source (str url "/test/" %) anywhere)
              tests   (support/discovery-tests url)]
          (testing "1 to 22"
            (doseq [[n {:keys [endpoint]}] (sort tests)]
              (reset! posts [])
              (let [report (send! n)]
                (is (= [:sent 202 (str url endpoint)] ((juxt :outcome :status :endpoint) report))
                    (str "test " n))
                (is (= [{:path         endpoint
                         :content-type "application/x-www-form-urlencoded"
                         :form         {"source" source "target" (str url "/test/" n)}}]
                       @posts)
                    (str "test " n ": the one POST, with the form alone in its body")))))
          (testing "23: a target that redirects, with keys that work once"
            (reset! posts [])
            (let [{:keys [outcome status endpoint]} (send! "23/page")]
              (is (= [:sent 202] [outcome status]))
              (is (re-matches #".*/test/23/page/webmention-endpoint/[^/]+" endpoint)
                  "relative to the page after the redirect")
              (is (= 1 (count @posts))))))))))

(defn- receiver
  "A Ring handler for a site at the URL in the atom `base`, with a
  Webmention endpoint at /webmention that gives its jobs to the atom
  `jobs`, a post at /posts/hello that names it, and the pages of the atom
  `pages`, a map of paths to Ring responses."
  [base jobs pages]
  (let [endpoint (wm/handler {:target-pred #(str/starts-with? % (str @base "/posts/"))
                              :schedule!   #(swap! jobs conj %)})]
    (fn [{:keys [uri] :as request}]
      (cond
        (= "/webmention" uri)      (endpoint request)
        (= "/old-webmention" uri)  {:status 307 :headers {"Location" "/webmention"} :body ""}
        (= "/posts/hello" uri)     (html "<article class=h-entry><h1 class=p-name>Hello</h1></article>"
                                         {"Link" "</webmention>; rel=\"webmention\""})
        (= "/posts/moved" uri)     (html "<link rel=webmention href=/old-webmention>")
        (contains? @pages uri)     (get @pages uri)
        :else                      (text 404 "Not found")))))

(defn- run-jobs!
  "Verify each job in the atom `jobs` and give the reports, without their
  microformats."
  [jobs]
  (let [[js _] (reset-vals! jobs [])]
    (mapv #(dissoc (wm/verify! % anywhere) :mf2) js)))

(deftest a-sender-and-a-receiver
  (let [base  (atom nil)
        jobs  (atom [])
        pages (atom {})]
    (with-server (receiver base jobs pages)
      (fn [url]
        (reset! base url)
        (let [source (str url "/notes/1")
              target (str url "/posts/hello")
              reply  #(html (str "<article class=h-entry><p class=e-content>" %
                                 "</p><a class=u-url href=/notes/1>link</a></article>"))
              send!  #(wm/send! source target anywhere)]
          (testing "a reply"
            (swap! pages assoc "/notes/1" (reply "Nice <a class=u-in-reply-to href=/posts/hello>post</a>!"))
            (is (= [:sent 202 (str url "/webmention")] ((juxt :outcome :status :endpoint) (send!))))
            (is (= [{:source source :target target}] @jobs) "the endpoint gives the job, but doesn't fetch the source")
            (let [[report & more] (run-jobs! jobs)]
              (is (nil? more))
              (is (= {:source source :target target :status 200 :url source :outcome :verified} report))))
          (testing "its microformats"
            (wm/send! source target anywhere)
            (let [[{:keys [mf2]}] (map #(wm/verify! % anywhere) (first (reset-vals! jobs [])))]
              (is (= [target] (get-in mf2 [:items 0 :properties :in-reply-to])))))
          (testing "an update that removes the link"
            (swap! pages assoc "/notes/1" (reply "Never mind"))
            (send!)
            (is (= [:unlinked] (map :outcome (run-jobs! jobs)))))
          (testing "a deletion"
            (swap! pages assoc "/notes/1" (text 410 "Gone"))
            (send!)
            (is (= [[:gone 410]] (map (juxt :outcome :status) (run-jobs! jobs)))))
          (testing "a source that moved"
            (swap! pages assoc
                   "/notes/1"       {:status 301 :headers {"Location" "/notes/1-hello"} :body ""}
                   "/notes/1-hello" (reply "<a href=/posts/hello>Hello</a> again"))
            (send!)
            (is (= [[:verified (str url "/notes/1-hello")]] (map (juxt :outcome :url) (run-jobs! jobs)))))
          (testing "an endpoint that moved for good, by a 307"
            (is (= [:sent 202] ((juxt :outcome :status) (wm/send! source (str url "/posts/moved") anywhere)))))
          (testing "a target that the receiver doesn't take"
            (let [{:keys [outcome status message]} (wm/send! source (str url "/elsewhere") (assoc anywhere :endpoint (str url "/webmention")))]
              (is (= [:failed 400 "The target doesn't take Webmentions"] [outcome status message]))))
          (testing "the sources of strangers are public alone"
            (reset! jobs [{:source source :target target}])
            (is (= [[:failed :dk.simongray.wary-fetch/refused]]
                   (map (juxt :outcome (comp :type :error)) (map wm/verify! (first (reset-vals! jobs [])))))))
          (testing "every target of a page, each once"
            (let [links   (wm/links (str "<a href=/posts/hello>a</a> <a href=" url "/posts/hello>b</a>"
                                         " <a href=/nowhere>c</a>")
                                    source)
                  reports (wm/send-all! source links anywhere)]
              (is (= [[target :sent] [(str url "/nowhere") :no-endpoint]] (map (juxt :target :outcome) reports))))))))))

(deftest receiver-tests-of-webmention-rocks
  (let [jobs     (atom [])
        endpoint (wm/handler {:target-pred (constantly true) :schedule! #(swap! jobs conj %)})]
    (with-server endpoint
      (fn [url]
        (let [post! #(http/send! (wm/request url %1 %2))]
          (testing "1: a valid Webmention is taken with 200, 201 or 202, and a Location with 201 alone"
            (let [{:keys [status headers]} (post! "https://webmention.rocks/receive/1/abc" "https://me.example/post")]
              (is (= 202 status))
              (is (nil? (get headers "location")))))
          (testing "2: an invalid source, target, or both, with 400"
            (is (= [400 400 400]
                   (map (comp :status #(http/send! (assoc (wm/request url "https://a.example/" "https://b.example/")
                                                          :body %)))
                        ["source=jwoijgoisdjlskjegisvjowuehjtkx&target=https%3A%2F%2Fme.example%2Fpost"
                         "source=https%3A%2F%2Fwebmention.rocks%2Freceive%2F2&target=owiejduvyeiwljjjcjmvbpsouehgd"
                         "source=sjuhvhwieuhtiwudcjvhuh&target=owiejduvyeiwljjjcjmvbpsouehgd"]))))
          (is (= 1 (count @jobs))))))))

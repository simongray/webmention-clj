(ns dk.simongray.webmention-test
  "Webmention for senders and receivers: discovery, sending, the endpoint
  and verification. A case of webmention.rocks's discovery or receiver
  tests is named by its number. Requests go to a :send of the tests' own,
  which answers from a map."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.wary-fetch.async :as async]
            [dk.simongray.webmention :as wm]
            [dk.simongray.webmention.test-support :as support :refer [run]]))

(def rocks "https://webmention.rocks")
(def source "https://me.example/notes/1")
(def target "https://you.example/posts/hello")
(def endpoint "https://you.example/webmention")

(defn- sender
  "A :send that answers each request by the `responses`, a map of a method
  and a URL to the response, or else with a 404. It keeps each request in
  the atom `sent`."
  [sent responses]
  (fn [{:keys [method url] :as request}]
    (swap! sent conj request)
    (merge {:status 404 :url url :headers {} :body ""}
           (get responses [(or method :get) url]))))

(defn- html
  "The response of a 200 with the HTML `s`, from the URL `url`."
  [url s]
  {:status 200 :url url :headers {"content-type" "text/html; charset=utf-8"} :body s})

(deftest the-endpoint-of-a-target
  (testing "1 to 22: webmention.rocks's discovery tests"
    (doseq [[n {:keys [headers html endpoint]}] (sort (support/discovery-tests rocks))]
      (is (= (str rocks endpoint)
             (wm/discover {:url     (str rocks "/test/" n)
                           :headers (assoc (update-keys headers str/lower-case)
                                           "content-type" "text/html")
                           :body    html}))
          (str "test " n))))
  (testing "a relative URL counts from the URL after the redirects, as 23 tests"
    (is (= "https://you.example/b/webmention"
           (wm/discover {:url     "https://you.example/b/page"
                         :headers {"link" "<webmention>; rel=webmention"}}))))
  (testing "a page"
    (let [page #(wm/discover {:url     "https://you.example/a/page"
                              :headers {"content-type" "text/html"}
                              :body    %})]
      (is (= "https://cdn.example/x/wm" (page "<base href='https://cdn.example/x/'><link rel=webmention href=wm>"))
          "against the base of the page")
      (is (= "https://you.example/wm" (page "<template><link rel=webmention href=/no></template><a rel=webmention href=/wm>"))
          "not inside a template")
      (is (= "https://you.example/wm" (page "<link rel='Webmention' href=/wm>")) "a rel in any case")
      (is (= "https://you.example/wm" (page "<area rel=webmention href=/no><a rel=webmention href=/wm>"))
          "a link or an a element alone, not an area")
      (is (nil? (page "<link rel=webmention href='mailto:me@you.example'>")) "an endpoint that isn't http or https")
      (is (nil? (page "<p>No endpoint")))))
  (testing "the HTML of a document that isn't HTML"
    (is (nil? (wm/discover {:url     target
                            :headers {"content-type" "text/plain"}
                            :body    "<link rel=webmention href=/wm>"}))))
  (testing "a document without a media type"
    (is (= endpoint (wm/discover {:url target :headers {} :body "<link rel=webmention href=/webmention>"}))))
  (is (nil? (wm/discover {:url target :headers {} :body nil}))))

(deftest discovering
  (let [sent (atom [])
        send (sender sent {[:get target]   (assoc-in (html target "<link rel=webmention href=/webmention>")
                                                     [:headers "cache-control"] "max-age=600")
                           [:get endpoint] {:status 500}})]
    (run #(async/then (wm/discover! target {:send send})
                      (fn [{:keys [status endpoint not-before]}]
                        (is (= [200 "https://you.example/webmention"] [status endpoint]))
                        (is (inst? not-before) "the time until which the endpoint can be kept")
                        (let [{:keys [headers user-agent]} (first @sent)]
                          (is (= [wm/accept wm/user-agent] [(get headers "Accept") user-agent])))))
         #(async/then (wm/discover! endpoint {:send send})
                      (fn [answer] (is (= {:status 500} answer) "no endpoint in a page that isn't a success")))
         #(async/then (wm/discover! target {:send (fn [_] (throw (ex-info "No route" {})))})
                      (fn [{:keys [error]}] (is (= "No route" (:reason error))))))))

(deftest the-request-of-a-webmention
  (testing "21: the query string of the endpoint stays, and the form is the body"
    (is (= {:method  :post
            :url     (str endpoint "?query=yes")
            :headers {"Content-Type" "application/x-www-form-urlencoded"}
            :body    "source=https%3A%2F%2Fme.example%2Fnotes%2F1&target=https%3A%2F%2Fyou.example%2Fposts%2Fhello"}
           (wm/request (str endpoint "?query=yes") source target))))
  (is (thrown? #?(:clj Exception :cljs js/Error) (wm/request endpoint "mailto:me@me.example" target))))

(deftest sending
  (let [sent     (atom [])
        page     (html target "<link rel=webmention href=/webmention>")
        answer   (fn [status & {:as more}] (merge {:status status :url endpoint :headers {} :body ""} more))
        send!    (fn [answers & {:as opts}]
                   (reset! sent [])
                   (wm/send! source target (merge {:send (sender sent (merge {[:get target] page} answers))}
                                                  opts)))
        expect   (fn [expected answers & opts]
                   #(async/then (apply send! answers opts)
                                (fn [report] (is (= expected (select-keys report (keys expected)))))))]
    (run (expect {:outcome :sent :status 202 :endpoint endpoint :source source :target target}
                 {[:post endpoint] (answer 202)})
         #(is (= [[nil target] [:post endpoint]] (map (juxt :method :url) @sent)) "discovery, then the POST")
         #(is (= 0 (:max-redirects (second @sent))) "the POST follows no redirect by itself")
         (expect {:outcome :sent :status 200} {[:post endpoint] (answer 200)})
         (expect {:outcome :sent :status 201 :location "https://you.example/status/1"}
                 {[:post endpoint] (answer 201 :headers {"location" "/status/1"})})
         (expect {:outcome :failed :status 400 :message "The target doesn't take Webmentions"}
                 {[:post endpoint] (answer 400 :body "The target doesn't take Webmentions")})
         (expect {:outcome :failed :status 500 :message (apply str (repeat 200 "x"))}
                 {[:post endpoint] (answer 500 :body (apply str (repeat 300 "x")))})
         (expect {:outcome :sent :status 202 :endpoint endpoint}
                 {[:post endpoint]              (answer 307 :headers {"location" "/webmention/2"})
                  [:post (str endpoint "/2")] (answer 202 :url (str endpoint "/2"))})
         #(is (= [:post :post] (map :method (rest @sent))) "a 307 sends the POST on")
         (expect {:outcome :sent :status 202}
                 {[:post endpoint]              (answer 308 :headers {"location" "/webmention/2"})
                  [:post (str endpoint "/2")] (answer 202 :url (str endpoint "/2"))})
         #(is (= [:post :post] (map :method (rest @sent))) "and so does a 308")
         (expect {:outcome :failed :status 302}
                 {[:post endpoint] (answer 302 :headers {"location" "/elsewhere"})})
         #(is (= 2 (count @sent)) "a 302 doesn't")
         (expect {:outcome :failed :status 307}
                 {[:post endpoint] (answer 307 :headers {"location" "http://you.example/webmention"})})
         #(is (= 2 (count @sent)) "nor a 307 from https to http")
         (expect {:outcome :failed :status 307} {[:post endpoint] (answer 307)})
         (expect {:outcome :failed :status 307}
                 {[:post endpoint] (answer 307 :headers {"location" "/webmention"})}
                 :max-redirects 2)
         #(is (= 4 (count @sent)) "a loop of redirects ends after :max-redirects")
         #(async/then (wm/send! source target {:send (fn [{:keys [method]}]
                                                       (if (= :post method)
                                                         (throw (ex-info "Connection reset" {}))
                                                         page))})
                      (fn [{:keys [outcome error] :as report}]
                        (is (= [:failed endpoint "Connection reset"] [outcome (:endpoint report) (:reason error)]))))
         #(async/then (send! {[:get target]   (assoc-in page [:headers "cache-control"] "max-age=600")
                              [:post endpoint] (answer 202)})
                      (fn [report] (is (inst? (:not-before report)) "until when the target's endpoint can be kept")))
         (expect {:outcome :no-endpoint} {[:get target] (html target "<p>Hi")})
         (expect {:outcome :failed :status 503} {[:get target] (answer 503 :url target)})
         #(is (= 1 (count @sent)) "a target that fails isn't one without an endpoint")
         #(async/then (wm/send! source "mailto:you@you.example" {:send (sender sent {})})
                      (fn [report]
                        (is (= {:outcome :failed :error {:reason "The target is not an http or https URL"}}
                               (select-keys report [:outcome :error])))))
         (expect {:outcome :sent :endpoint "https://cached.example/wm"}
                 {[:post "https://cached.example/wm"] (answer 202)}
                 :endpoint "https://cached.example/wm")
         #(is (= [:post] (map :method @sent)) "an endpoint found before spares the discovery")
         #(is (thrown? #?(:clj Exception :cljs js/Error) (wm/send! "/relative" target))))))

(deftest sending-to-several
  (let [sent    (atom [])
        answers (into {[:get "https://c.example/"] (html "https://c.example/" "<p>Hi")}
                      (for [t ["https://a.example/" "https://b.example/"]]
                        [[:get t] (html t "<link rel=webmention href=/wm>")]))]
    (run #(async/then (wm/send-all! source ["https://a.example/" "https://b.example/" "https://a.example/" "https://c.example/"]
                                    {:send (sender sent answers)})
                      (fn [reports]
                        (is (= [["https://a.example/" :failed] ["https://b.example/" :failed] ["https://c.example/" :no-endpoint]]
                               (map (juxt :target :outcome) reports))
                            "each target once, in order"))))))

(deftest the-links-of-a-page
  (is (= ["https://you.example/posts/hello" "https://me.example/about" "https://them.example/"]
         (wm/links (str "<a href='https://you.example/posts/hello'>you</a> <a href='/about'>me</a>"
                        "<img src='https://img.example/a.png'><area href='https://them.example/'>"
                        "<a href='mailto:me@me.example'>mail</a> <a href='https://you.example/posts/hello'>again</a>")
                   source))
      "of a and area, http and https alone, each once")
  (is (= ["https://them.example/"] (wm/links [:p [:a {:href "https://them.example/"} "them"]] source))
      "of Hiccup")
  (is (= ["https://cdn.example/x/a"] (wm/links "<base href='https://cdn.example/x/'><a href=a>a</a>" source))
      "against the base of the page"))

(defn- post
  "The POST of a form with the `fields` to an endpoint."
  [fields]
  {:request-method :post :form-params fields})

(defn- utf-8
  "The bytes of the text `s` in UTF-8."
  [s]
  #?(:clj  (.getBytes ^String s "UTF-8")
     :cljs (.encode (js/TextEncoder.) s)))

(deftest the-endpoint
  (let [jobs    (atom [])
        taken   (atom [])
        opts    {:target-pred #(do (swap! taken conj %) (str/starts-with? % "https://you.example/"))
                 :schedule!   #(swap! jobs conj %)}
        serve   (wm/handler opts)
        status  #(:status (serve %))]
    (testing "1: a valid Webmention, which is taken, but not fetched"
      (let [{:keys [status headers body]} (serve (post {"source" source "target" target}))]
        (is (= 202 status))
        (is (nil? (get headers "Location")) "a 202 has no Location")
        (is (string? body))
        (is (= [{:source source :target target}] @jobs))))
    (testing "201 and the URL of a status page"
      (let [serve (wm/handler (assoc opts :status-url-fn #(str "https://you.example/status?source=" (:source %))))
            {:keys [status headers]} (serve (post {"source" source "target" target}))]
        (is (= [201 "https://you.example/status?source=https://me.example/notes/1"] [status (get headers "Location")]))))
    (testing "2: an invalid source, target, or both"
      (reset! jobs [])
      (is (= [400 400 400]
             [(status (post {"source" "jwoijgoisdjlskjegisvjowuehjtkx" "target" target}))
              (status (post {"source" source "target" "owiejduvyeiwljjjcjmvbpsouehgd"}))
              (status (post {"source" "sjuhvhwieuhtiwudcjvhuh" "target" "owiejduvyeiwljjjcjmvbpsouehgd"}))]))
      (is (= [] @jobs)))
    (testing "the reasons of a 400"
      (is (= "The source must be an http or https URL" (:body (serve (post {"source" "mailto:me@me.example" "target" target})))))
      (is (= "The source must be an http or https URL" (:body (serve (post {"target" target})))))
      (is (= "The target must be an http or https URL" (:body (serve (post {"source" source "target" "ftp://you.example/"})))))
      (is (= "The source and the target must be different URLs"
             (:body (serve (post {"source" target "target" "https://YOU.example:443/posts/hello"})))))
      (is (= "The target doesn't take Webmentions" (:body (serve (post {"source" source "target" "https://them.example/"}))))))
    (testing "the target without its fragment decides"
      (reset! taken [])
      (serve (post {"source" source "target" (str target "#comments")}))
      (is (= [target] @taken))
      (is (= (str target "#comments") (:target (last @jobs))) "but the job keeps it"))
    (testing "the URLs normalized before they're checked"
      (reset! jobs [])
      (is (= 202 (status (post {"source" "HTTPS://ME.example:443/notes/1" "target" "https://YOU.example/posts/hello"}))))
      (is (= [{:source source :target target}] @jobs))
      (is (= "The source and the target must be different URLs"
             (:body (serve (post {"source" (str target "#reply") "target" target})))))
      (let [serve (wm/handler (assoc opts :target-pred #(str/starts-with? % "https://you.example/posts/")))]
        (is (= "The target doesn't take Webmentions"
               (:body (serve (post {"source" source "target" "https://you.example/posts/../private/1"})))))))
    (testing "a form in the body"
      (reset! jobs [])
      (is (= 202 (status {:request-method :post
                          :body           "source=https%3A%2F%2Fme.example%2Fnotes%2F1&target=https://you.example/posts/hello"})))
      (is (= [{:source source :target target}] @jobs)))
    (testing "the fields of a form that Ring has read, by keyword or name"
      (is (= 202 (status (post {:source source :target target})))))
    (testing "the last of a repeated field, and an empty one as none"
      (reset! jobs [])
      (is (= 202 (status (post {"source" ["mailto:me@me.example" source] "target" target}))))
      (is (= 202 (status {:request-method :post
                          :body           (str "source=x&&source=" source "&target=" target)})))
      (is (= [{:source source :target target} {:source source :target target}] @jobs))
      (is (= "The source must be an http or https URL"
             (:body (serve {:request-method :post :body (str "source=&target=" target)}))))
      (is (= 400 (status {:request-method :post})) "nor a body at all"))
    (testing "a body of bytes, or on the JVM a stream"
      (let [form (str "source=" source "&target=" target)]
        (is (= 202 (status {:request-method :post :body (utf-8 form)})))
        #?(:clj (is (= 202 (status {:request-method :post :body (java.io.ByteArrayInputStream. (utf-8 form))}))))
        #?(:clj (is (= 413 (status {:request-method :post
                                    :body           (java.io.ByteArrayInputStream. (utf-8 (apply str form (repeat 70000 "a"))))}))))))
    (is (= 413 (status {:request-method :post :body (apply str "source=" (repeat 70000 "a"))})))
    (is (= [405 "POST"] ((juxt :status #(get-in % [:headers "Allow"])) (serve {:request-method :get}))))
    (is (thrown? #?(:clj Exception :cljs js/Error) (wm/handler {:schedule! identity})))))

(defn- answered
  "The answer of the asynchronous Ring handler `serve` to the `request`, in
  a promise in ClojureScript."
  [serve request]
  #?(:clj  (let [p (promise)]
             (serve request #(deliver p %) #(deliver p %))
             @p)
     :cljs (js/Promise. (fn [resolve] (serve request resolve resolve)))))

(deftest the-asynchronous-handler
  (let [serve  (wm/handler {:target-pred (constantly true) :schedule! identity})
        broken (wm/handler {:target-pred (constantly true) :schedule! #(throw (ex-info "The queue is down" {:job %}))})]
    (run #(async/then (answered serve (post {"source" source "target" target}))
                      (fn [{:keys [status]}] (is (= 202 status))))
         #(async/then (answered broken (post {"source" source "target" target}))
                      (fn [e] (is (= "The queue is down" (ex-message e)) "an error goes to raise"))))))

(deftest verifying-at-once
  (let [reports (atom [])
        serve   (fn [response]
                  (wm/handler {:target-pred (constantly true)
                               :report!     #(swap! reports conj (:outcome %))
                               :send        (sender (atom []) {[:get source] response})}))
        answer  (fn [response expected]
                  #(async/then ((serve response) (post {"source" source "target" target}))
                               (fn [{:keys [status body]}] (is (= expected [status body])))))]
    (run (answer (html source (str "<a href=" target ">Hi</a>")) [200 "The source links the target"])
         (answer (html source "<p>Hi") [400 "The source doesn't link the target"])
         (answer {:status 410 :url source} [400 "The source is gone"])
         (answer {:status 500 :url source} [400 "The source can't be fetched"])
         #(is (= [:verified :unlinked :gone :failed] @reports))
         #(is (= 400 (:status ((serve (html source "")) (post {"source" source}))))
              "a Webmention that's wrong isn't verified"))))

(defn- verify
  "Verify a Webmention from `source` to `target` whose source answers with
  the `response`, and check the report against `expected`, its keys
  alone."
  ([expected response]
   (verify expected response target))
  ([expected response target]
   #(async/then (wm/verify! {:source source :target target}
                            {:send (sender (atom []) {[:get source] (merge {:url source :headers {}} response)})})
                (fn [report]
                  (is (= expected (select-keys report (keys expected))) (pr-str response))))))

(defn- page
  "The 200 of an HTML page `s`."
  [s]
  (html source s))

(defn- json
  "The 200 of the JSON `s` of `media-type`."
  [media-type s]
  {:status 200 :headers {"content-type" media-type} :body s})

(deftest verifying
  (run (verify {:outcome :verified :source source :target target :status 200 :url source}
               (page (str "<p>Re <a href='" target "'>this</a>")))
       (verify {:outcome :unlinked} (page "<p>Re nothing"))
       (verify {:outcome :verified :title "A reply"}
               (page (str "<title> A reply </title><a href='" target "'>this</a>")))
       ;; the attributes of HTML whose value is a URL
       (verify {:outcome :verified} (page "<img src='https://you.example/posts/hello' alt=''>"))
       (verify {:outcome :verified} (page "<video src='https://you.example/posts/hello'></video>"))
       (verify {:outcome :verified} (page "<blockquote cite='https://you.example/posts/hello'>Hi</blockquote>"))
       (verify {:outcome :verified} (page "<img srcset='/a.png 1x, https://you.example/posts/hello 2x'>"))
       (verify {:outcome :verified} (page "<head><link rel=in-reply-to href='https://you.example/posts/hello'></head>"))
       (verify {:outcome :verified} (page "<a href=/x ping='https://a.example/ https://you.example/posts/hello'>x</a>"))
       ;; XHTML, and HTML without a media type, but not text
       (verify {:outcome :verified :title "X"}
               (json "application/xhtml+xml" (str "<html xmlns='http://www.w3.org/1999/xhtml'><head><title>X</title></head>"
                                                  "<body><a href='" target "'>x</a></body></html>")))
       (verify {:outcome :verified :title "X"} {:status 200 :body (str "<title>X</title><a href='" target "'>x</a>")})
       (verify {:outcome :verified} {:status 200 :body (str "See " target)})
       ;; resolved against the page, and normalized
       (verify {:outcome :verified} (page "<a href='//you.example/posts/hello'>a</a>"))
       (verify {:outcome :verified} (page "<a href='/posts/hello'>relative</a>") "https://me.example/posts/hello")
       (verify {:outcome :verified} (page "<base href='https://you.example/'><a href='posts/hello'>base</a>"))
       (verify {:outcome :verified} (page "<a href='HTTPS://YOU.example:443/posts/hello'>case</a>"))
       ;; but otherwise exact
       (verify {:outcome :verified} (page "<a href='https://you.example/posts/hello#c1'>c</a>") (str target "#c1"))
       (verify {:outcome :unlinked} (page "<a href='https://you.example/posts/hello'>c</a>") (str target "#c1"))
       (verify {:outcome :unlinked} (page "<a href='https://you.example/posts/hello/'>slash</a>"))
       (verify {:outcome :unlinked} (page "<a href='http://you.example/posts/hello'>http</a>"))
       ;; not as text, in a comment or in a template
       (verify {:outcome :unlinked} (page "<p>https://you.example/posts/hello, as text"))
       (verify {:outcome :unlinked} (page "<!-- <a href='https://you.example/posts/hello'> -->"))
       (verify {:outcome :unlinked} (page "<template><a href='https://you.example/posts/hello'>t</a></template>"))
       ;; a deletion
       (verify {:outcome :gone :status 410} {:status 410 :body "Gone"})
       (verify {:outcome :gone :status 200}
               (page (str "<meta http-equiv=Status content='410 Gone'><a href='" target "'>a</a>")))
       (verify {:outcome :verified}
               (page (str "<meta http-equiv=Status content='200 OK'><a href='" target "'>a</a>")))
       (verify {:outcome :failed :status 404} {:status 404})
       (verify {:outcome :failed :status 500} {:status 500})
       #(async/then (wm/verify! {:source source :target target}
                                {:send (fn [_] (throw (ex-info "Timed out" {:type :dk.simongray.wary-fetch/timeout})))})
                    (fn [{:keys [outcome error]}]
                      (is (= [:failed "Timed out" :dk.simongray.wary-fetch/timeout]
                             [outcome (:reason error) (:type error)]))))
       ;; the URL of the source after its redirects
       (verify {:outcome :verified :url "https://me.example/notes/1-hello"}
               (assoc (page (str "<a href='" target "'>a</a>")) :url "https://me.example/notes/1-hello"))
       ;; but not when that's the target itself
       (verify {:outcome :unlinked :url target} (assoc (page (str "<a href='" target "'>a</a>")) :url target))
       ;; JSON, by its values
       (verify {:outcome :verified} (json "application/json" (str "{\"inReplyTo\": \"" target "\", \"tags\": [1, null]}")))
       (verify {:outcome :verified} (json "application/activity+json"
                                          "{\"object\": {\"url\": [\"https:\\/\\/you.example\\/posts\\/hello\"]}}"))
       (verify {:outcome :unlinked} (json "application/json" (str "{\"" target "\": 1}")))
       (verify {:outcome :verified} (json "application/json" (str "not JSON, but " target)))
       (verify {:outcome :verified} (json "application/json" (str "[1] and then " target)))
       (verify {:outcome :verified} (json "application/json" (str (apply str (repeat 100000 "[")) target)))
       ;; other text, by the text of the URL
       (verify {:outcome :verified} (json "text/plain" (str "See " target ".")))
       (verify {:outcome :unlinked} (json "text/plain" "See nothing"))
       (verify {:outcome :verified} (json "application/atom+xml" (str "<feed><link href='" target "'/></feed>")))
       (verify {:outcome :unlinked} (json "image/png" target))))

(deftest the-request-for-a-source
  (let [sent (atom [])]
    (run #(wm/verify! {:source source :target target} {:send (sender sent {})})
         #(let [{:keys [headers user-agent timeout-seconds max-bytes max-redirects allow-pred]} (first @sent)]
            (is (= [wm/accept wm/user-agent] [(get headers "Accept") user-agent])
                "Webmention 3.2.2: the media types whose links are read")
            (is (= [5 (* 1024 1024) 5] [timeout-seconds max-bytes max-redirects])
                "Webmention 4.2: limits on time, size and redirects")
            (is (fn? allow-pred) "Webmention 4.3: a check of the address")))))

(deftest the-microformats-of-a-source
  (run #(async/then
         (wm/verify! {:source source :target target}
                     {:send (sender (atom []) {[:get source] (page (str "<article class=h-entry><p class=e-content>"
                                                                        "Nice <a class=u-in-reply-to href='" target "'>post</a>"
                                                                        "</article>"))})})
         (fn [{:keys [mf2]}]
           (is (= [target] (get-in mf2 [:items 0 :properties :in-reply-to])))))
       #(async/then
         (wm/verify! {:source source :target target}
                     {:send (sender (atom []) {[:get source] (page "<p>Re nothing")})})
         (fn [report] (is (not (contains? report :mf2)) "only for a source that links the target")))))

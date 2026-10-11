(ns dk.simongray.webmention.test-support
  "What the tests share: a test of steps that give promises in
  ClojureScript, the scenarios of webmention.rocks's discovery tests, and
  on the JVM a server of Ring handlers on localhost."
  (:require #?(:clj [clojure.string :as str])
            #?(:cljs [clojure.test :refer [is async]]))
  #?(:clj (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
                   [java.net InetSocketAddress])))

(defn run
  "Run the steps `fs` one after another as a test on either platform. Each
  step is a function of no arguments that gives a value or a promise.

  In ClojureScript, a deftest runs only the async test that it returns. So
  a deftest calls `run` once, as its last form, with all its steps."
  [& fs]
  #?(:clj  (doseq [f fs] (f))
     :cljs (async done
             (-> (reduce (fn [p f] (.then p (fn [_] (f)))) (js/Promise.resolve nil) fs)
                 (.catch (fn [e] (is false (str "rejected: " e))))
                 (.finally done)))))

(defn- page
  "The HTML of a discovery test's page, with the `head` and `body` HTML."
  [head body]
  (str "<!doctype html><html><head><title>Discovery Test</title>" head
       "</head><body><div class=\"h-entry\"><a class=\"p-author h-card\" href=\"/\">"
       "Webmention Rocks!</a><div class=\"e-content\">" body "</div></div></body></html>"))

(defn discovery-tests
  "The scenarios of webmention.rocks's discovery tests 1 to 22 on the server
  at `base`, by number: the :headers and the :html of the target at
  /test/n, and the path of the :endpoint that a sender finds in it. A path
  that ends in /error is one that a sender mustn't find.

  Test 23, a target that redirects, is the end-to-end test's, since it
  needs a server."
  [base]
  (let [wm    #(str "/test/" % "/webmention")
        error #(str (wm %) "/error")
        abs   #(str base %)]
    {1  {:headers  {"Link" (str "<" (wm 1) ">; rel=webmention")}
         :html     (page "" "A Link header with a relative URL")
         :endpoint (wm 1)}
     2  {:headers  {"Link" (str "<" (abs (wm 2)) ">; rel=webmention")}
         :html     (page "" "A Link header with an absolute URL")
         :endpoint (wm 2)}
     3  {:html     (page (str "<link rel=\"webmention\" href=\"" (wm 3) "\">")
                         "A link element with a relative URL")
         :endpoint (wm 3)}
     4  {:html     (page (str "<link href=\"" (abs (wm 4)) "\" rel=\"webmention\">")
                         "A link element with an absolute URL")
         :endpoint (wm 4)}
     5  {:html     (page "" (str "An <a rel=\"webmention\" href=\"" (wm 5)
                                 "\">a element</a> with a relative URL"))
         :endpoint (wm 5)}
     6  {:html     (page "" (str "An <a href=\"" (abs (wm 6))
                                 "\" rel=\"webmention\">a element</a> with an absolute URL"))
         :endpoint (wm 6)}
     7  {:headers  {"LinK" (str "<" (abs (wm 7)) ">; rel=webmention")}
         :html     (page "" "A Link header with a name in odd case")
         :endpoint (wm 7)}
     8  {:headers  {"Link" (str "<" (abs (wm 8)) ">; rel=\"webmention\"")}
         :html     (page "" "A Link header with a quoted rel")
         :endpoint (wm 8)}
     9  {:html     (page (str "<link rel=\"webmention somethingelse\" href=\""
                              (abs (wm 9)) "\">")
                         "A link element with two rel values")
         :endpoint (wm 9)}
     10 {:headers  {"Link" (str "<" (abs (wm 10)) ">; rel=\"webmention somethingelse\"")}
         :html     (page "" "A Link header with two rel values")
         :endpoint (wm 10)}
     11 {:headers  {"Link" (str "<" (wm 11) ">; rel=\"webmention\"")}
         :html     (page (str "<link rel=\"webmention\" href=\"" (error 11) "\">")
                         (str "The Link header first, before the link element and an <a href=\""
                              (error 11) "\" rel=\"webmention\">a element</a>"))
         :endpoint (wm 11)}
     12 {:html     (page (str "<link rel=\"not-webmention\" href=\"" (error 12) "\">")
                         (str "Only rel=webmention counts, so <a href=\"" (wm 12)
                              "\" rel=\"webmention\">this one</a>"))
         :endpoint (wm 12)}
     13 {:html     (page "" (str "A comment, <!-- <a href=\"" (error 13)
                                 "\" rel=\"webmention\"></a> -->, and <a href=\""
                                 (wm 13) "\" rel=\"webmention\">the endpoint</a>"))
         :endpoint (wm 13)}
     14 {:html     (page "" (str "Escaped HTML, <code>&lt;a href=\"" (error 14)
                                 "\" rel=\"webmention\"&gt;&lt;/a&gt;</code>, and <a href=\""
                                 (wm 14) "\" rel=\"webmention\">the endpoint</a>"))
         :endpoint (wm 14)}
     15 {:html     (page "<link rel=\"webmention\" href=\"\">"
                         "An empty href, so the page is its own endpoint")
         :endpoint "/test/15"}
     16 {:html     (page "" (str "An <a href=\"" (wm 16) "\" rel=\"webmention\">a element</a>"
                                 " before a <link rel=\"webmention\" href=\"" (error 16) "\">"))
         :endpoint (wm 16)}
     17 {:html     (page "" (str "A <link rel=\"webmention\" href=\"" (wm 17) "\">"
                                 " before an <a href=\"" (error 17)
                                 "\" rel=\"webmention\">a element</a>"))
         :endpoint (wm 17)}
     18 {:headers  {"Link" [(str "<" (abs (error 18)) ">; rel=\"other\"")
                            (str "<" (abs (wm 18)) ">; rel=\"webmention\"")]}
         :html     (page "" "Two Link headers")
         :endpoint (wm 18)}
     19 {:headers  {"Link" (str "<" (abs (error 19)) ">; rel=\"other\", <"
                               (abs (wm 19)) ">; rel=\"webmention\"")}
         :html     (page "" "One Link header with two links")
         :endpoint (wm 19)}
     20 {:html     (page "" (str "A <link rel=\"webmention\"> without an href, and <a href=\""
                                 (wm 20) "\" rel=\"webmention\">the endpoint</a>"))
         :endpoint (wm 20)}
     21 {:html     (page (str "<link rel=\"webmention\" href=\"" (wm 21) "?query=yes\">")
                         "An endpoint with a query string")
         :endpoint (str (wm 21) "?query=yes")}
     22 {:html     (page "<link rel=\"webmention\" href=\"22/webmention\">"
                         "An endpoint relative to the path of the page")
         :endpoint (wm 22)}}))

#?(:clj
   (defn- ring-request
     "The Ring request of the JDK server's `exchange`."
     [^HttpExchange exchange]
     (let [uri (.getRequestURI exchange)]
       {:request-method (keyword (str/lower-case (.getRequestMethod exchange)))
        :uri            (.getPath uri)
        :query-string   (.getRawQuery uri)
        :scheme         :http
        :headers        (into {}
                              (for [[k vs] (.getRequestHeaders exchange)]
                                [(str/lower-case k) (str/join ", " vs)]))
        :body           (.getRequestBody exchange)})))

#?(:clj
   (defn- respond!
     "Answer the JDK server's `exchange` with the Ring `response`, whose
     header values can be vectors of several."
     [^HttpExchange exchange {:keys [status headers body]}]
     (let [^bytes b (if (bytes? body) body (.getBytes (str body) "UTF-8"))]
       (doseq [[k v] headers
               v     (if (sequential? v) v [v])]
         (.add (.getResponseHeaders exchange) (str k) (str v)))
       ;; with the request body open, Java 11 closes the connection after
       ;; an answer with no body, and the client reuses it
       (.close (.getRequestBody exchange))
       (if (zero? (alength b))
         (.sendResponseHeaders exchange status -1)
         (do (.sendResponseHeaders exchange status (alength b))
             (with-open [out (.getResponseBody exchange)]
               (.write out b))))
       (.close exchange))))

#?(:clj
   (defn with-server
     "Call `f` with the URL of a server on localhost that answers each
     request by the Ring handler `handler`."
     [handler f]
     (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
       (.createContext server "/"
                       (reify HttpHandler
                         (handle [_ exchange]
                           (respond! exchange (handler (ring-request exchange))))))
       (.start server)
       (try
         (f (str "http://127.0.0.1:" (.getPort (.getAddress server))))
         (finally
           (.stop server 0))))))

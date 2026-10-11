(ns dk.simongray.webmention.rocks
  "The discovery tests of webmention.rocks, against the site itself:

      clojure -X:test:rocks

  They find the endpoint of each test, but send it no Webmention, which
  would need a source at a public URL. The name of the namespace doesn't
  end in -test, so CI, which shouldn't depend on the network, leaves it
  out."
  (:require [clojure.test :refer [deftest is testing]]
            [dk.simongray.webmention :as wm]
            [dk.simongray.webmention.test-support :as support]))

(def base
  "The URL of webmention.rocks."
  "https://webmention.rocks")

(deftest discovery-tests
  (testing "1 to 22"
    (doseq [[n {:keys [endpoint]}] (sort (support/discovery-tests base))]
      (is (= {:status 200 :endpoint (str base endpoint)}
             (select-keys (wm/discover! (str base "/test/" n)) [:status :endpoint]))
          (str "test " n))))
  (testing "23, whose target redirects"
    (is (re-matches #"https://webmention\.rocks/test/23/page/webmention-endpoint/\w+"
                    (:endpoint (wm/discover! (str base "/test/23/page")))))))

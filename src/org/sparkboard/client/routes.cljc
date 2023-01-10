(ns org.sparkboard.client.routes)

(def routes
  [["/" {:name :home}]
   ["/playground" {:name :playground}]
   ["/slack/invite-offer" {:name :slack/invite-offer}]
   ["/slack/link-complete" {:name :slack/link-complete}]
   ["/auth-test" {:name :auth-test}]])

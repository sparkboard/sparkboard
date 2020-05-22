(ns org.sparkboard.slack.browser)

(defn deep-link-to-home [app-id team-id]
  (str "https://slack.com/app_redirect?team=" team-id "&app=" app-id))
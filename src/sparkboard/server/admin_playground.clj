(ns sparkboard.server.admin-playground
  (:require [clojure.string :as str]
            [wkok.openai-clojure.api :as ai]
            [sparkboard.schema :as schema]
            [sparkboard.server.env :as env]
            [datalevin.core :as d]
            [re-db.api :as db]
            [sparkboard.datalevin :as dl :refer [conn]])
  (:import [java.time LocalDateTime
                      ZoneId
                      Instant]))

(defn get-year [date]
  (.getYear (LocalDateTime/ofInstant (.toInstant date) (ZoneId/systemDefault))))

(defn ?helper [helper & args]
  (case helper :get-year (apply get-year args)))

(def options {:api-key (-> env/config :prod :openai/api-key)
              :organization (-> env/config :prod :openai/organization)})

(defn ms [messages]
  (for [[role content] (partition 2 messages)] {:role (name role) :content content}))

(def models {:completions #{:text-ada-001
                            :text-babbage-001}
             :chat #{:gpt-3.5-turbo
                     :gpt-4}})

(defn ada [prompt]
  (ai/create-completion
   {:model "text-ada-001"
    :prompt prompt}
   options))

(defn turbo [model prompt]
  (ai/create-chat-completion {:model ({3 "gpt-3.5-turbo"
                                       4 "gpt-4"} model)
                              :temperature 0.2
                              :messages (ms [:system "You are a Clojure datalog expert."
                                             :user prompt])}
                             options))

(defn cost [{:keys [usage model]}]
  (cond (str/starts-with? model "gpt-4")
        (str "$" (+ (-> (:prompt_tokens usage) (/ 1000) (* 0.03))
                    (-> (:completion_tokens usage) (/ 1000) (* 0.06))))
        (str/starts-with? model "gpt-3.5")
        (str "$" (+ (-> (:total_tokens usage) (/ 1000) (* 0.002))))))

(defn query [model s]
  (-> (turbo model (str "Please write a Datomic-flavored datalog query for the following English-language request.
  Only return the query, nothing else. You can use built-in Clojure functions and ?helper functions. ?helper takes
  the name of a helper function and its arguments. Here are the helper functions:
  :get-year [date] - returns the year of a java.util.Date object

  Always include `:in $ ?helper` in the query.

  # Example
  User: Find all the projects in board \"x\".
  System:
  [:find (pull ?project [*])
   :where
   [?board :board/id \"x\"]
   [?project :project/board ?board]
   :in $ ?helper]
  User: List boards whose names contain \"Waterloo\"
  System:
  [:find (pull ?board [*])
   :where
   [?board :board/title ?title]
   [(clojure.string/includes? ?title Waterloo)]
   :in $ ?helper]
  User: Find boards older than 2017. Return their titles and years.
  System:
  [:find ?title ?year
   :where
   [?board :board/title ?title]
   [?board :ts/created-at ?created-at]
   [(?helper :get-year ?created-at) ?year]
   [(< ?year 2017)]
   :in $ ?helper]

Here are all the identifiers in our schema: " (keys schema/sb-schema) ". Here is the query in english: "
                  s))
      (doto (-> ((juxt :model cost :usage)) println))
      :choices first :message :content read-string
      (doto prn)
      (d/q (d/db conn) ?helper)))

(comment
 (-> (db/where [:entity/id]) first :entity/created-at get-year)
 (query 3 "Find all the projects in board 'X'.")
 (query 3 "List all board names and their ids.")
 (query 4 "List boards whose names contain \"Waterloo\"")
 (query 3 "Count the number of boards newer than 2019.")
 (d/q '[:find ?title ?year
        :where
        [?board :entity/title ?title]
        [?board :entity/created-at ?created-at]
        [(?helper :get-year ?created-at) ?year]
        [(< ?year 2017)]
        :in $ ?helper]
      (d/db conn)
      ?helper)

 [:find ?title ?year
  :where
  [?board :entity/title ?title]
  [?board :entity/created-at ?created-at]
  [(?helper :get-year ?created-at) ?year]
  [(< ?year 2017)]
  :in $ ?helper]

 (d/q
  '[:find ?title ?year
    :where
    [?board :entity/title ?title]
    [?board :entity/created-at ?date]
    [(?helper :get-year ?date) ?year]
    [(< ?year 2017)]
    :in $ ?helper]
  (d/db conn)
  ?helper))

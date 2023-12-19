(ns sb.migration.firebase-schema
  (:require [clojure.string :as str]
            [clojure.string :as str]
            [jsonista.core :as json]
            [malli.core :as m]))


(def database-url (:databaseURL (env/config :firebase/app-config)))
(def database-secret (env/config :firebase/database-secret))

(defn json-path [path]
  (str database-url
       (str/replace-first path #"^\/*" "/")                 ;; needs a single leading "/"
       ".json"
       "?auth=" database-secret))

  ;; NB: keywordized keys are not reliable due to mismatch between Firebase and Clojure
(def fetch-json (comp json/read-value slurp json-path))

(comment
  (get (fetch-json "settings/demo") "title")

  (fetch-json "privateSettings")

  )


(def db-paths
  ["_parent"
   "collection"
   "domain"
   "feedback"
   "invalidations"
   "org"
   "privateSettings"
   "roles"
   "settings"])

(def db
  (into {} (map (juxt identity fetch-json) db-paths)))

;; TODO consider double-checking reference values to see if they actually exist

(def parent-schema
  [:map-of string? ;; org
   [:map-of string? ;; board
    boolean?]])

(def collection-schema
  [:map-of string? ; collection
   [:map
    ["boards" [:map-of
               string? ; board
               boolean?]]
    ["domain" string?]
    ["title" string?]
    ["images" [:map-of string? string?]]]])

(def domain-schema
  [:map-of
   string? ; board?
   string?])

(def feedback-schema
  ;; acquired with `mp/provide` plus hand-tuning
  [:map-of string? ; ID like "-LSKg04e5jkkCHXncDOJ"
   [:map
    ["accountId" {:optional true} string?]
    ["createdAt" {:optional true} int?]
    ["label" {:optional true} string?]
    ["message" string?]
    ["url" {:optional true} string?]
    ["userId" {:optional true} string?]]])

(def invalidations-schema
  [:map-of string?
   [:map-of string?
    [:or number?
     [:map-of string?
      [:or
       number?
       [:map
        ["group"         {:optional true} [:map-of string? [:map ["ts" number?]]]]
        ["membership"    {:optional true} [:map-of string? [:map ["ts" number?]]]]
        ["notifications" {:optional true} [:map-of string? [:map ["ts" number?]]]]
        ["ts"            {:optional true} number?]]]]]]])

(def org-schema
  ;; acquired with `[:map-of string? (mp/provide (vals (get db "org"))) ]`
  [:map-of string?
   [:map
    ["localeSupport"      {:optional true} [:vector string?]]
    ["allowPublicViewing" {:optional true} boolean?]
    ["boardTemplate"      {:optional true} string?]
    ["creator"            {:optional true} string?]
    ["domain" string?]
    ["images"             {:optional true} [:map
                                            ["logo" string?]
                                            ["background" string?]]]
    ["showOrgTab"         {:optional true} boolean?]
    ["socialFeed"         {:optional true} [:map
                                            ["twitterHashtags" string?]
                                            ["twitterProfiles" string?]]]
    ["title" string?]]])

(def private-settings-schema
  [:map-of string?
   [:map
    ["registrationCode" string?]
    ["webHooks" {:optional true} [:map
                                  ["updateMember" string?]
                                  ["newMember" string?]]]]])

(def roles-schema
  [:map
   ["e-u-r" [:map-of string?
             [:map ["admin" {:optional true} boolean?]]]]
   ["u-e-r" [:map-of string?
             [:map ["admin" {:optional true} boolean?]]]]])

(def settings-schema
  ;; NB: maps using keys with internal IDs are denoted with `[:foo {:optional true} string?]`
  [:map
   ["permissions" {:optional true}
    [:map
     ["addproject" {:optional true} [:map ["admin" boolean?]]]
     ["addProject" {:optional true} [:map ["admin" boolean?]]]]]
   ["userFields" {:optional true} [:map [:foo {:optional true} string?]]]
   ["authMethods" {:optional true}
    [:map [:foo {:optional true} string?]]]
   ["descriptionLong" {:optional true} string?]
   ["localeSupport" {:optional true} [:vector string?]]
   ["groupLabel" {:optional true} [:vector string?]]
   ["communityVoteSingle" {:optional true} boolean?]
   ["projectsRequireApproval" {:optional true} boolean?]
   ["createdAt" {:optional true} int?]
   ["userMaxGroups" {:optional true} string?]
   ["projectNumbers" {:optional true} boolean?]
   ["stickyColor" {:optional true} string?]
   ["allowPublicViewing" {:optional true} boolean?]
   ["languageDefault" {:optional true} string?]
   ["parent" {:optional true} string?]
   ["css" {:optional true} string?]
   ["filterByFieldView" {:optional true}
    [:map
     ["label" string?]
     ["name" string?]
     ["type" {:optional true} string?]
     ["options" [:vector [:map ["label" string?] ["value" string?]]]]]]
   ["tags" {:optional true}
    [:map [:foo {:optional true} string?]]]
   ["defaultTag" {:optional true} string?]
   ["userLabel" {:optional true} [:vector string?]]
   ["defaultFilter" {:optional true} string?]
   ["locales" {:optional true}
    [:map [:foo {:optional true} string?]]]
   ["groupFields" {:optional true}
    [:map [:foo {:optional true} string?]]]
   ["registrationMessage" {:optional true} string?]
   ["isTemplate" {:optional true} boolean?]
   ["languages" {:optional true}
    [:vector [:map ["name" string?] ["code" string?]]]]
   ["domain" {:optional true} string?]
   ["metaDesc" {:optional true} string?]
   ["learnMoreLink" {:optional true} string?]
   ["title" {:optional true} string?]
   ["images" {:optional true}
    [:map
     ["logo" {:optional true} string?]
     ["logoLarge" {:optional true} string?]
     ["background" {:optional true} string?]
     ["footer" {:optional true} string?]
     ["subHeader" {:optional true} string?]]]
   ["registrationOpen" {:optional true} boolean?]
   ["userMessages" {:optional true} boolean?]
   ["headerJs" {:optional true} string?]
   ["projectTags" {:optional true} [:vector string?]]
   ["social" {:optional true}
    [:map
     ["twitter" {:optional true} boolean?]
     ["facebook" {:optional true} boolean?]
     ["all" {:optional true} boolean?]
     ["qrCode" {:optional true} boolean?]]]
   ["groupSettings" {:optional true} [:map ["maxMembers" int?]]]
   ["registrationEmailBody" {:optional true} string?]
   ["groupMaxMembers" {:optional true} string?]
   ["newsletterSubscribe" {:optional true} boolean?]
   ["socialFeed" {:optional true}
    [:map
     ["twitterHashtags" string?]
     ["twitterProfiles" {:optional true} string?]]]
   ["groupNumbers" {:optional true} boolean?]
   ["publicVoteMultiple" {:optional true} boolean?]
   ["publicWelcome" {:optional true} string?]
   ["description" {:optional true} string?]])

(def db-schema
  [:map
   ["_parent" parent-schema]
   ["collection" collection-schema]
   ["domain" domain-schema]
   ["feedback" feedback-schema]
   ["invalidations" invalidations-schema]
   ["org" org-schema]
   ["privateSettings" private-settings-schema]
   ["roles" roles-schema]
   ["settings" settings-schema]])


(comment
  (m/validate db-schema db)

  )


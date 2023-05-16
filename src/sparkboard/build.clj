(ns sparkboard.build
  (:require [babashka.process :as bp]
            [clojure.repl.deps :as deps]))

(defn start
  {:shadow/requires-server true}
  []
  ((requiring-resolve 'shadow.cljs.devtools.api/watch) :web)
  ((requiring-resolve 'sparkboard.server.core/-main)))

(defn tailwind-dev!
  {:shadow.build/stage :flush}
  [state]
  (defonce _tsc (bp/process
                 {:in :inherit
                  :out :inherit
                  :err :inherit
                  :shutdown bp/destroy-tree}
                 "npx tailwindcss -w -i src/sparkboard.css -o resources/public/sparkboard.css"))
  state)

(defn tailwind-release!
  {:shadow.build/stage :flush}
  [state]
  (bp/sh "npx tailwindcss -i src/sparkboard.css -o resources/public/sparkboard.css")
  state)


(comment
 (start)
 (clerk/clear-cache!))

(defn aot [_]
  (require 'sparkboard.server.core)
  (compile 'sparkboard.server.core))

(defn uberjar [_]
  (let [deps (clojure.edn/read-string (slurp "deps.edn"))]
    ((requiring-resolve 'uberdeps.api/package) deps "target/sparkboard.jar"
     {:main-class "sparkboard.server"
      :aliases [:datalevin]})))


(comment

 (deps/sync-deps)

 (require '[sparkboard.migration.one-time :as one-time]
          '[datalevin.core :as dl]
          '[re-db.api :as db]
          '[sparkboard.schema :as sb.schema])

 ;; DATA IMPORT - copies to ./.db
 (one-time/fetch-mongodb)
 (one-time/fetch-firebase)
 (one-time/fetch-accounts)

 ;; RESHAPED ENTITIES
 (def entities (one-time/all-entities))
 (comment
  (into #{} (filter (comp #{"id"} name)) (mapcat keys entities)))

 ;; COMMIT TO DB - note, you may wish to
 (dl/clear conn)                                            ;; NOTE - after this, re-run `def conn` in sparkboard.datalevin

 (db/merge-schema! sb.schema/sb-schema)

 ;; "upsert" lookup refs
 (db/transact! (mapcat sb.schema/unique-keys entities))
 (db/transact! entities)

 ;; add entities, print and stop if one fails
 (doseq [e entities]
   (try (db/transact! [e])
        (catch Exception e!
          (prn e)
          (throw e!))))

 (one-time/explain-errors!)

(sparkboard.server.validate/humanized
 [:sequential :field/as-map]
 [#:field{:id #uuid "6616e701-3b08-3a8f-ad82-6d286f52bd77",
          :value
          [:field.type/images
           [#:image{:url
                    "https://lh3.googleusercontent.com/Wm5j5T5-T5hr0R6fpTpPqrZ37Ywaf2w5Y_enN5AyVb41bak2tl98Q0Qky05Tbs6tcolE6xtjpLKVA6sLrQuuQO9ZnJYQ"}]],
          :spec
          [:field-spec/id
           #uuid "ce648472-cbf1-39e4-afb7-c637466259b8"]}
  #:field{:id #uuid "259d6fe2-34a9-3ce3-acbb-040ee1b89f31",
          :value
          [:field.type/images
           [#:image{:url
                    "https://lh3.googleusercontent.com/Lby96SeqJkXBvGaCZMXC0F2ZIJNqQyOIkiWC7IQ6VYwvutHapPmffHjPUSmSZ1NbR1jXa6y0Fn2rwrH7_dam2uBxmNqo"}
            #:image{:url
                    "https://lh3.googleusercontent.com/lc1zl9_-1k1ugHdcvGn1TFeeFTLo--YvqRS3xarBrDreeWjBbPlWTRDiulyzOlQuV37woOy2Bih0fqYcysWRh0nEOk4"}]],
          :spec
          [:field-spec/id
           #uuid "a3f541ec-2fc6-3311-b790-b6dac4af6d03"]}
  #:field{:id #uuid "4ec4c353-2a03-3e6e-a8e0-5b8bc6ad201d",
          :value
          [:field.type/text-content
           #:text-content{:format :text.format/html,
                          :string
                          "🐌\nThe idea behind our project, is to serve matching news-articles to audio podcasts from the SRG API. The otherway from text-articles to podcast should work too. \nWe think it is a good idea, to enjoy the latest news on the road, based on an article, which was interesting. The otherway can be interesting for people who often listen to radio. It is possible to redirect the users to the webcontent of your werbserver.\n🐌\nAny news, anywhere in every type of medium.\n🐌"}],
          :spec
          [:field-spec/id
           #uuid "5078b7d8-ffac-3fd3-bf1b-68bee1244ad2"]}
  #:field{:id #uuid "13b53c29-84ef-3017-b430-1b031c81f764",
          :value
          [:field.type/text-content
           #:text-content{:format :text.format/html, :string "🐌"}],
          :spec
          [:field-spec/id
           #uuid "dc7750d3-766c-37c0-9921-540ee42d95db"]}
  #:field{:id #uuid "132b96e7-072f-3418-949b-7b116f0dab4b",
          :value [:field.type/select #:select{:value "zurich"}],
          :spec
          [:field-spec/id
           #uuid "5637b0e3-e9da-3bc9-855f-43b96e61f55b"]}
  #:field{:id #uuid "eeaa4780-e7fd-38e0-82e1-70de14143473",
          :value
          [:field.type/link-list
           #:link-list{:items
                       [{:text "SRG Audiometadata API",
                         :url
                         "https://api.srgssr.ch/audiometadata/v2/"}
                        {:text "https://newsapi.org/", :url "NewsApi"}
                        {:text "https://www.javascript.com/",
                         :url "Javascript"}
                        {:text "https://nodejs.org/en/",
                         :url "Node.js"}
                        {:url
                         "https://speech-to-text-demo.ng.bluemix.net/"}]}],
          :spec
          [:field-spec/id
           #uuid "2986701e-2e65-3463-9ad2-2bc11bd63066"]}
  #:field{:id #uuid "0cfb4814-3cde-3cd0-ad44-8bd4bbb041a0",
          :value
          [:field.type/text-content
           #:text-content{:format :text.format/html,
                          :string
                          "🐌\nWe analyze the podcast and create some matching tags. With this tags we will search in a news-api for the latest news.\nThe application is built with javascript on Node.js\n🐌\nAPI:\nhttps://newsapi.org/\nhttps://api.srgssr.ch/audiometadata/v2/\n🐌\nDev:\nConrwnr Filtee\nSpeech to Text\nWebStorm\nLove and Coffee \nNode.js\n🐌"}],
          :spec
          [:field-spec/id
           #uuid "eec28303-ad5b-361e-90a9-aff5551d770f"]}]
 )

 )

;; TODO
;; playground for writing datalevin queries using plain english
;; - try ingesting the schema via ChatGPT 3.5, ada, babbage and use that to
;;   write queries (hopefully gpt4 isn't necessary)
;; https://github.com/wkok/openai-clojure
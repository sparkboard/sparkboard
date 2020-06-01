#!bin/bb

(ns cursive.repl.runtime)
(defn completions [& args])

(ns certs
  (:require [clojure.java.shell :refer [sh]]
            [clojure.string :as str]
            [cheshire.core :as json])
  (:import (java.time Instant LocalDateTime ZoneOffset)
           (java.time.format DateTimeFormatter)
           (java.time.temporal ChronoUnit)))

(defn env [x & [not-found]]
  (or (System/getenv (name x)) not-found))

(defn shell [& cmds]
  (let [{:keys [exit out err]} (apply sh cmds)]
    (if (zero? exit)
      (str/trim-newline out)
      (throw (ex-info err {:cmd cmds})))))

(defn cert-expire-time [domain]
  (when-let [date-string (try (->> (shell "openssl" "s_client" "-connect" (str domain ":443") "-servername" domain)
                                   (shell "openssl" "x509" "-noout" "-dates" :in)
                                   (str/split-lines)
                                   (keep (comp second #(re-find #"^notAfter=(.*)" %)))
                                   first)
                              (catch Exception e
                                (print e)
                                (println (str "Could not read ssl expiration time" {:domain domain}))))]
    (try (-> date-string
             (str/replace #"\s+" " ")
             (LocalDateTime/parse (DateTimeFormatter/ofPattern "MMM d k:mm:ss yyyy z"))
             (.toInstant ZoneOffset/UTC))
         (catch Exception e
           (println "Could not parse date-string " {:domain domain :date date-string})))))

(defn powershell [& cmds]
  (println :powershell (apply str cmds))
  (shell "pwsh" "-c" (apply str cmds)))

(defn guard [x f]
  (when (f x) x))

(defn new-cert! [{:as opts :keys [names prod?]}]
  (println (str "Creating certificate for: " names))
  (some-> (powershell
            (str
              "$cfArgs=@{CFAuthEmail='" (env :CF_EMAIL) "'; CFAuthKey='" (env :CF_KEY) "'};"
              "Set-PSRepository -Name 'PSGallery' -InstallationPolicy Trusted;"
              "Install-Module -Name Posh-ACME -Scope CurrentUser;"
              "Set-PAServer " (if prod? "LE_PROD" "LE_STAGE") ";"
              "$result = New-PACertificate "
              (->> names (map #(str "'" % "'")) (str/join ","))
              (str " -DnsPlugin Cloudflare -PluginArgs $cfArgs")
              " -DNSSleep 10"
              " -AcceptTOS"
              " -Contact " (env :LE_EMAIL "domains@sparkboard.com")
              " -Verbose;"
              "echo '---RESULT---';"
              "ConvertTo-Json $result"))
          (doto println)
          (str/split #"---RESULT---")
          (second)
          (json/parse-string keyword)))

(defn expires-in
  "Number of days until domain expires"
  [domain]
  (some->> (cert-expire-time domain)
           (.between ChronoUnit/DAYS (Instant/now))))

(defn renew! [{:as opts :keys [names
                               renewal-period
                               create?]
               :or {renewal-period 30}}]
  (let [days-left (expires-in (str/replace (first names) "*." ""))]
    (if (or create? (and days-left (< days-left renewal-period)))
      (assoc opts :cert-files (new-cert! opts))
      (do (println (str "Not renewing " (first names) ", has " days-left " days left."))
          opts))))

(defn install-to-heroku! [{:as opts :keys [cert-files
                                           app-name
                                           prod?]}]
  (if cert-files
    (if prod?
      (do
        (powershell "if (Test-Path server.crt) {Remove-Item server.crt};")
        (powershell "if (Test-Path server.key) {Remove-Item server.key}")
        (powershell "Copy-Item " (:FullChainFile cert-files) " server.crt")
        (powershell "Copy-Item " (:KeyFile cert-files) " server.key")
        (shell "yarn" "heroku" "certs:update" "server.crt" "server.key" "-a" app-name "--confirm" app-name))
      (println "staging: not installing to heroku"))
    (do
      (println "heroku: no cert files found")
      opts)))

(defn last-result []
  (-> (powershell "Get-PACertificate | ConvertTo-Json")
      (json/parse-string keyword)))

(def jobs
  [#_{:names ["staging.sparkboard.dev"
              "*.staging.sparkboard.dev"]
      :app-name "spark-stage"}
   #_{:names ["sparkboard.com"
              "*.sparkboard.com"]
      :app-name "spark01"}])

(comment

  ;; see how long any domain's cert has until expiry
  (expires-in "microsoft.com")

  ;; hash-map in powershell
  (-> (powershell "$a=@{a='b';c='d'}; ConvertTo-Json $a")
      (json/parse-string keyword))

  ;; after certificate has been created, we can read the result-map
  (-> (powershell "Get-PACertificate | ConvertTo-Json")
      (json/parse-string keyword))

  (-> {:names ["test-1.matt.is"]
       :app-name "mattis"
       :prod? true
       :create? true
       :renewal-period 500}
      (renew!)
      (install-to-heroku!))

  (expires-in "test-1.matt.is")

  )

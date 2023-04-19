(ns sparkboard.i18n
  (:require [sparkboard.client.common :as common]
            [re-db.memo :as memo]
            [re-db.reactive :as r]
            [taoensso.tempura :as tempura]
            [yawn.hooks :refer [use-deref]]))

(defonce !preferred-language
  (common/$local-storage ::preferred-language :eng))

(defonce !locales
  (r/reaction (into [] (distinct) [@!preferred-language :eng])))

(def dict
  "Tempura-style dictionary of internationalizations, keyed by ISO-639-3.
  See https://iso639-3.sil.org/code_tables/639/data/all for list of codes"
  {:eng {:missing ":eng missing text"
         ;; A `lect` is what a language or dialect variety is called; see
         ;; https://en.m.wikipedia.org/wiki/Variety_(linguistics)
         :meta/lect "English"
         ;; Translations
         :skeleton/nix "Nothing to see here, folks." ;; keyed separately from `tr` to mark it as dev-only
         :tr/lang "Language"

         :tr/search "Search",
         :tr/search-across-org "org-wide search"
         :tr/new "New"
         :tr/create "Create"
         :tr/login "Log in",
         :tr/logout "Log out"
         :tr/user "User"
         :tr/org "Organisation"
         :tr/orgs "Organisations"
         :tr/board "Board"
         :tr/boards "Boards"
         :tr/project "Project"
         :tr/projects "Projects"
         :tr/member "Member"
         :tr/members "Members"
         :tr/tag "Tag"
         :tr/tags "Tags"
         :tr/badge "Badge"
         :tr/badges "Badges"
         :tr/password "Password"
         :tr/email "Email"}

   :fra {:missing ":fra texte manquant"
         :meta/lect "Français"
         :skeleton/nix "Rien à voir ici, les amis."
         :tr/lang "Langue"
         :tr/search "Rechercher"
         :tr/search-across-org "rechercher dans toute l'organisation"
         :tr/new "Nouveau"
         :tr/login "Connexion"
         :tr/logout "Se déconnecter"
         ;tr/; entities
         :tr/user "Utilisateur" ;; FIXME feminine
         :tr/org "Organisation"
         :tr/orgs "Organisations"
         :tr/boards "Tableaux"
         :tr/project "Projet"
         :tr/projects "Projets"
         :tr/member "Membre"
         :tr/members "Membres"
         :tr/tag "Mot-clé"
         :tr/tags "Mots clés"
         :tr/badge "Insigne"
         :tr/badges "Insignes"
         :tr/password "Mot de passe"
         :tr/email "Email"}})


(defn tr
  ([resource-ids] (tempura/tr {:dict dict} @!locales resource-ids))
  ([resource-ids resource-args] (tempura/tr {:dict dict} @!locales resource-ids resource-args)))

(ns sparkboard.i18n
  (:require [sparkboard.client.common :as common]
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
         :tr {:lang "Language"
              ;; verbs & instructions
              :search "Search", :search-across-org "org-wide search"
              :new "New"
              :create "Create"
              :login "Log in", :logout "Log out"
              ;; entities
              :user "User"
              :org "Organisation", :orgs "Organisations"
              :board "Board" :boards "Boards"
              :project "Project", :projects "Projects"
              :member "Member", :members "Members"
              :tag "Tag", :tags "Tags"
              :badge "Badge", :badges "Badges"
              :member-name "Member Name", :password "Password"}}

   :fra {:missing ":fra texte manquant"
         :meta/lect "Français"
         :skeleton/nix "Rien à voir ici, les amis."
         :tr {:lang "Langue"
              ;; verbs & instructions
              :search "Rechercher", :search-across-org "rechercher dans toute l'organisation"
              :new "Nouveau"
              :login "Connexion", :logout "Se déconnecter"
              ;; entities
              :user "Utilisateur" ;; FIXME feminine
              :org "Organisation", :orgs "Organisations"
              :boards "Tableaux"
              :project "Projet", :projects "Projets"
              :member "Membre", :members "Membres"
              :tag "Mot-clé", :tags "Mots clés"
              :badge "Insigne", :badges "Insignes"
              :member-name "Nom de membre", :password "Mot de passe"}}})

(def dev? false)

(defn use-tr
  ;; hook: reactive, must follow rules of hooks
  ([resource-ids] (str (when dev? resource-ids) (tempura/tr {:dict dict} (use-deref !locales) resource-ids)))
  ([resource-ids resource-args] (str (when dev? resource-ids) (tempura/tr {:dict dict} (use-deref !locales) resource-ids resource-args))))

(defn tr
  ;; not reactive within yawn, doesn't need to follow rules of hooks
  ;; (raises the NB question: how (far) to integrate re-db.reactive with yawn)
  ([resource-ids] (str (when dev? resource-ids) (tempura/tr {:dict dict} @!locales resource-ids)))
  ([resource-ids resource-args] (str (when dev? resource-ids) (tempura/tr {:dict dict} @!locales resource-ids resource-args))))

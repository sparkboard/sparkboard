(ns sparkboard.i18n
  (:require [sparkboard.client.local-storage :as common]
            [re-db.reactive :as r]
            [taoensso.tempura :as tempura])
  #?(:cljs (:require-macros [sparkboard.i18n :refer [ungroup-dict]])))

(defonce !preferred-language
  (common/$local-storage ::preferred-language :en))

(defonce !locales
  (r/reaction (into [] (distinct) [@!preferred-language :en])))

(defmacro ungroup-dict [dict]
  (->> dict
       (map (fn [[key-id lang-map]]
              (->> lang-map
                   (map (fn [[lang-id value]]
                          [lang-id {key-id value}]))
                   (into {}))))
       (apply merge-with merge)))

(def dict
  "Tempura-style dictionary of internationalizations, keyed by ISO-639-2.
See https://iso639-3.sil.org/code_tables/639/data/all for list of codes"
  (ungroup-dict ;; grouped for easy generation of multiple languages
   {:tr/boards {:en "Boards", :fr "Tableaux", :es "Tableros"},
    :tr/projects {:en "Projects", :fr "Projets", :es "Proyectos"},
    :tr/tags {:en "Tags", :fr "Mots clés", :es "Etiquetas"},
    :tr/sign-in {:en "Sign in", :fr "Connexion", :es "Iniciar sesión"},
    :tr/email {:en "Email", :fr "Courriel", :es "Correo electrónico"},
    :tr/password {:en "Password", :fr "Mot de passe", :es "Contraseña"},
    :tr/new {:en "New", :fr "Nouveau", :es "Nuevo"},
    :skeleton/nix {:en "Nothing to see here, folks.", :fr "Rien à voir ici, les amis.", :es "Nada que ver aquí, amigos."},
    :tr/create {:en "Create", :es "Crear"},
    :tr/invalid-email {:en "Invalid email", :fr "Courriel invalide", :es "Correo electrónico inválido"},
    :tr/lang {:en "Language", :fr "Langue", :es "Idioma"},
    :tr/member {:en "Member", :fr "Membre", :es "Miembro"},
    :tr/logout {:en "Log out", :fr "Se déconnecter", :es "Cerrar sesión"},
    :tr/search {:en "Search", :fr "Rechercher", :es "Buscar"},
    :tr/project {:en "Project", :fr "Projet", :es "Proyecto"},
    :tr/orgs {:en "Organisations", :fr "Organisations", :es "Organizaciones"},
    :tr/org {:en "Organisation", :fr "Organisation", :es "Organización"},
    :tr/badge {:en "Badge", :fr "Insigne", :es "Insignia"},
    :tr/badges {:en "Badges", :fr "Insignes", :es "Insignias"},
    :tr/search-across-org {:en "org-wide search", :fr "rechercher dans toute l'organisation", :es "buscar en toda la organización"},
    :missing {:en ":eng missing text", :fr ":fra texte manquant", :es ":spa texto faltante"},
    :tr/user {:en "User", :fr "Utilisateur", :es "Usuario"},
    :tr/members {:en "Members", :fr "Membres", :es "Miembros"},
    :tr/board {:en "Board", :es "Tablero"},
    :tr/tag {:en "Tag", :fr "Mot-clé", :es "Etiqueta"}
    :tr/or {:en "or", :fr "ou", :es "o"}
    :tr/tos {:en "Terms of Service", :fr "Conditions d'utilisation", :es "Términos de servicio"}
    :tr/privacy-policy {:en "Privacy Policy" :fr "Politique de confidentialité", :es "Política de privacidad"}
    :tr/cookie-policy {:en "Cookie Policy", :fr "Politique relative aux cookies", :es "Política de cookies"}
    :tr/and {:en "and", :fr "et", :es "y"}
    :tr/sign-in-with-google {:en "Sign in with Google", :fr "Se connecter avec Google", :es "Iniciar sesión con Google"}
    :tr/sign-up-agree-to {:en "By clicking continue, you agree to our",
                          :fr "En cliquant sur continuer, vous acceptez notre",
                          :es "Al hacer clic en continuar, acepta nuestro"}
    :tr/welcome {:en "Welcome to Sparkboard" :fr "Bienvenue sur Sparkboard" :es "Bienvenido a Sparkboard"}
    ;; A `lect` is what a language or dialect variety is called; see
    ;; https://en.m.wikipedia.org/wiki/Variety_(linguistics)
    :meta/lect {:en "English", :fr "Français", :es "Español"}}))


(defn tr
  ([resource-ids] (tempura/tr {:dict dict} @!locales (cond-> resource-ids
                                                             (keyword? resource-ids)
                                                             vector)))
  ([resource-ids resource-args] (tempura/tr {:dict dict} @!locales resource-ids resource-args)))

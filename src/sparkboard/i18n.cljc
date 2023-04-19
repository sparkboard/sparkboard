(ns sparkboard.i18n
  (:require [sparkboard.client.local-storage :as common]
            [re-db.reactive :as r]
            [taoensso.tempura :as tempura])
  #?(:cljs (:require-macros [sparkboard.i18n :refer [ungroup-dict]])))

(defonce !preferred-language
  (common/$local-storage ::preferred-language :eng))

(defonce !locales
  (r/reaction (into [] (distinct) [@!preferred-language :eng])))

(defmacro ungroup-dict [dict]
  (->> dict
       (map (fn [[key-id lang-map]]
              (->> lang-map
                   (map (fn [[lang-id value]]
                          [lang-id {key-id value}]))
                   (into {}))))
       (apply merge-with merge)))

(def dict
  "Tempura-style dictionary of internationalizations, keyed by ISO-639-3.
See https://iso639-3.sil.org/code_tables/639/data/all for list of codes"
  (ungroup-dict ;; grouped for easy generation of multiple languages
   {:tr/boards {:eng "Boards", :fra "Tableaux", :spa "Tableros"},
    :tr/projects {:eng "Projects", :fra "Projets", :spa "Proyectos"},
    :tr/tags {:eng "Tags", :fra "Mots clés", :spa "Etiquetas"},
    :tr/sign-in {:eng "Sign in", :fra "Connexion", :spa "Iniciar sesión"},
    :tr/email {:eng "Email", :fra "Courriel", :spa "Correo electrónico"},
    :tr/password {:eng "Password", :fra "Mot de passe", :spa "Contraseña"},
    :tr/new {:eng "New", :fra "Nouveau", :spa "Nuevo"},
    :skeleton/nix {:eng "Nothing to see here, folks.", :fra "Rien à voir ici, les amis.", :spa "Nada que ver aquí, amigos."},
    :tr/create {:eng "Create", :spa "Crear"},
    :tr/invalid-email {:eng "Invalid email", :fra "Courriel invalide", :spa "Correo electrónico inválido"},
    :tr/lang {:eng "Language", :fra "Langue", :spa "Idioma"},
    :tr/member {:eng "Member", :fra "Membre", :spa "Miembro"},
    :tr/logout {:eng "Log out", :fra "Se déconnecter", :spa "Cerrar sesión"},
    :tr/email-example {:eng "name@example.com", :fra "nom@exemple.com", :spa "nombre@ejemplo.com"},
    :tr/search {:eng "Search", :fra "Rechercher", :spa "Buscar"},
    :tr/project {:eng "Project", :fra "Projet", :spa "Proyecto"},
    :tr/orgs {:eng "Organisations", :fra "Organisations", :spa "Organizaciones"},
    :tr/org {:eng "Organisation", :fra "Organisation", :spa "Organización"},
    :tr/badge {:eng "Badge", :fra "Insigne", :spa "Insignia"},
    :tr/badges {:eng "Badges", :fra "Insignes", :spa "Insignias"},
    :tr/search-across-org {:eng "org-wide search", :fra "rechercher dans toute l'organisation", :spa "buscar en toda la organización"},
    :missing {:eng ":eng missing text", :fra ":fra texte manquant", :spa ":spa texto faltante"},
    :tr/user {:eng "User", :fra "Utilisateur", :spa "Usuario"},
    :tr/members {:eng "Members", :fra "Membres", :spa "Miembros"},
    :tr/board {:eng "Board", :spa "Tablero"},
    :tr/tag {:eng "Tag", :fra "Mot-clé", :spa "Etiqueta"}
    :tr/or {:eng "or", :fra "ou", :spa "o"}
    :tr/sign-in-with-email {:eng "Sign in with email",
                            :fra "Se connecter avec un courriel",
                            :spa "Iniciar sesión con correo electrónico"}
    :tr/tos {:eng "Terms of Service", :fra "Conditions d'utilisation", :spa "Términos de servicio"}
    :tr/pp {:eng "Privacy Policy" :fra "Politique de confidentialité", :spa "Política de privacidad"}
    :tr/and {:eng "and", :fra "et", :spa "y"}
    :tr/by-signing-up-you-agree-to {:eng "By clicking continue, you agree to our",
                                    :fra "En cliquant sur continuer, vous acceptez notre",
                                    :spa "Al hacer clic en continuar, acepta nuestro"}
    :tr/welcome {:eng "Welcome to Sparkboard" :fra "Bienvenue sur Sparkboard" :spa "Bienvenido a Sparkboard"}
    ;; A `lect` is what a language or dialect variety is called; see
    ;; https://en.m.wikipedia.org/wiki/Variety_(linguistics)
    :meta/lect {:eng "English", :fra "Français", :spa "Español"}}))


(defn tr
  ([resource-ids] (tempura/tr {:dict dict} @!locales (cond-> resource-ids
                                                             (keyword? resource-ids)
                                                             vector)))
  ([resource-ids resource-args] (tempura/tr {:dict dict} @!locales resource-ids resource-args)))

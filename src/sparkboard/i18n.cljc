(ns sparkboard.i18n
  (:require #?(:clj [sparkboard.server.validate :as vd])
            [re-db.api :as db]
            [taoensso.tempura :as tempura])
  #?(:cljs (:require-macros [sparkboard.i18n :refer [ungroup-dict]])))

#?(:cljs
   (def !selected-locale (delay
                          (when-not (db/get :env/config :env)
                            (throw (js/Error. "Reading i18n before environment is set"))
                            #_(js-debugger))
                          (db/get :env/account :account/locale "en"))))

#?(:cljs
   (def !locales (delay
                  (->> [@!selected-locale "en"]
                       (into []
                             (comp (keep identity)
                                   (distinct)))))))

(defmacro ungroup-dict [dict]
  (->> dict
       (map (fn [[key-id lang-map]]
              (->> lang-map
                   (map (fn [[lang-id value]]
                          [(name lang-id) {key-id value}]))
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
    :tr/create {:en "Create", :fr "Créer", :es "Crear"},
    :tr/invalid-email {:en "Invalid email", :fr "Courriel invalide", :es "Correo electrónico inválido"},
    :tr/lang {:en "Language", :fr "Langue", :es "Idioma"},
    :tr/member {:en "Member", :fr "Membre", :es "Miembro"},
    :tr/logout {:en "Log out", :fr "Se déconnecter", :es "Cerrar sesión"},
    :tr/search {:en "Search", :fr "Rechercher", :es "Buscar"},
    :tr/project {:en "Project", :fr "Projet", :es "Proyecto"},
    :tr/orgs {:en "Organisations", :fr "Organisations", :es "Organizaciones"},
    :tr/org {:en "Organisation", :fr "Organisation", :es "Organización"},
    :tr/new-org {:en "New organisation", :fr "Nouvelle organisation", :es "Nueva organización"},
    :tr/badge {:en "Badge", :fr "Insigne", :es "Insignia"},
    :tr/badges {:en "Badges", :fr "Insignes", :es "Insignias"},
    :tr/search-across-org {:en "org-wide search", :fr "rechercher dans toute l'organisation", :es "buscar en toda la organización"},
    ;; :missing {:en ":eng missing text", :fr ":fra texte manquant", :es ":spa texto faltante"},
    :tr/user {:en "User", :fr "Utilisateur", :es "Usuario"},
    :tr/members {:en "Members", :fr "Membres", :es "Miembros"},
    :tr/board {:en "Board", :fr "Tableau", :es "Tablero"}
    :tr/new-board {:en "New board", :fr "Nouveau tableau", :es "Nuevo tablero"}
    :tr/tag {:en "Tag", :fr "Mot-clé", :es "Etiqueta"}
    :tr/or {:en "or", :fr "ou", :es "o"}
    :tr/tos {:en "Terms of Service", :fr "Conditions d'utilisation", :es "Términos de servicio"}
    :tr/privacy-policy {:en "Privacy Policy" :fr "Politique de confidentialité", :es "Política de privacidad"}
    :tr/cookie-policy {:en "Cookie Policy", :fr "Politique relative aux cookies", :es "Política de cookies"}
    :tr/and {:en "and", :fr "et", :es "y"}
    :tr/new-project {:en "New project", :fr "Nouveau projet", :es "Nuevo proyecto"}
    :tr/create-board {:en "Create board", :fr "Créer un tableau", :es "Crear tablero"}
    :tr/board-title {:en "Board title", :fr "Titre du tableau", :es "Título del tablero"}
    :tr/sign-in-with-google {:en "Sign in with Google", :fr "Se connecter avec Google", :es "Iniciar sesión con Google"}
    :tr/sign-in-agree-to {:en "By signing in, you agree to our",
                          :fr "En cliquant sur continuer, vous acceptez notre",
                          :es "Al hacer clic en continuar, acepta nuestro"}
    :tr/welcome {:en "Welcome to Sparkboard" :fr "Bienvenue sur Sparkboard" :es "Bienvenido a Sparkboard"}
    :tr/title {:en "Title" :fr "Titre" :es "Título"}
    :tr/invalid-domain {:en "Must contain only numbers, letters, and hyphens"
                        :fr "Ne peut contenir que des chiffres, des lettres et des tirets"
                        :es "Debe contener solo números, letras y guiones"}
    :tr/subdomain {:en "Subdomain" :fr "Sous-domaine" :es "Subdominio"}
    :tr/description {:en "Description" :fr "Description" :es "Descripción"}
    ;; A `lect` is what a language or dialect variety is called; see
    ;; https://en.m.wikipedia.org/wiki/Variety_(linguistics)
    :meta/lect {:en "English", :fr "Français", :es "Español"}
    :tr/not-available {:en "Not available" :fr "Non disponible" :es "No disponible"}
    :tr/available {:en "Available" :fr "Disponible" :es "Disponible"}
    }))

#?(:cljs
   (defn tr
     ([resource-ids] (or (tempura/tr {:dict dict} @!locales (cond-> resource-ids
                                                                    (keyword? resource-ids)
                                                                    vector))
                         (doto (str "Missing" resource-ids) js/console.warn)))
     ([resource-ids resource-args] (or (tempura/tr {:dict dict} @!locales resource-ids resource-args)
                                       (doto (str "Missing" resource-ids) js/console.warn)))))

#?(:clj
   (def supported-locales (into #{} (map name) (keys dict))))

#?(:clj
   (defn accept-language->639-2 [accept-language]
     (->> accept-language
          (re-find #".*[^;]?([a-z]{2})[;$]?.*")
          (second))))

#?(:clj
   (defn get-locale [req]
     (or (some-> (:account req) :account/locale supported-locales) ;; a known user explicitly set their language
         (some-> (:cookies req) (get "locale") supported-locales) ;; anonymous user explicitly set their language
         (some-> (:board req) :board/locale-default supported-locales) ;; board has a preferred language
         (some-> (:org req) :org/locale-default supported-locales) ;; org has preferred language
         (some-> (get-in req [:headers "accept-language"]) accept-language->639-2) ;; use the browser's language
         "en"))) ;; fallback to english

#?(:clj
   (defn set-locale
     {:POST :i18n/locale}
     [req _params locale]
     (vd/assert locale :i18n/locale)
     (if (:account req)
       (do (re-db.api/transact! [{:db/id (:db/id (:account req))
                                  :account/locale locale}])
           {:status 200
            :body {:i18n/locale locale}})
       {:status 200
        :body {:i18n/locale locale}
        :cookies {"locale" {:value locale
                            :max-age 31536000
                            :path "/"}}})))
;; TODO
;; - send only the current language to the browser. when changing locale,
;;   first set the locale to the account or cookie, then reload the page.
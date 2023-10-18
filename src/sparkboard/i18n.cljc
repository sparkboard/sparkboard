(ns sparkboard.i18n
  (:require #?(:clj [sparkboard.validate :as vd])
            [re-db.api :as db]
            [taoensso.tempura :as tempura])
  #?(:cljs (:require-macros [sparkboard.i18n :refer [ungroup-dict]])))

#?(:clj (def ^:dynamic *selected-locale* "en"))
(defn current-locale []
  #?(:cljs (-> (db/entity :env/config)
               :account
               (:account/locale "en"))
     :clj  *selected-locale*))


(defn locales []
  #?(:cljs (->> [(current-locale) "en"]
                (into []
                      (comp (keep identity)
                            (distinct))))
     :clj  ["en"]))

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
  (ungroup-dict                                             ;; grouped for easy generation of multiple languages
    {:tr/domain-already-registered       {:en "Domain already registered"
                                          :fr "Domaine déjà enregistré"
                                          :es "Dominio ya registrado"},
     :tr/you                             {:en "You:"
                                          :fr "Vous :"
                                          :es "Tú:"},
     :tr/recent                          {:en "Recent"
                                          :fr "Récents"
                                          :es "Recientes"},
     :tr/show-more                       {:en "Show more"
                                          :fr "Afficher plus"
                                          :es "Mostrar más"},
     :tr/show-less                       {:en "Show less"
                                          :fr "Afficher moins"
                                          :es "Mostrar menos"},
     :tr/images                          {:en "Images"
                                          :fr "Images"
                                          :es "Imágenes"},
     :tr/back                            {:en "Back"
                                          :fr "Retour"
                                          :es "Atrás"},
     :tr/done                            {:en "Done"
                                          :fr "Terminé"
                                          :es "Hecho"},
     :tr/boards                          {:en "Boards"
                                          :fr "Tableaux"
                                          :es "Tableros"},
     :tr/projects                        {:en "Projects"
                                          :fr "Projets"
                                          :es "Proyectos"},
     :tr/new-member                      {:en "New member"
                                          :fr "Nouveau membre"
                                          :es "Nuevo miembro"},
     :tr/tags                            {:en "Tags"
                                          :fr "Mots clés"
                                          :es "Etiquetas"},
     :tr/sign-in                         {:en "Sign in"
                                          :fr "Connexion"
                                          :es "Iniciar sesión"},
     :tr/email                           {:en "Email"
                                          :fr "Courriel"
                                          :es "Correo electrónico"},
     :tr/password                        {:en "Password"
                                          :fr "Mot de passe"
                                          :es "Contraseña"},
     :tr/new                             {:en "New"
                                          :fr "Nouveau"
                                          :es "Nuevo"},
     :skeleton/nix                       {:en "Nothing to see here, folks."
                                          :fr "Rien à voir ici, les amis."
                                          :es "Nada que ver aquí, amigos."},
     :tr/create                          {:en "Create"
                                          :fr "Créer"
                                          :es "Crear"},
     :tr/invalid-email                   {:en "Invalid email"
                                          :fr "Courriel invalide"
                                          :es "Correo electrónico inválido"},
     :tr/image.logo                      {:en "Logo"
                                          :fr "Logo"
                                          :es "Logo"},
     :tr/image.background                {:en "Background"
                                          :fr "Arrière-plan"
                                          :es "Fondo"},
     :tr/lang                            {:en "Language"
                                          :fr "Langue"
                                          :es "Idioma"},
     :tr/member                          {:en "Member"
                                          :fr "Membre"
                                          :es "Miembro"},
     :tr/save                            {:en "Save"
                                          :fr "Enregistrer"
                                          :es "Guardar"},
     :tr/logout                          {:en "Log out"
                                          :fr "Se déconnecter"
                                          :es "Cerrar sesión"},
     :tr/search                          {:en "Search"
                                          :fr "Rechercher"
                                          :es "Buscar"},
     :tr/project                         {:en "Project"
                                          :fr "Projet"
                                          :es "Proyecto"},
     :tr/orgs                            {:en "Organisations"
                                          :fr "Organisations"
                                          :es "Organizaciones"},
     :tr/org                             {:en "Organisation"
                                          :fr "Organisation"
                                          :es "Organización"},
     :tr/new-org                         {:en "New Organisation"
                                          :fr "Nouvelle Organisation"
                                          :es "Nueva Organización"},
     :tr/badge                           {:en "Badge"
                                          :fr "Insigne"
                                          :es "Insignia"},
     :tr/badges                          {:en "Badges"
                                          :fr "Insignes"
                                          :es "Insignias"},
     :tr/search-across-org               {:en "org-wide search"
                                          :fr "rechercher dans toute l'organisation"
                                          :es "buscar en toda la organización"},
     ;; :missing {:en ":eng missing text", :fr ":fra texte manquant", :es ":spa texto faltante"},
     :tr/user                            {:en "User"
                                          :fr "Utilisateur"
                                          :es "Usuario"},
     :tr/members                         {:en "Members"
                                          :fr "Membres"
                                          :es "Miembros"},
     :tr/board                           {:en "Board"
                                          :fr "Tableau"
                                          :es "Tablero"}
     :tr/new-board                       {:en "New board"
                                          :fr "Nouveau tableau"
                                          :es "Nuevo tablero"}
     :tr/support-project                 {:en "Support this project"
                                          :fr "Soutenez ce projet"
                                          :es "Apoyar este proyecto"}
     :tr/tag                             {:en "Tag"
                                          :fr "Mot-clé"
                                          :es "Etiqueta"}
     :tr/or                              {:en "or"
                                          :fr "ou"
                                          :es "o"}
     :tr/tos                             {:en "Terms of Service"
                                          :fr "Conditions d'utilisation"
                                          :es "Términos de servicio"}
     :tr/privacy-policy                  {:en "Privacy Policy"
                                          :fr "Politique de confidentialité"
                                          :es "Política de privacidad"}
     :tr/cookie-policy                   {:en "Cookie Policy"
                                          :fr "Politique relative aux cookies"
                                          :es "Política de cookies"}
     :tr/and                             {:en "and"
                                          :fr "et"
                                          :es "y"}
     :tr/new-project                     {:en "New project"
                                          :fr "Nouveau projet"
                                          :es "Nuevo proyecto"}
     :tr/create-board                    {:en "Create board"
                                          :fr "Créer un tableau"
                                          :es "Crear tablero"}
     :tr/board-title                     {:en "Board title"
                                          :fr "Titre du tableau"
                                          :es "Título del tablero"}
     :tr/sign-in-with-google             {:en "Sign in with Google"
                                          :fr "Se connecter avec Google"
                                          :es "Iniciar sesión con Google"}
     :tr/sign-in-agree-to                {:en "By signing in, you agree to our",
                                          :fr "En cliquant sur continuer, vous acceptez notre",
                                          :es "Al hacer clic en continuar, acepta nuestro"}
     :tr/welcome                         {:en "Welcome to Sparkboard"
                                          :fr "Bienvenue sur Sparkboard"
                                          :es "Bienvenido a Sparkboard"}
     :tr/title                           {:en "Title"
                                          :fr "Titre"
                                          :es "Título"}
     :tr/invalid-domain                  {:en "Must contain only numbers, letters, and hyphens"
                                          :fr "Ne peut contenir que des chiffres, des lettres et des tirets"
                                          :es "Debe contener solo números, letras y guiones"}
     :tr/domain-name                     {:en "Domain name"
                                          :fr "Nom de domaine"
                                          :es "Nombre de dominio"}
     :tr/description                     {:en "Description"
                                          :fr "Description"
                                          :es "Descripción"}
     ;; A `lect` is what a language or dialect variety is called; see
     ;; https://en.m.wikipedia.org/wiki/Variety_(linguistics)
     :meta/lect                          {:en "English"
                                          :fr "Français"
                                          :es "Español"}
     :tr/not-available                   {:en "Not available"
                                          :fr "Non disponible"
                                          :es "No disponible"}
     :tr/available                       {:en "Available"
                                          :fr "Disponible"
                                          :es "Disponible"}
     :tr/account-not-found               {:en "Account not found"
                                          :fr "Compte introuvable"
                                          :es "Cuenta no encontrada"}
     :tr/account-requires-password-reset {:en "Password reset required"
                                          :fr "Réinitialisation du mot de passe requise"
                                          :es "Restablecimiento de contraseña requerido"}
     :tr/settings                        {:en "Settings"
                                          :fr "Paramètres"
                                          :es "Configuración"}
     :tr/too-short                       {:en "Too short"
                                          :fr "Trop court"
                                          :es "Demasiado corto"}
     :tr/all                             {:en "All"
                                          :fr "Tous"
                                          :es "Todos"}
     :tr/home                            {:en "Home"
                                          :fr "Accueil"
                                          :es "Inicio"}
     :tr/personal-account                {:en "Personal (%1)"
                                          :fr "Personnel (%1)"
                                          :es "Personal (%1)"}
     :tr/owner                           {:en "Owner"
                                          :fr "Propriétaire"
                                          :es "Propietario"}
     :tr/find-a-member                   {:en "Find a member"
                                          :fr "Trouver un membre"
                                          :es "Encontrar un miembro"}}))

(defn tr
  ([resource-ids] (or (tempura/tr {:dict dict} (locales) (cond-> resource-ids
                                                                 (keyword? resource-ids)
                                                                 vector))
                      #?(:cljs (doto (str "Missing" resource-ids) js/console.warn))))
  ([resource-ids resource-args] (or (tempura/tr {:dict dict} (locales)
                                                (cond-> resource-ids
                                                        (keyword? resource-ids)
                                                        vector)
                                                resource-args)
                                    #?(:cljs (doto (str "Missing" resource-ids) js/console.warn)))))

(def supported-locales (into #{} (map name) (keys dict)))

(defn accept-language->639-2 [accept-language]
  (->> accept-language
       (re-find #".*[^;]?([a-z]{2})[;$]?.*")
       (second)))


#?(:clj
   (defn req-locale [req]
     (or (some-> (:account req) :account/locale supported-locales) ;; a known user explicitly set their language
         (some-> (:cookies req) (get "locale") supported-locales) ;; anonymous user explicitly set their language
         (some-> (:board req) :entity/locale-default supported-locales) ;; board has a preferred language
         (some-> (:org req) :entity/locale-default supported-locales) ;; org has preferred language
         (some-> (get-in req [:headers "accept-language"]) accept-language->639-2) ;; use the browser's language
         "en")))                                            ;; fallback to english

#?(:clj
   (defn set-locale!
     {:endpoint/route {:post ["/locale/" "set"]}
      :malli/in       :i18n/locale}
     [req {locale :body}]
     (tap> (vector :set-locale locale (some? (:account req))))
     (vd/assert locale :i18n/locale)
     (if (:account req)
       (do (re-db.api/transact! [{:db/id          (:db/id (:account req))
                                  :account/locale locale}])
           {:status 200
            :body   {:i18n/locale locale}})
       {:status  200
        :body    {:i18n/locale locale}
        :cookies {"locale" {:value   locale
                            :max-age 31536000
                            :path    "/"}}})))

#?(:clj
   (defn wrap-i18n [f] [f]
     (fn [req]
       (let [locale (req-locale req)]
         (binding [*selected-locale* locale]
           (f (assoc req :locale locale)))))))
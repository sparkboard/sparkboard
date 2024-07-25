(ns sb.i18n
  (:require [sb.authorize :as az]
            [sb.util :as u]
            [re-db.api :as db]
            [taoensso.tempura :as tempura]
            [sb.query :as q])
  #?(:cljs (:require-macros [sb.i18n :refer [ungroup-dict]])))

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
  (-> (u/map-transpose dict)
      (update-keys name)))

(def dict
  "Tempura-style dictionary of internationalizations, keyed by ISO-639-2.
See https://iso639-3.sil.org/code_tables/639/data/all for list of codes"
  (ungroup-dict                                             ;; grouped for easy generation of multiple languages
    {:tr/cancel                          {:en "Cancel"
                                          :fr "Annuler"
                                          :es "Cancelar"}
     :tr/team                            {:en "Team"
                                          :fr "Équipe"
                                          :es "Equipo"}
     :tr/add-badge                       {:en "Add badge"
                                          :fr "Ajouter un badge"
                                          :es "Añadir insignia"}
     :tr/edit                            {:en "Edit"
                                          :fr "Modifier"
                                          :es "Editar"}
     :tr/publish                         {:en "Publish"
                                          :fr "Publier"
                                          :es "Publicar"}
     :tr/drafts                          {:en "Drafts"
                                          :fr "Brouillons"
                                          :es "Borradores"}
     :tr/edit-tags                       {:en "Edit tags"
                                          :fr "Modifier les mots-clés"
                                          :es "Editar etiquetas"}
     :tr/add-tag                         {:en "Add tag"
                                          :fr "Ajouter un mot-clé"
                                          :es "Añadir etiqueta"}
     :tr/restricted                      {:en "Restricted"
                                          :fr "Restreint"
                                          :es "Restringido"}
     :tr/further-instructions            {:en "Further instructions"
                                          :fr "Instructions supplémentaires"
                                          :es "Instrucciones adicionales"}
     :tr/community-actions               {:en "Community actions"
                                          :fr "Actions communautaires"
                                          :es "Acciones comunitarias"}
     :tr/community-actions-add           {:en "Add up to 3 buttons to encourage visitors to support your project in specific way."
                                          :fr "Ajoutez jusqu'à 3 boutons pour encourager les visiteurs à soutenir votre projet de manière spécifique."
                                          :es "Agregue hasta 3 botones para alentar a los visitantes a apoyar su proyecto de manera específica."}
     :tr/confirm                         {:en "Are you sure?"
                                          :fr "Êtes-vous sûr ?"
                                          :es "¿Estás seguro?"}
     :tr/cannot-be-undone                {:en "This cannot be undone."
                                          :fr "Ceci ne peut pas être annulé."
                                          :es "Esto no se puede deshacer."}
     :tr/copy-link                       {:en "Copy link"
                                          :fr "Copier le lien"
                                          :es "Copiar enlace"}
     :tr/start-chat                      {:en "Start chat"
                                          :fr "Démarrer le chat"
                                          :es "Iniciar chat"}
     :tr/hover-text                      {:en "Hover text"
                                          :fr "Texte de survol"
                                          :es "Texto de desplazamiento"}
     :tr/choose-action                   {:en "Choose an action"
                                          :fr "Choisissez une action"
                                          :es "Elija una acción"}
     :tr/other                           {:en "Other"
                                          :fr "Autre"
                                          :es "Otro"}
     :tr/action                          {:en "Action"
                                          :fr "Action"
                                          :es "Acción"}
     :tr/share                           {:en "Share"
                                          :fr "Partager"
                                          :es "Compartir"}
     :tr/join-our-team                   {:en "Join our team"
                                          :fr "Rejoignez notre équipe"
                                          :es "Únete a nuestro equipo"}
     :tr/invest                          {:en "Invest"
                                          :fr "Investir"
                                          :es "Invertir"}
     :tr/delete                          {:en "Delete"
                                          :fr "Supprimer"
                                          :es "Eliminar"}
     :tr/field-type                      {:en "Field type"
                                          :fr "Type de champ"
                                          :es "Tipo de campo"},
     :tr/video-field                     {:en "Video field"
                                          :fr "Champ vidéo"
                                          :es "Campo de video"},
     :tr/video                           {:en "Video"
                                          :fr "Vidéo"
                                          :es "Video"},
     :tr/menu                            {:en "Menu"
                                          :fr "Menu"
                                          :es "Menú"},
     :tr/links                           {:en "Links"
                                          :fr "Liens"
                                          :es "Enlaces"},
     :tr/image                           {:en "Image"
                                          :fr "Image"
                                          :es "Imagen"},
     :tr/text                            {:en "Text"
                                          :fr "Texte"
                                          :es "Texto"},
     :tr/add                             {:en "Add"
                                          :fr "Ajouter"
                                          :es "Añadir"},
     :tr/selection-menu                  {:en "Selection menu"
                                          :fr "Menu de sélection"
                                          :es "Menú de selección"},
     :tr/web-links                       {:en "Web links"
                                          :fr "Liens Web"
                                          :es "Enlaces web"},
     :tr/image-field                     {:en "Image field"
                                          :fr "Champ d'image"
                                          :es "Campo de imagen"},
     :tr/text-field                      {:en "Text field"
                                          :fr "Champ de texte"
                                          :es "Campo de texto"},
     :tr/start-board-new                 {:en "### Start Something New\nNo boards yet? Start a hackathon and bring people together to solve problems."
                                          :fr "### Commencez quelque chose de nouveau\nPas encore de tableaux ? Démarrez un hackathon et rassemblez des personnes pour résoudre des problèmes."
                                          :es "### Comienza algo nuevo\n¿Aún no hay tableros? Inicie un hackathon y reúna a las personas para resolver problemas."}
     :tr/create-first-board              {:en "Create your first board"
                                          :fr "Créez votre premier tableau"
                                          :es "Crea tu primer tablero"},
     :tr/view-all                        {:en "View all"
                                          :fr "Voir tout"
                                          :es "Ver todo"}
     :tr/no-messages                     {:en "You have no messages."
                                          :fr "Vous n'avez pas de messages."
                                          :es "No tienes mensajes."}
     :tr/domain-already-registered       {:en "Domain already registered"
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
     :tr/project-fields                  {:en "Project fields"
                                          :fr "Champs de projet"
                                          :es "Campos de proyecto"},
     :tr/member-tags                     {:en "Member tags"
                                          :fr "Mots-clés des membres"
                                          :es "Etiquetas de miembros"},
     :tr/member-fields                   {:en "Member fields"
                                          :fr "Champs de membre"
                                          :es "Campos de miembro"},
     :tr/new-member                      {:en "New member"
                                          :fr "Nouveau membre"
                                          :es "Nuevo miembro"},
     :tr/tags                            {:en "Tags"
                                          :fr "Mots clés"
                                          :es "Etiquetas"},
     :tr/continue-with-email             {:en "Continue with email"
                                          :fr "Continuer avec courriel"
                                          :es "Continuar con correo electrónico"},
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
     :tr/logo                            {:en "Logo"
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
     :tr/continue-with-google            {:en "Continue with Google"
                                          :fr "Continuer avec Google"
                                          :es "Continuar con Google"}
     :tr/next                            {:en "Next"
                                          :fr "Suivant"
                                          :es "Siguiente"}
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
     :tr/label                           {:en "Label"
                                          :fr "Étiquette"
                                          :es "Etiqueta"}
     :tr/color                           {:en "Color"
                                          :fr "Couleur"
                                          :es "Color"}
     :tr/hint                            {:en "Hint"
                                          :fr "Indice"
                                          :es "Pista"}
     :tr/required?                       {:en "Required"
                                          :fr "Obligatoire"
                                          :es "Requerido"}
     :tr/show-as-filter?                 {:en "Show as filter"
                                          :fr "Afficher comme filtre"
                                          :es "Mostrar como filtro"}
     :tr/show-at-registration?           {:en "Show at registration"
                                          :fr "Afficher à l'inscription"
                                          :es "Mostrar en el registro"}
     :tr/show-on-card?                   {:en "Show on card"
                                          :fr "Afficher sur la carte"
                                          :es "Mostrar en la tarjeta"}
     :tr/untitled                        {:en "Untitled"
                                          :fr "Sans titre"
                                          :es "Sin título"}
     :tr/options                         {:en "Options"
                                          :fr "Options"
                                          :es "Opciones"}
     :tr/add-option                      {:en "Add option"
                                          :fr "ajouter une option"
                                          :es "añadir opción"}
     :tr/option-label                    {:en "Option label"
                                          :fr "étiquette d'option"
                                          :es "etiqueta de opción"}
     :tr/find-a-member                   {:en "Find a member"
                                          :fr "Trouver un membre"
                                          :es "Encontrar un miembro"}
     :tr/project-numbers                 {:en "Project numbers"
                                          :fr "Numéros de projet"
                                          :es "Números de proyecto"}
     :tr/max-members-per-project         {:en "Max members per project"
                                          :fr "Nombre maximum de membres par projet"
                                          :es "Máximo de miembros por proyecto"}
     :tr/sharing-buttons                 {:en "Sharing buttons"
                                          :fr "Boutons de partage"
                                          :es "Botones de compartir"}
     :tr/home-page-message               {:en "Home page message"
                                          :fr "Message de la page d'accueil"
                                          :es "Mensaje de la página de inicio"}
     :tr/max-projects-per-member         {:en "Max projects per member"
                                          :fr "Nombre maximum de projets par membre"
                                          :es "Máximo de proyectos por miembro"}
     :tr/registration-codes              {:en "Registration codes"
                                          :fr "Codes d'inscription"
                                          :es "Códigos de registro"}
     :tr/invite-email-text               {:en "Invite email text"
                                          :fr "Texte de l'e-mail d'invitation"
                                          :es "Texto del correo electrónico de invitación"}
     :tr/registration-newsletter-field?  {:en "Registration newsletter field"
                                          :fr "Champ d'inscription à la newsletter"
                                          :es "Campo de registro de boletín"}
     :tr/anyone-may-join                 {:en "Anyone may join"
                                          :fr "Tout le monde peut rejoindre"
                                          :es "Cualquiera puede unirse"}
     :tr/invite-only                     {:en "Invite only"
                                          :fr "Invitation seulement"
                                          :es "Solo por invitación"}
     :tr/registration-page-message       {:en "Registration page message"
                                          :fr "Message de la page d'inscription"
                                          :es "Mensaje de la página de registro"}
     :tr/registration-url-override       {:en "Registration URL override"
                                          :fr "Remplacement de l'URL d'inscription"
                                          :es "Anulación de la URL de registro"}
     :tr/remove                          {:en "Remove"
                                          :fr "Supprimer"
                                          :es "Eliminar"}
     :tr/remove?                         {:en "Are you sure you want to remove this?"
                                          :fr "Etes-vous sûr de vouloir supprimer ceci ?"
                                          :es "¿Estás segura de que quieres eliminar esto?"}
     :tr/basic-settings                  {:en "Basic settings"
                                          :fr "Paramètres de base"
                                          :es "Configuración básica"}
     :tr/projects-and-members            {:en "Projects and members"
                                          :fr "Projets et membres"
                                          :es "Proyectos y miembros"}
     :tr/registration                    {:en "Registration"
                                          :fr "Inscription"
                                          :es "Registro"}
     :tr/sort-order                      {:en "Sort order"
                                          :fr "Ordre de tri"
                                          :es "Orden de clasificación"}
     :tr/sort-default                    {:en "Default"
                                          :fr "Défaut"
                                          :es "Por defecto"}
     :tr/sort-random                     {:en "Random"
                                          :fr "Aléatoire"
                                          :es "Aleatorio"}
     :tr/sort-entity-created-at-asc      {:en "Creation time ascending"
                                          :fr "Temps de creation ascendant"
                                          :es "Tiempo de creación ascendente"}
     :tr/sort-entity-created-at-desc     {:en "Creation time descending"
                                          :fr "Temps de creation descendant"
                                          :es "Tiempo de creación descendente"}
     :tr/post                            {:en "Post"
                                          :fr "Publier"
                                          :es "Publicar"}
     :tr/questions-and-comments          {:en "Questions and comments"
                                          :fr "Questions et commentaires"
                                          :es "Preguntas y comentarios"}
     :tr/reply                           {:en "Reply"
                                          :fr "Répondre"
                                          :es "Responder"}
     :tr/replies                         {:en "replies"
                                          :fr "réponses"
                                          :es "respuestas"}
     :tr/requests                        {:en "Looking for"
                                          :fr "À la recherche de"
                                          :es "Buscando"}
     :tr/add-request                     {:en "Add request"
                                          :fr "ajouter une demande"
                                          :es "Añadir solicitud"}
     :tr/request-text                    {:en "Request text"
                                          :fr "texte de la demande"
                                          :es "texto de solicitud"}
     :tr/filters                         {:en "Filters"
                                          :fr "Filtres"
                                          :es "Filtros"}
     :tr/my-projects                     {:en "My projects"
                                          :fr "Mes projets"
                                          :es "Mis proyectos "}
     :tr/looking-for-help                {:en "Looking for help"
                                          :fr "Cherchent de l'aide"
                                          :es "En busca de ayuda"}
     }))

(defn tr*
  ([resource-ids]
   (tempura/tr {:dict dict} (locales) (cond-> resource-ids
                                              (keyword? resource-ids)
                                              vector)))
  ([resource-ids resource-args]
   (tempura/tr {:dict dict} (locales)
               (cond-> resource-ids
                       (keyword? resource-ids)
                       vector)
               resource-args)))

(defn t
  ([resource-ids] (or (tr* resource-ids)
                      #?(:cljs (doto (str "Missing" resource-ids) js/console.warn))))
  ([resource-ids resource-args] (or (tr* resource-ids resource-args)
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

(q/defx set-locale!
  {:prepare [az/with-account-id]}
  [{:keys [i18n/locale account-id]}]
  ((resolve 'sb.validate/assert) locale :i18n/locale)
  {:http/response
   (if account-id
     (do (re-db.api/transact! [[:db/add account-id :account/locale locale]])
         {:status 200
          :body   {:i18n/locale locale}})
     {:status  200
      :body    {:i18n/locale locale}
      :cookies {"locale" {:value   locale
                          :max-age 31536000
                          :path    "/"}}})})


#?(:clj
   (defn wrap-i18n [f] [f]
     (fn [req]
       (let [locale (req-locale req)]
         (binding [*selected-locale* locale]
           (f (assoc req :locale locale)))))))

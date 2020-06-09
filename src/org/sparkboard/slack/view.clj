(ns org.sparkboard.slack.view
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [org.sparkboard.js-convert :refer [kw->js clj->json]]
            [org.sparkboard.server.slack.core :as slack]
            [taoensso.timbre :as log]
            [org.sparkboard.server.slack.hiccup :as hiccup]
            [org.sparkboard.util :as u]
            [clojure.set :as set]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; formatting helpers

(defn truncate [s max-len]
  (if (> (count s) max-len)
    (str (subs s 0 (dec max-len)) "…")
    s))

(defn blockquote [text]
  (str "> " (str/replace text "\n" "\n> ")))

(defn link [text url]
  (str "<" url "|" text ">"))

(defn channel-link [id]
  (str "<#" id ">"))

(defn mention [user-id]
  (str "<@" user-id "> "))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; normalized handling of input values

(defn action-value [action]
  (case (:type action)
    ("checkboxes"
      "multi_external_select"
      "multi_static_select") (->> (:selected_options action)
                                  (map :value)
                                  (into #{}))
    "multi_users_select" (set (:selected_users action))
    "multi_conversations_select" (set (:selected_conversations action))
    "multi_channels_select" (set (:selected_channels action))
    ("static_select"
      "external_select"
      "radio_buttons"
      "overflow") (-> action :selected_option :value)
    "plain_text_input" (:value action)
    "users_select" (:selected_user action)
    "datepicker" (:selected_date action)
    "button" nil
    (do
      (log/warn :not-parsing-action action)
      action)))

(defn actions-values [actions]
  (->> actions
       (reduce (fn [values action]
                 (assoc values (:action_id action) (action-value action))) {})))

(defn input-values
  "Returns all of the normalized values from a view's input blocks"
  [view]
  (->> view
       :state
       :values
       vals
       (apply merge)
       (reduce-kv (fn [m k action]
                    (assoc m k (action-value action))) {})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; WIP - api for making views with local state

(defonce registry (atom {}))
(def ^:dynamic *view* nil)

;; the following is WIP
;; issues TBD:
;; - can be extended to support `home` screens as well as modals
;; - a way expose action-ids for opening modals / updating home screen
;; - unsure about using an atom for this api - may change action-fns to
;;   be pure functions of [state, value, context] => new state

(defn render [view context & {:keys [state props]}]
  (let [{::keys [render-fn view-name]} view]
    (binding [*view* view]
      (-> (render-fn {:context context
                      :state (atom state)
                      :props props})
          (assoc-in [1 :private_metadata] state)
          (assoc-in [1 :callback_id] view-name)))))

(defn view-api [hiccup method context opts]
  (slack/web-api method
                 {:auth/token (:slack/bot-token context)}
                 (->> {:view (hiccup/->blocks-json hiccup)}
                      (merge opts)
                      (reduce-kv (fn [m k v] (cond-> m (some? v) (assoc k v))) {}))))

(defn trigger [context]
  {:post [(some? %)]}
  (-> context :slack/payload :trigger_id))

(defn open! [context view & {:keys [props]}]
  (-> (render view context :state (:initial-state view) :props props)
      (view-api "views.open"
                context
                {:trigger_id (trigger context)})))

(defn push! [context view & {:keys [props]}]
  (-> (render view context :state (:initial-state view) :props props)
      (view-api "views.push"
                context
                {:trigger_id (trigger context)})))

(defn replace! [context view & {:keys [props view-id]}]
  (-> (render view context :state (:initial-state view) :props props)
      (view-api "views.update"
                context
                {:view_id view-id
                 :trigger_id (trigger context)})))

(defn update! [context view & {:keys [props state view-id hash]}]
  (let [state (or state
                  (when (= (::view-name view)
                           (-> context :slack/payload :view :callback_id))
                    (-> context :slack/payload :view :private_metadata)))]
    (-> (render view context :state state :props props)
        (view-api "views.update"
                  context
                  {:hash (or hash (-> context :slack/payload :view :hash))
                   :view_id (or view-id (-> context :slack/payload :view :id))
                   :trigger_id (trigger context)}))))

(defn home! [context view & {:keys [props user-id]}]
  {:pre [user-id]}
  (-> (render context view :state (:initial-state view) :props props)
      (view-api "views.publish"
                context
                {:user_id user-id})))

(defn handle-home-opened! [context view & {:keys [props]}]
  (-> (render context view :state (-> context :slack/payload :view :private_metadata) :props props)
      (view-api "views.publish"
                context
                {:user_id (:slack/user-id context)})))

(defn handle-block-action
  "Calls a block action with [context, state-atom, block-value]"
  [context view action-id action-fn]
  (let [prev-state (-> context :slack/payload :view :private_metadata)
        all-values (-> context :slack/payload :actions actions-values)
        value (get all-values action-id)
        context (assoc context ::view view)
        state-atom (atom prev-state)
        _ (action-fn {:context context :state state-atom :value value :view view})
        next-state @state-atom]
    (log/trace action-id action-value {:prev-state prev-state
                                       :next-state next-state})
    (when (and next-state (not= prev-state next-state))
      (update! context view :state next-state))))

(defn return-json [status body]
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body (clj->json body)})

(defn handle-form-callback
  "Calls a form action with [context, state-atom, input-values]"
  [context kind form-fn]
  (let [rsp-view (-> context :slack/payload :view)
        result (form-fn {:context context
                         :state (atom (:private_metadata rsp-view))
                         :value (input-values rsp-view)})]
    (case kind
      :close result
      :submit (when-let [[action view] result]
                (assert (and (#{:push :update} action)
                             "on-submit should return [:push <view>] or [:update <view>] or nil"))
                (return-json 200
                             {:response_action action
                              :view (hiccup/->blocks view)})))))

(defn scoped-id [view-name k]
  ;; actions need globally-unique names, so we append them to their parent modal's name
  (str view-name ":" (name k)))

(defn make-view* [{:as view
                   ::keys [view-name actions]
                   :keys [on-submit
                          on-close]}]
  (let [view (reduce-kv (fn [m action-name action-fn]
                          (let [id (scoped-id view-name action-name)]
                            (-> m
                                (assoc-in [::actions action-name] id)
                                (assoc-in [::handlers (keyword "block_actions" id)]
                                          #(handle-block-action % view id action-fn)))))
                        view
                        actions)
        view (cond-> view
                     on-submit
                     (assoc-in [::handlers (keyword "view_submission" view-name)]
                               #(handle-form-callback % :submit on-submit))
                     on-close
                     (assoc-in [::handlers (keyword "view_close" view-name)]
                               #(handle-form-callback % :close on-submit)))]
    (-> view
        (merge (::actions view))            ;; expose :actions as bare key
        (dissoc ::actions))))

(defmacro defview
  "Defines a modal or home surface"
  [name-sym & args]
  (let [[options [argv & body]] (if (map? (first args))
                                  [(first args) (rest args)]
                                  [nil args])
        view-name (str name-sym)
        options (atom (set/rename-keys options {:actions ::actions}))
        body (walk/postwalk
               (fn [x]
                 (if-not (map? x)
                   x
                   (cond (:action x)
                         (if (map? (:action x))
                           (let [[action-k action-form] (first (:action x))]
                             (swap! options update ::actions merge (:action x))
                             (-> (dissoc x :action)
                                 (assoc :action-id (scoped-id view-name action-k))))
                           (set/rename-keys x {:action :action-id}))
                         (:on-submit x) (do (swap! options assoc :on-submit (:on-submit x))
                                            (dissoc x :on-submit))
                         (:on-close x) (do (swap! options assoc :on-close (:on-close x))
                                           (dissoc x :on-close))
                         :else x))) (last body))]
    `(do (def ~name-sym
           (make-view* (merge {::view-name ~view-name
                               ::render-fn (fn ~name-sym ~argv ~body)}
                              ~(deref options))))
         (swap! registry merge (::handlers ~name-sym))
         #'~name-sym)))

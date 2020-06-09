(ns org.sparkboard.slack.view
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [org.sparkboard.js-convert :refer [kw->js]]
            [org.sparkboard.server.slack.core :as slack]
            [taoensso.timbre :as log]
            [org.sparkboard.server.slack.hiccup :as hiccup]
            [org.sparkboard.util :as u]))

(defn truncate [s max-len]
  (if (> (count s) max-len)
    (str (subs s 0 (dec max-len)) "…")
    s))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; helpers for formatting in Slack's mrkdwn

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

(defn render [{:as view :keys [render-fn actions view-name]} context state]
  (binding [*view* view]
    (-> (render-fn (assoc context ::view view
                                  ::actions actions
                                  ::state (atom state)))
        (assoc-in [1 :private_metadata] state)
        (assoc-in [1 :callback_id] view-name))))

(defn view-api [hiccup method context opts]
  (slack/web-api method
                 {:auth/token (:slack/bot-token context)}
                 (merge {:view (hiccup/->blocks-json hiccup)}
                        opts)))

(defn open! [view context]
  (-> (render view context (:initial-state view))
      (view-api "views.open"
                context
                {:trigger_id (-> context :slack/payload :trigger_id)})))

(defn push! [view context]
  (-> (render view context (:initial-state view))
      (view-api "views.push"
                context
                {:trigger_id (-> context :slack/payload :trigger_id)})))

(defn update! [view context state]
  (-> (render view context state)
      (view-api "views.update"
                context
                {:hash (-> context :slack/payload :view :hash)
                 :view_id (-> context :slack/payload :view :id)})))

(defn set-home! [view context state user-id]
  (-> (render view context (or state (:initial-state view)))
      (view-api "views.publish"
                context
                {:user_id user-id})))

(defn handle-home-opened! [view context]
  (-> (render view context (or (-> context :slack/payload :view :private_metadata (u/guard seq))
                               (:initial-state view)))
      (view-api "views.publish"
                context
                {:user_id (:slack/user-id context)})))

(defn handle-block-action
  "Calls a block action with [context, state-atom, block-value]"
  [context view action-id action-fn ]
  (let [prev-state (-> context :slack/payload :view :private_metadata)
        all-values (-> context :slack/payload :actions actions-values)
        value (get all-values action-id)
        context (assoc context ::view view)
        state-atom (atom prev-state)
        _ (action-fn context state-atom value)
        next-state @state-atom]
    (log/trace action-id action-value {:prev-state prev-state
                                       :next-state next-state})
    (when (and next-state (not= prev-state next-state))
      (update! view context next-state))))

(defn handle-form-callback
  "Calls a form action with [context, state-atom, input-values]"
  [context form-fn]
  (let [rsp-view (-> context :slack/payload :view)]
    (form-fn context (atom (:private_metadata rsp-view)) (input-values rsp-view))))

(defn make-view* [{:as view
                   :keys [view-name
                          actions
                          on-submit
                          on-close]}]
  (let [scoped-id #(str view-name ":" (name %))
        view (assoc view :actions (reduce (fn [m action-name] (assoc m action-name (scoped-id action-name))) {} (keys actions)))
        view (reduce-kv (fn [m action-name action-fn]
                          (let [id (scoped-id action-name)]
                            (-> m
                                (assoc-in [:handlers (keyword "block_actions" id)]
                                          #(handle-block-action % view id action-fn)))))
                        view
                        actions)
        view (cond-> view
                     on-submit
                     (assoc-in [:handlers (keyword "view_submission" view-name)] #(handle-form-callback % on-submit))
                     on-close
                     (assoc-in [:handlers (keyword "view_close" view-name)] #(handle-form-callback % on-submit)))]
    view))

(defmacro defmodal [name-sym & args]
  (let [[options [argv body]] (if (map? (first args))
                                [(first args) (rest args)]
                                [nil args])
        view-name (str name-sym)
        options (atom options)
        body (walk/postwalk
               (fn [x]
                 (if-not (map? x)
                   x
                   (cond (::action x)
                         (let [action-form (::action x)
                               action-sym (second action-form)
                               action-k (keyword (name action-sym))
                               _ (assert (and (symbol? action-sym)))]
                           (swap! options assoc-in [:actions action-k] action-form)
                           (-> (dissoc x ::action)
                               (assoc :action_id `(-> *view* :actions ~action-k))))
                         (::on-submit x) (do (swap! options assoc :on-submit (::on-submit x))
                                             (dissoc x ::on-submit))
                         (::on-close x) (do (swap! options assoc :on-close (::on-close x))
                                            (dissoc x ::on-close))
                         :else x))) body)]
    `(do (def ~name-sym
           (make-view* (merge {:view-name ~view-name
                               :render-fn (fn ~name-sym ~argv ~body)}
                              ~(deref options))))
         (swap! registry merge (:handlers ~name-sym))
         #'~name-sym)))

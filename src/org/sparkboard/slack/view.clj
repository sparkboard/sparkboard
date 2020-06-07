(ns org.sparkboard.slack.view
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [org.sparkboard.js-convert :refer [kw->js]]
            [org.sparkboard.server.slack.core :as slack]
            [taoensso.timbre :as log]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; helpers for formatting in Slack's mrkdwn

(defn blockquote [text]
  (str "> " (str/replace text "\n" "\n> ")))

(defn link [text url]
  (str "<" url "|" text ">"))

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

(defn local-state [view]
  (:private_metadata view))

;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; WIP - api for making views with local state

(defonce registry (atom {}))

;; the following is WIP
;; issues TBD:
;; - can be extended to support `home` screens as well as modals
;; - a way expose action-ids for opening modals / updating home screen

(defn with-state [view-hiccup state]
  (assoc-in view-hiccup [1 :private_metadata] state))

(defn render-view [view-fn context state-atom]
  (with-state (view-fn context state-atom) @state-atom))

(defn action-handler
  "Returns a handler that will update a modal, given:
   - modal-fn, accepts [context, state-atom] & returns a [:modal ..] hiccup form
   - action-fn, a function of [value] that can swap! the state-atom"
  [view-fn action-id on-action]
  (fn [{{:keys [view actions]} :slack/payload :as context}]
    (let [state (atom (:private_metadata view))
          all-values (actions-values actions)
          value (get all-values action-id)
          _ (on-action context state value)                 ;; for side effects
          next-view (render-view view-fn context state)]
      (log/trace action-id action-value {:prev-state (:private_metadata view)
                                         :next-state @state})
      (slack/views-update (:id view) next-view))))

(defn view-opener
  "Returns a handler that will open a modal, given:
   - modal-fn, accepts [context, state] & returns a [:modal ..] hiccup form
   - initial-state, a map"
  [view-fn initial-state open-fn]
  (fn [context]
    (-> (render-view view-fn context (atom initial-state))
        open-fn)))

(defn add-ns [parent action-id]
  (cond (string? action-id) (str parent ":" action-id)
        (keyword? action-id) (kw->js action-id)
        :else (str action-id)))

(defn make-view* [{::keys [actions]
                   :keys [name-str
                          initial-state
                          on_submit
                          on_close
                          view-fn]}]
  (let [open! (view-opener view-fn initial-state slack/views-open)
        push! (view-opener view-fn initial-state slack/views-push)]
    {:handlers
     (merge
       {(keyword "block_actions" (add-ns name-str "open")) open!
        (keyword "block_actions" (add-ns name-str "push")) push!}
       (when on_submit {(keyword "view_submission" name-str) on_submit})
       (when on_close {(keyword "view_closed" name-str) on_close})
       (reduce-kv (fn [m action-id action-fn]
                    (assoc m
                      (keyword "block_actions" action-id)
                      (action-handler view-fn action-id action-fn))) {} actions))}))

(defmacro defmodal [name-sym initial-state argv body]
  (let [name-str (str name-sym)
        found (atom {})
        consume (fn [x k]
                  (swap! found assoc k (get x k))
                  (dissoc x k))
        add-ns (partial add-ns name-str)
        body (walk/postwalk
               (fn [x]
                 (if-not (map? x)
                   x
                   (cond (::action x)
                         (do (swap! found assoc-in [::actions (add-ns (:action_id x))]
                                    `(fn [~@argv value#]
                                       (~(::action x) value#)))
                             (-> (dissoc x ::action)
                                 (update :action_id add-ns)))
                         (:on_submit x) (consume x :on_submit)
                         (:on_close x) (consume x :on_close)
                         (:action_id x) (update x :action_id add-ns)
                         :else x))) body)]
    `(do (def ~name-sym
           (make-view* (merge {:name-str ~name-str
                               :view-fn (fn ~argv ~body)
                               :initial-state ~initial-state}
                              ~(deref found))))
         (swap! registry merge (:handlers ~name-sym))
         #'~name-sym)))

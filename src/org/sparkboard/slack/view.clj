(ns org.sparkboard.slack.view
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [org.sparkboard.js-convert :refer [kw->js]]
            [org.sparkboard.server.slack.core :as slack]
            [taoensso.timbre :as log]))

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
  (into {} (map (juxt :action_id action-value)) actions))

(defn view-values [view]
  (->> (apply merge (vals (:values (:state view))))
       (reduce-kv (fn [m k action]
                    (assoc m k (action-value action))) {})
       (merge (:private_metadata view))))

(defn assoc-some [m k v]
  (if v
    (assoc m k v)
    m))

;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Setting form values on Slack inputs (inconsistent keys + breaks on nil)

(defn- all-options [{:keys [options option-groups]}]
  (concat options (->> option-groups (mapcat :options))))

(defn assoc-option
  [m as-key value]
  (assoc-some m as-key (first (filter #(= (:value %) value) (all-options m)))))

(defn assoc-options
  [m as-key values]
  (assoc-some m as-key (seq (filter #(contains? values (:value %)) (all-options m)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Testing local-state

(defn modal-updater
  "Returns a handler that will update a modal, given:
   - modal-fn, accepts [context, state] & returns a [:modal ..] hiccup form
   - reducer, a function of [state] that returns a new state"
  [modal-fn on-action]
  (fn [k {:as context
          {:keys [view actions]} :slack/payload}]
    (log/info :actions-values (actions-values actions) actions)
    (let [prev-state (:private_metadata view)
          next-state (on-action prev-state (actions-values actions))]
      (log/info :prev prev-state :next next-state)
      (slack/views-update (:id view)
                          (-> (modal-fn context next-state)
                              (assoc-in [1 :private_metadata] next-state))))))

(defn modal-opener
  "Returns a handler that will open a modal, given:
   - modal-fn, accepts [context, state] & returnsn a [:modal ..] hiccup form
   - initial-state, a map"
  [modal-fn initial-state open-fn]
  (fn [_ context]
    (-> (modal-fn context initial-state)
        (assoc-in [1 :private_metadata] initial-state)
        open-fn)))

(defn add-ns [parent action-id]
  (cond (string? action-id) (str parent ":" action-id)
        (keyword? action-id) (kw->js action-id)
        :else (str action-id)))

(defn make-modal* [{:as modal*
                    :keys [modal-name
                           modal-str
                           initial-state
                           on_actions
                           on_submit
                           on_close
                           modal-fn]}]
  (merge
    {(keyword "block_actions" (add-ns modal-str "open")) (modal-opener modal-fn initial-state slack/views-open)
     (keyword "block_actions" (add-ns modal-str "push")) (modal-opener modal-fn initial-state slack/views-push)}
    (when on_submit
      {(keyword "view_submission" modal-str) on_submit})
    (when on_close
      {(keyword "view_closed" modal-str) on_close})
    (reduce-kv (fn [m action_id on-action]
                 (assoc m
                   (keyword "block_actions" action_id)
                   (modal-updater modal-fn
                                  (fn [state values]
                                    (log/info :updater-receives-values action_id values)
                                    (on-action state (get values action_id)))))) {} on_actions)))

;; formatting
(defn blockquote [text]
  (str "> " (str/replace text "\n" "\n> ")))
(defn link [text url]
  (str "<" url "|" text ">"))

(defmacro defmodal [modal-name initial-state argv body]
  (let [modal-str (str modal-name)
        found (atom {})
        consume (fn [x k]
                  (swap! found assoc k (get x k))
                  (dissoc x k))
        add-ns (partial add-ns modal-str)
        body (walk/postwalk
               (fn [x]
                 (if-not (map? x)
                   x
                   (cond (:on_action x)
                         (do (swap! found assoc-in [:on_actions (add-ns (:action_id x))] (:on_action x))
                             (-> (dissoc x :on_action)
                                 (update :action_id add-ns)))
                         (:on_submit x) (consume x :on_submit)
                         (:on_close x) (consume x :on_close)
                         (:action_id x) (update x :action_id add-ns)
                         :else x))) body)]
    `(def ~modal-name
       (make-modal* (merge {:modal-name '~(symbol (str *ns*) modal-str)
                            :modal-str ~modal-str
                            :modal-fn (fn ~argv ~body)
                            :initial-state ~initial-state}
                           ~(deref found))))))

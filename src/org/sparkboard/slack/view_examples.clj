(ns org.sparkboard.slack.view-examples
  (:require [clojure.string :as str]
            [org.sparkboard.slack.view :as v]))

(v/defview counter
  "simple usage of the `state` atom"
  {:initial-state 1}
  [{:as context :keys [state]}]
  [:modal {:title (str "Counter!")}
   [:section
    [:md (str/join (take @state (repeat "🍎")))]]
   [:actions
    [:button {:action-id
              {:count!                                      ;; this ID will be expanded to `counter/count!`
               (fn [{:keys [state]}] (swap! state inc))}} "Inc!"]]])

;; defview returns (basically) function that you see here, wrapped to take care of
;; setting dynamic context. metadata is added which includes the handlers registered
;; for the view, and the actions that were found.
(comment
  (meta counter)
  ;; =>
  {:initial-state 1,
   :actions {:count! :counter/count!},
   :view-name "counter",
   :handlers #:counter{:count! ...}})


(v/defview modals-submit
  [{:keys [state]}]
  [:modal {:title "Modals: Submission"
           :submit "Submit"
           :on-submit (fn [{:keys [state input-values]}]
                        (swap! state assoc :submitted input-values))}
   [:context
    [:md
     "An `on-submit` handler swaps our `:state` atom with the `input-values` "
     "from the modal."]]
   [:input
    {:label "Text field"}
    [:plain-text-input
     {:action-id :text-field}]]
   (when-let [submitted (:submitted @state)]
     (list
       [:divider]
       [:section [:md "*SUBMITTED:*\n" submitted]]))])

(v/defview modals-open
  [_]
  [:modal {:title "Modals: Opening"}

   [:section
    {:accessory
     [:button {:text "Push"
               :action-id
               {:push (partial v/push! (constantly [:modal {:title "Pushed"} [:section [:md "🍎"]]]))}}]}
    [:md "Push a modal onto the stack"]]

   [:section
    {:accessory
     [:button {:text "Replace"
               :action-id
               {:replace (fn [context]
                           (v/replace! (constantly [:modal {:title "Replaced"} [:section [:md "🍎"]]])
                                       context))}}]}
    [:md "Replace the current modal"]]

   [:section
    {:accessory
     [:button {:text "Open 💀️️"
               :action-id
               {:open (partial v/open! (constantly [:modal {:title "Opened"} [:section [:md "🍎"]]]))}}]}
    [:md "Open will not work here because a modal already exists"]]])

(v/defview multi-select-modal
  [{:keys [state]}]
  [:modal {:title "Multi-Select examples"
           :submit "Submit"
           :on-submit (fn [{:keys [input-values state]}]
                        [:update [:modal {:title "Result"}
                                  [:section
                                   [:md (str {:values (str input-values)
                                              :state (str @state)})]]]])}
   [:section
    {:accessory
     [:multi-static-select
      {:placeholder "Select some..."
       :action-id {:static
                   (fn [{:keys [state value]}]
                     (swap! state assoc :multi-static value))}
       :set-value (:multi-static @state)
       :options [{:value "multi-1"
                  :text [:plain-text "Multi 1"]}
                 {:value "multi-2"
                  :text [:plain-text "Multi 2"]}]}]}
    [:md "Multi-select (section)"]]
   [:section
    {:accessory
     [:multi-users-select
      {:placeholder "Select some..."
       :action-id {:users
                   (fn [{:keys [state value]}]
                     (swap! state assoc :users value))}
       :set-value (:users @state)}]}
    "Multi-users-select (section)"]

   [:input
    {:label "Multi-conversations-select (input)"
     :optional true}
    [:multi-conversations-select
     {:placeholder "Select some..."
      :action-id :multi-conversations
      :set-value (:conversations @state)}]]

   [:input
    {:label "Multi-channels-select (input)"
     :optional true}
    [:multi-channels-select
     {:placeholder "Select some..."
      :action-id :multi-channels}]]])

(v/defview all-field-types
  {:initial-state {:show-state? true
                   :radio-buttons "r2"
                   :checkboxes #{"value-test-1"}}}
  [{:keys [state]}]
  [:modal {:title "State test"}
   [:actions
    [:datepicker {:action-id {:datepicker (fn [{:keys [state value]}]
                                            (swap! state assoc :datepicker value))}
                  :set-value (:datepicker @state)}]

    [:overflow {:action-id {:overflow (fn [{:keys [state value]}]
                                        (swap! state assoc :overflow value))}
                :options [{:value "o1"
                           :text [:plain-text "O1"]}
                          {:value "o2"
                           :text [:plain-text "O2"]}]}]

    [:users-select
     {:placeholder "Pick a person"
      :action-id {:users-select (fn [{:keys [state value]}]
                                  (swap! state assoc :users-select value))}
      :set-value (:users-select @state)}]

    [:button {:action-id {:open-select-modal (partial v/push! multi-select-modal)}}
     "Open multi-select modal"]]

   [:actions
    [:radio-buttons
     {:action-id {:radio-buttons
                  (fn [{:keys [state value]}]
                    (swap! state assoc :radio-buttons value))}
      :set-value (:radio-buttons @state)
      :options [{:value "r1"
                 :text [:plain-text "Radio 1"]}
                {:value "r2"
                 :text [:plain-text "Radio 2"]}]}]

    [:checkboxes
     {:action-id {:checkboxes (fn [{:keys [state value]}]
                                (swap! state assoc :checkboxes value))}
      :set-value (:checkboxes @state)
      :options [{:value "value-test-1"
                 :text [:md "Check 1"]}
                {:value "value-test-2"
                 :text [:md "Check 2"]}]}]]

   (when (:show-state? @state)
     [:section {:fields (for [[k v] @state]
                          [:md "*" (name k) "*\n" v])}])
   [:actions
    [:button {:action-id
              {:toggle-state-view
               (fn [{:keys [state]}] (swap! state update :show-state? not))}}
     (if (:show-state? @state) "hide state" "show state")]]])

(v/defview branching-submit [{:as context :keys [state]}]
  [:modal
   {:title "Branching submit"
    :submit "Submit"
    :on-submit (fn [{:keys [input-values]}]
                 ;; on-submit can return a vector of [#{:update, :push}, hiccup]
                 ;; to replace the current modal or push a new one.
                 [:update [:modal {:title "Result"}
                           [:section [:md (str input-values)]]]])}
   [:input {:label "Message"}
    [:plain-text-input {:action-id :message}]]
   [:actions
    [:checkboxes
     {:action-id {:options (fn [{:keys [state value]}]
                             (swap! state assoc :options value))}
      :set-value (:options @state)
      :options [{:value "log-to-channel"
                 :text [:md "Log to channel..."]}]}]]
   (when (contains? (:options @state) "log-to-channel")
     [:input
      {:label "Select a channel"}
      [:multi-conversations-select
       {:action-id :selected-channel}]])])

(v/defview dev-overflow [_]
  [:overflow
   {:action-id {:overflow (fn [{:as context :keys [state value]}]
                            (case value
                              "counter" (v/open! counter context)
                              "modals-open" (v/open! modals-open context)
                              "modals-submit" (v/open! modals-submit context)
                              "all-field-types" (v/open! all-field-types context)
                              "branching-submit" (v/open! branching-submit context)))}
    :options [{:value "counter"
               :text [:plain-text "Counter"]}
              {:value "modals-open"
               :text [:plain-text "Modals: Open"]}
              {:value "modals-submit"
               :text [:plain-text "Modals: Submit"]}
              {:value "all-field-types"
               :text [:plain-text "All field types"]}
              {:value "branching-submit"
               :text [:plain-text "Branching Submit"]}]}])

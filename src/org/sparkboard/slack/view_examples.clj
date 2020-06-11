(ns org.sparkboard.slack.view-examples
  (:require [org.sparkboard.slack.view :as v]
            [clojure.pprint :as pp]
            [taoensso.timbre :as log]))


(v/defview branching-submit [{:as context :keys [state]}]
  [:modal
   {:title "Branching submit"
    :submit "Submit"
    :on-submit (fn [{:keys [input-values]}]
                 [:update [:modal {:title "Result"}
                           [:section (str input-values)]]])}
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
     (list
       [:input
        {:label "Select a channel"}
        [:multi-conversations-select
         {:action-id :selected-channel}]]
       [:section (v/blockquote "Now we see the channel selector, but the text box has lost its value - and there was no way for us to 'save' it or pass it through from the checkbox action-handler.")]))])

(v/defview multi-select-modal
           [{:keys [state]}]
           [:modal {:title "Multi-Select examples"
                    :submit "Submit"
                    :on-submit (fn [{:as context :keys [input-values state]}]
                                 [:update [:modal {:title "Result"}
                                           [:section (str {:values (str input-values)
                                                           :state (str @state)})]]])}
            [:section
             {:accessory
              [:multi-static-select
               {:placeholder "Select some..."
                :action-id {:static
                            (fn [{:keys [state value] :as context}]
                              (log/info :CCC context)
                              (swap! state assoc :multi-static value))}
                :set-value (:multi-static @state)
                :options [{:value "multi-1"
                           :text [:plain-text "Multi 1"]}
                          {:value "multi-2"
                           :text [:plain-text "Multi 2"]}
                          {:value "multi-3"
                           :text [:plain-text "Multi 3"]}]}]}
             "Multi-select (section)"]
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

(v/defview checks-test
           {:initial-state {:counter 0
                            :show-state? true
                            :checkboxes #{"value-test-1"}}}
           [{:keys [state props]}]
           [:modal {:title "State test"}
            [:actions
             [:button {:action-id {:counter (fn [{:keys [state]}]
                                              (swap! state update :counter inc))}}
              (str "Clicked " (str "(" (:counter @state) ")"))]
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
               :set-value (:radio-buttons @state "r2")
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
              [:section (with-out-str (pp/pprint @state))])
            [:actions
             [:button {:action-id {:toggle-state-view
                                   (fn [{:keys [state]}] (swap! state update :show-state? not))}}
              (if (:show-state? @state) "hide state" "show state")]]])

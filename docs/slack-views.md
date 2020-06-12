# Slack view system

Our view system is implemented primarily in `org.sparkboard.slack.hiccup` and `org.sparkboard.slack.view`.

- A `defview` macro behaves like `defn` and should return hiccup. Every view takes 1 argument, the root context map, which is augmented with a `:state` key containing a "local state atom" for the view. Swapping this atom from within an action or on-submit callback will cause the view to re-render with the new state.
- No evaluation context is shared across renders or callbacks. We store local-state as serialized transit+json in the modal/home-tab's `private_metadata` field. A new local state atom is created for every render or callback evaluation. The server is stateless with respect to client views.
- Modals are assigned a unique id based on their name (passed to defview), which should be globally unique. This is assigned to the modal as its `callback_id`. We do not include the clojure namespace in the name -- this is to make refactoring less painful, as IDs become part of your public API.
- Slack requires globally-unique `action_id` values for every "interactive" block (like a button or text field). Defining an action_id is kind of like defining a URL because once blocks have been published "in the wild" they cannot be changed. Instead of coming up with long unique names for every action, the `defview` macro accepts simple keywords as action-ids, and will then expand that by adding the parent view's name as its namespace. Eg, `:message` will become `parent-modal-name/message`.
    - You can pass a string ID if you want to fully specify the global ID and it will not be augmented in this way. We are lucky to only have published 1 visible button with a permanent action-id that can't be changed - the reply button on team-broadcast messages. We specify that ID as a string [here](https://github.com/sparkboard/sparkboard/blob/e16069445afa28b5245827f40ee842392d9f1bfc/src/org/sparkboard/slack/screens.clj#L135). All the stuff we've published in the home-tab can be thrown away as it's refreshed on every view.
- Actions are often very small functions and it would be annoying to have to register them all separately from where they are defined. Instead, you can supply a map containing a single entry, with the action-id keyword and function:
    ```clj
    [:button
     {:action-id
      {:open-modal (fn [{:as context :keys [state]}] (swap! state assoc :clicked true))}}]
    ```
    See how we swapped the `state` atom passed to the callback. This will trigger our view to re-render.
- Important: *action functions will be lifted out of the view function* and so they cannot access the surrounding scope - they can only read the arguments they are passed (and, of course, anything else in their Clojure namespace).
- Action callbacks are passed their current value under the `:value` key. See the examples (and play with the interactive example) to see what these values look like for different kinds of elements.
- Action callbacks also receive the containing `:block-id`. This can be a way to communicate state in chat messages, which _do not support local state_ like modals and the home-tab do. [example](https://github.com/sparkboard/sparkboard/blob/e16069445afa28b5245827f40ee842392d9f1bfc/src/org/sparkboard/slack/screens.clj#L132)
- Any elements wrapped in an `:input` field only expose their values when a modal is submitted, and *lose* their values if the view is re-rendered. This means that we need to basically choose 1 mode of entry (read all values in `on-submit` vs. store-in-local-state) for each (entire) modal. I've contacted the Slack API team about this, hopefully they will fix this as it's quite a limitation. The local-state method is the only way to create dynamic views that change as you interact with them, but doesn't support text fields at all. [example](https://github.com/sparkboard/sparkboard/blob/e16069445afa28b5245827f40ee842392d9f1bfc/src/org/sparkboard/slack/view_examples.clj#L163)

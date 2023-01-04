Sparkboard
(WIP)
----

Contents:

- A [Google Pub/Sub](https://cloud.google.com/pubsub/docs/overview) subscription consumer in `org.sparkboard/migration/pubsub`. See [sparkboard/mongo-oplog-pubsub](https://github.com/sparkboard/mongodb-oplog-pubsub) for source code of the mongodb->Pub/Sub process that is feeding data into this subscription.
- Localization definitions in `org.sparkboard.localization/locales.edn` (consumed from legacy repos)

----

## Develop locally

TODO describe `bb dev` process

1. yarn install
1. Start a JVM process with `yarn shadow-cljs`
1. Open a REPL with your favorite editor, reading the port from `.nrepl-port`
1. `(shadow/watch :web)` compiles/reloads the client (not much there yet)
1. `org.sparkboard.server.server/-main` starts the server

## Receive Slack events with your local server

Slack uses webhooks to tell us when events happen, so we need to expose our local server
to the internet.

1. Install http://ngrok.io/
1. `ngrok http 3000` and copy the https url ngrok prints.
1. Create/update a Slack app
    1. paste the above `https` url into the relevant fields in:
        * `Interactivity & Shortcuts`,
        * `OAuth & Permissions > Redirect URLs` (then click "Save URLs")
        * `Event Subscriptions`, then
            1. Subscribe to bot events:
                `app_home_opened`, `member_joined_channel`, `team_join`
            2. "Save Changes"
    1. `Manage Distribution`
        - check the box in `Remove Hard Coded Information`
        - click `Activate Public Distribution`
    1. App Home > Show Tabs
        - enable Home Tab, disable Messages Tab
1. Update `lambda/src/.local.config.edn` with your Slack app's config found under `Basic Information` > `App Credentials`
1. Navigate to `https://YOUR_NGROK_SUBDOMAIN.ngrok.io/slack/install-local` and pick a Slack workspace
to install your dev app to. If all goes well, you will be asked to grant permissions to your app,
and then redirected to your app's home tab in Slack.

## Deployment

- Pushing to `staging` branch deploys to staging server
- `bin/promote` promotes the current staging build to production

## Slack View API

, and a React-like component model for maintaining local state in [modals](https://api.slack.com/surfaces/modals) and
[home tab](https://api.slack.com/surfaces/tabs) views. See the `org.sparkboard.slack.screens` namespace for example usage.

## Hiccup syntax

We use *hiccup* to define [Block Kit](https://api.slack.com/block-kit) views. Here is a simple example:

```clj

(hiccup/blocks [:button {:url "http://..."} "Apple"])
;; =>
{:type "button"
 :text {:emoji true
        :type "plain_text"
        :text "Apple"}
 :url "http://..."}
```

We can see that:
- The `type` of the block moves to the first position in the hiccup vector, `[:blocks ...]`
- The "child" element, `"Apple"`, was placed under the `:text` key. These mappings of `:child`
and `:children` are configured in the `org.sparkboard.slack.hiccup` namespace.
- The `:text` value was wrapped in a `"plain_text"` block, as required by the Slack api.

The hiccup syntax is shorter and contains fewer redundancies than the raw `Block Kit` formats.

## Modal submission

If interactive blocks like buttons and checkboxes are inside an `:input` block,
their value is only transmitted when submitting a modal. Use a `:on-submit` callback for that.

```clj
(v/defview my-modal [context]
 [:modal {:on-submit (fn [{:as context :keys [input-values]}]
                       ;; input-values
                       ;; => {:invite-link "..."}
                       )}]
   [:input {:label "Link"}
    [:plain-text-input
     {:placeholder "Paste the invite link from Slack here..."
      ;; action-id determines the "name" this value is stored under
      :action-id :invite-link
      ;; :set-value controls the value of this input
      ;; (there is no single way to do this with Block Kit, we take care of handling the inconsistencies for you.)
      :set-value (or (:slack/invite-link context) "")}]])
```

## Action IDs

The `:action-id` key is special because Slack requires these IDs to be globally unique.
Therefore, we expand `:action-id` keywords to include their parent view's name, eg. in
this example `:action-id` becomes `:my-modal/action-id`. When inside a component's render
function or callbacks, you can always use short (local) names.

## Action callbacks

Most interactive blocks (but not text blocks) can exist outside of an `:input` block and will
fire a callback when the user interacts with them. Actions cannot be anonymous, we always need
an ID (see above). The syntax for defining an action "inline" is to supply a map of `{<id>, <callback-fn>}`
for the `:block-id` field. The callback will receive a context map as its sole argument, which will
contain `:state` - the view's local state atom - and `:value`, the current value for this particular item.

If an action mutates the `:state` atom that it is passed, this will trigger the view to re-render with
this updated state value.

Sparkboard
(WIP)
----

Contents:

- A [Google Pub/Sub](https://cloud.google.com/pubsub/docs/overview) subscription consumer in `org.sparkboard/migration/pubsub`. See [sparkboard/mongo-oplog-pubsub](https://github.com/sparkboard/mongodb-oplog-pubsub) for source code of the mongodb->Pub/Sub process that is feeding data into this subscription.
- Localization definitions in `org.sparkboard.localization/locales.edn` (consumed from legacy repos)

----

## Receive Slack events with your local server

Slack uses webhooks to tell us when events happen, so we need to expose our local server
to the internet.

1. Install http://ngrok.io/
1. `ngrok http 3000 and copy the https url ngrok prints.
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
1. Update `lambda/src/.local.config.edn` with your Slack app's config found under `Basic Information` > `App Credentials`
1. Navigate to `https://YOUR_NGROK_SUBDOMAIN.ngrok.io/slack/install-local` and pick a Slack workspace
to install your dev app to. If all goes well, you will be asked to grant permissions to your app,
and then redirected to your app's home tab in Slack.

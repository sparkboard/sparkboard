Sparkboard lambdas
----

## Dev

This can all be done from this repo's root directory.

## Environment vars

Create `lambda/src/.local.config.edn` - get the initial contents from Matt.
(You will update this with your own Slack app config if developing locally.)
This file is in `.gitignore` -- make sure it is named correctly and never
checked in to source control!

## Shadow-cljs

Start a shadow-cljs JVM process:
```
shadow-cljs server
```

Enter the repl:

### CIDER

1. in terminal, `shadow-cljs server`
3. from an emacs ClojureScript buffer, `M-x cider-connect`; select `localhost` and enter the nrepl port reported from (1) when prompted
3. in the newly created CIDER REPL, `(shadow/watch :lambda)`
5. in another terminal window, `node lambda/target/main.js`
6. back in the CIDER REPL, `(shadow/repl :lambda)`
9. in one more terminal, expose my localhost to the outside world with `ngrok http 3000 -subdomain <consistent subdomain matching Slack config>`


### Cursive

Start a remote nrepl session with the port in `.nrepl-port`

----

Start watching the build:

```
# in your repl
(shadow/watch :lambda)
```
(also possible through the shadow UI at http://localhost:9630)

Start the local node server:

```
node lambda/target/main.js
```

When you start this process you should see `:started-server 3000` in the console, repeated
whenever you save a change, unless you are using SAM (see below).

## Receive Slack events with your local server

Slack uses webhooks to tell us when events happen, so we need to expose our local server
to the internet.

1. Install http://ngrok.io/
1. `ngrok http 3000 and copy the https url ngrok prints.
1. Create/update a Slack app and paste the above `https` url
    into the relevant fields in "Interactivity & Shortcuts", "Event Subscriptions", and
    "OAuth & Permissions > Redirect URLs" in the Slack settings for your app.
1. Enable "App Distribution" for your app.
1. Update `lambda/src/.local.config.edn` with your Slack app's config.
1. Navigate to `https://YOUR_NGROK_SUBDOMAIN.ngrok.io/slack/install-local` and pick a Slack workspace
to install your dev app to. If all goes well, you will be asked to grant permissions to your app,
and then redirected to your app's home tab in Slack.

## Release

```
bin/release <dev/staging/prod/other-tag>
```

Deployed lambda roots are as follows:

Dev: https://bycqw7pf9k.execute-api.us-east-1.amazonaws.com/Prod
Staging: https://xztdh6w28b.execute-api.us-east-1.amazonaws.com/Prod
Prod: https://sstwt0eqqb.execute-api.us-east-1.amazonaws.com/Prod

(Yes, they all end in 'Prod', this is a weird SAM thing.)

### Local testing: SAM

Instead of the local dev server, you can simulate a lambda environment using [SAM](https://docs.aws.amazon.com/serverless-application-model/latest/developerguide/what-is-sam.html).
This will run a mock lambda runtime on your machine. It is much slower than using the express-server
approach and many requests will not return fast enough to satisfy Slack. However, it will be a more
realistic approximation of the prod environment.

First, set `server.dev-server/ENABLED?` to false to prevent the dev server from running.

Then, install AWS SAM:

```
brew tap aws/tap
brew install aws-sam-cli
```

Set up [AWS credentials](https://docs.aws.amazon.com/serverless-application-model/latest/developerguide/serverless-getting-started-set-up-credentials.html)

Start the lambda:

```
sam local start-api
```

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

    cider-jack-in-cljs
    shadow
    :app
    y

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

## Serving Slack requests locally

1. Install http://ngrok.io/
2. `ngrok http 3000 and copy the https url ngrok prints.
3. Create/update a Slack app with the above https url
    entered under "Interactivity & Shortcuts", "Event Subscriptions", and
    "OAuth & Permissions > Redirect URLs".
4. Update `lambda/src/.local.config.edn` with your Slack app's config.
4. Navigate to `YOUR_NGROK_URL/slack/install-local` and pick a Slack workspace
to install your dev app to.

## Release

```
bin/release <dev/staging/prod/other-tag>
```

### URLs

Dev: https://bycqw7pf9k.execute-api.us-east-1.amazonaws.com/Prod
Staging: https://xztdh6w28b.execute-api.us-east-1.amazonaws.com/Prod
Prod: https://sstwt0eqqb.execute-api.us-east-1.amazonaws.com/Prod

### Local testing: SAM

This will run a mock lambda runtime on your machine. It is much slower than using the express-server
approach and many requests will not return fast enough to satisfy Slack. However, it will be a more
realistic approximation of the prod environment. (Unsure of the usefulness of that.)

First, set `server.dev-server/ENABLED?` to false to stop the dev server.

#### Install AWS SAM:

```
brew tap aws/tap
brew install aws-sam-cli
```

Set up [AWS credentials](https://docs.aws.amazon.com/serverless-application-model/latest/developerguide/serverless-getting-started-set-up-credentials.html)

#### Get started

start the lambda:
```
sam local start-api
```
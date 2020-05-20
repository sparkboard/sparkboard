AWS Lambda with node.js + shadow-cljs
----

### URLs

Dev: https://bycqw7pf9k.execute-api.us-east-1.amazonaws.com/Prod
Staging: https://xztdh6w28b.execute-api.us-east-1.amazonaws.com/Prod
Prod:

### CIDER

    cider-jack-in-cljs
    shadow
    :app
    y

In terminal, in directory target/
   
    node main.js


## Testing locally

### Install ngrok

https://ngrok.com/download

run `ngrok http 3000 -subdomain=A_SUBDOMAIN` to expose port 3000 to the world.
ngrok will show you a URL, which you can paste into the Slack API settings pages for your app.

#### Slack app settings

You should create a new Slack app for local testing, and add it to a Slack workspace for testing purposes.
Then add a `:slack` entry in `.local.config.edn` as follows:

```
{...
 :slack
 {:app-id "XX"
  :client-id "XX"
  :client-secret "XX"
  :signing-secret "XX"
  :verification-token "XX"
  :bot-user-oauth-token "XX"}}
 ```

### Local testing via simple express-server

run `server.main/dev-start` from within a connected node repl. Depending on how you start
your repl, it will live-reload (if you run `shadow/watch :app` and then `node target/main.js`)
or only update via REPL eval (if you run `(shadow/node-repl)` after connecting via nrepl)

### Local testing: SAM

This will run a mock lambda runtime on your machine. It is much slower than using the express-server
approach and many requests will not return fast enough to satisfy Slack. However, it will be a more
realistic approximation of the prod environment. (Unsure of the usefulness of that.)

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

start ngrok:
```
ngrok http 3000
```

Go to api.slack.com and update the URLs in Features > `Interactivity & Shortcuts` & `Event Subscriptions`
----

Below is original instructions for quickstart Node.js shadow-cljs project.


### Develop

Watch compile with with hot reloading:

```bash
yarn
yarn shadow-cljs watch app
```

Start program:

```bash
node target/main.js
```

### REPL

Start a REPL connected to current running program, `app` for the `:build-id`:

```bash
yarn shadow-cljs cljs-repl app
```

### Build

```bash
shadow-cljs release app
```

Compiles to `target/main.js`.

You may find more configurations on http://doc.shadow-cljs.org/ .

### Steps

* add `shadow-cljs.edn` to config compilation
* compile ClojureScript
* run `node target/main.js` to start app and connect reload server

### License

MIT

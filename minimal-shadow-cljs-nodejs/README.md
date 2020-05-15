AWS Lambda with node.js + shadow-cljs
----

### CIDER

    cider-jack-in-cljs
    shadow
    :app
    y

In terminal, in directory target/
   
    node main.js


### Local testing

#### Install AWS SAM:

```
brew tap aws/tap
brew install aws-sam-cli
```

Set up [AWS credentials](https://docs.aws.amazon.com/serverless-application-model/latest/developerguide/serverless-getting-started-set-up-credentials.html)

From the root directory, run `sam local start-api`

#### Install ngrok

https://ngrok.com/download

After SAM is serving your lambda locally, run `ngrok http 3000` to expose it to the world.
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

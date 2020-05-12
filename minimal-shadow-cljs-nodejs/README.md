AWS Lambda with node.js + shadow-cljs
----

### CIDER

    cider-jack-in-cljs
    shadow
    :app
    y

In terminal, in directory target/
   
    node main.js




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

Sparkboard v2
----

To run locally:
```
bb dev
```
[localhost:3000](http://localhost:3000)

Configuration files are expected in `src/.{env}.config.edn` where `env` is one of `local`, `staging`, `prod`.

## Develop locally

1. `yarn install`
1. `bb dev`
1. Open a REPL with your favorite editor, reading the port from `.nrepl-port`

## Deployment

- Pushing to `staging` branch deploys to staging server
- `bin/promote` promotes the current staging build to production

## Architecture and Approach

Core libraries used:
- [re-db](https://github.com/mhuebert/re-db), an end-to-end reactive library which streams data from a Datalevin database to a React-driven frontend.
- [yawn](https://github.com/mhuebert/yawn), a React wrapper supporting hiccup and live reloading.


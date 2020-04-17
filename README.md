Sparkboard
(WIP)
----

Contents:

- A [juxt](https://github.com/juxt/crux) node in `org.sparkboard.server/crux`
- A [Google Pub/Sub](https://cloud.google.com/pubsub/docs/overview) subscription consumer in `org.sparkboard/migration/pubsub`. See [sparkboard/mongo-oplog-pubsub](https://github.com/sparkboard/mongodb-oplog-pubsub) for source code of the mongodb->Pub/Sub process that is feeding data into this subscription.
- Localization definitions in `org.sparkboard.localization/locales.edn` (consumed from legacy repos)

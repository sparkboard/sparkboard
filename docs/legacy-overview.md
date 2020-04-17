# Sparkboard

Primary entity types
- a **board** is used to represent a hackathon. It contains **members** and **projects**.
- an **organization** can create and manage boards.
- a **project** represents a group of people who work together at a hackathon. Projects can be created by any member, and any member can join any project.
- a **membership** represents the link between an **account** and a **board**.
- Boards and orgs are served from custom domains (`<subdomain>.sparkboard.com`), while projects are displayed in the context of their parent board or (ancestor) org.

![image](https://user-images.githubusercontent.com/165223/79548137-4091c980-8095-11ea-93f1-45dfe837537a.png)

Users, accounts, authentication
- Users sign in using [Firebase Auth](https://firebase.google.com/docs/auth), and can register using a Google account or email/password. Firebase manages this data (it can be exported, including hashed passwords).
    - The sign-in flow is managed by [firebaseui-web](https://github.com/firebase/firebaseui-web).
    - Users sign in via client-side javascript, then an "ID Token" is generated which is sent to Sparkboard's server and creates a secure session.
    - API requests are authenticated by passing these ID tokens in `Authorization` headers.

Board membership
- Board membership can be by invitation-only, or open to the public.
- A board member with the `admin` role can edit all content on the board, add/remove members, and promote other members to `admin`.

Org membership
- Administrators of an org have an 'admin' role. Members of boards within an organization do not have a role in the organization per se.

Profile & project fields
- Memberships & projects contain **fields** which are dynamically managed by admins (can be unique per-board). Membership fields are used to describe a user's interest & background as it relates to the particular event. Project fields describe what each team is working on and other data (eg. what city the project is in, for multi-city hackathons)
- Each field has a `type`, such as `image`, `video`, `text`, `select`

Member interaction
- Members can send private messages to each other (a `Thread` of two `participantIds` contains `Messages`)
- Members can post questions or comments on a project. Replies are 1 level deep. (a `Discussion` contains `Posts`, which contain `Comments`) Members can "watch" a project to receive a notification for new comments.

Notifications
- Members receive notifications for new private messages (`newMessage`), or new posts on "watched" projects (`newPost`), or new comments on watched posts (`newComment`)
- Members can choose a single global notification preference: `never`, `daily`, `periodic` (hourly), or `instant`.
- A `Notification` contains `notificationViewed` and `targetViewed` fields to keep track of whether a user has viewed the notification and the object it points to, respectively.

## Persistence

Boards, orgs, and their roles are in Firebase.

Projects/groups, discussions, threads, & notifications are in MongoDB.

See [schema](legacy-schema.md).

When a membership (`User`) or group (`Project`) is created/modified/deleted, an "invalidation" event is published to a Google Pub/Sub topic and a timestamp is written to Firebase. This enables cloud functions or browsers to respond to invalidation events by subscribing to the Pub/Sub or real-time database, respectively. Separately, an older code-path maintains websocket connections to active browser clients and pushes changes.

A search index of projects is maintained in [Algolia](https://algolia.com) to enable search across all of the projects in an organization or collection. The index is updated by a Firebase function subscribed to the `invalidations` Pub/Sub topic.


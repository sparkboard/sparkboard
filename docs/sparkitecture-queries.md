Overview of current and expected queries.

**Domain Lookup**

* For every request, we use the hostname of the request to look up the entity that is linked to the domain. Multiple kinds of entities can be associated with a custom domain: `#{:board, :org, :collection}`. 
* **Real-time:** no
* **Legacy: **domains are stored in Firebase, at path `/{entityKind}/{entityId}/domain`, with an index for reverse lookup maintained at `/domain/{domain}`

**Org, Board, and Collection Entities**

* For every request, we look up the **entity** associated with the domain, containing fields like `title`, `description`, etc (see [schema](https://github.com/sparkboard/sparkboard/blob/master/docs/legacy-schema.md))
* **Real-time**: yes, some of this data changes during an event and should update on user's screens immediately, eg. when we start a "community vote" period. 
* **Legacy: **in firebase, `/{entityKind}/{entityId}/`

**Org, Board, and Collection Children (Projects & Memberships)**

* **Relations:**
  * Organizations "own" boards, and (transitively) projects and members (one to many)
  * Boards "own" projects and members (one to many)
  * Collections "link to" a list of other entities (many to many)
* **Search:** users can search across all children (direct & transitive) of an org/board/collection. This is important, eg. for a new diabetes-related project to be able to find other diabetes-related projects that have been worked on at other events.
  * **Legacy:** A board's projects and members are downloaded in full to the client, where they are searched in-memory (this needs to stop, infeasible for large boards). Searching all of the projects for an organization is achieved via a separate index (managed by [Algolia](https://algolia.com)).
* **Real-time:** yes, participants often keep a Sparkboard window open during events, and expect to see new projects/members appear over time. Eg, during a "pitch night", a representative of each project will talk for 1-2 minutes. Participants browse and join projects and it is important that everyone can see who has joined which project without refreshing the page. OTOH, search queries do not need to be updated in real time.

**Field specifications**

* Projects and Memberships (users) contain a list of profile "fields". These fields are described by "field specs" owned by a parent entity (eg. the board they are a part of). 
  * A field spec describes its `type` (eg. text, checkbox, photo, video, link, link-list, etc), `label`, `hint`, `options` (for select drop-downs or tag fields) and so on. 
  * Displaying a project requires first fetching the field-spec for its parent, then using that metadata to correctly display the project's fields (the project contains only values for each field, not the associated metadata).

**Notifications**

* Notifications link to a `target` entity (eg. a membership, project, post, or comment). We track whether a notification has been viewed, and whether the target has been viewed (we do not need to continue to highlight the notification if the user navigated to its target independently).
* **Real-time:** yes, we show an up-to-date notifications indicator in the browser.
* **Legacy: **Notifications are stored in their own mongodb collection. The client watches an `invalidations` path in Firebase associated with the current user, which is updated with the current timestamp when a new notification has been created for that user, triggering the client to request its latest notifications.

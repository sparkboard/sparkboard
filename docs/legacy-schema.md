
### Boards
(firebase)
```js
type Board {
    parent: EntityId | Null // organization id
    allowPublicViewing: Boolean | Null // should projects be exposed to public
    createdAt: TimestampCreatedAt | Null
    communityVoteSingle: Boolean | Null // is community-vote currently active
    projectsRequireApproval: Boolean | Null // do new projects need approval by an admins
    publicWelcome: String | Null // welcome message displayed on public landing page
    userMessages: Boolean | Null // is private messaging enabled
    social: Map<String, Boolean> // enable `TWITTER`, `FACEBOOK`, & `QRCODE` links on projects
    description: String | Null // description of board
    descriptionLong: String | Null // description of board, replaces default view on landing page
    languageDefault: String | Null // default locale, `en`, `es`, `fr`
    languages: Language[] // deprecated
    localeSupport: String[] // ordered array of locales to show to user
    css: String | Null // custom css to be inserted into page
    domain: Subdomain
    groupFields: Field[] // list of group (project) fields
    groupLabel: Object | Null // how to refer to groups [singularForm, pluralForm] - default ["Project", "Projects"]
    groupNumbers: Boolean | Null // whether to show project numbers
    userFields: Field[] // list of user profile fields
    userLabel: Object | Null // how to refer to users (members) - ["Person", "People"]
    images: Map<String, String> // images uploaded via settings - `logo`, `logoLarge`, `background`, `subHeader`, `footer`
    tags:  Tag[] // list of tags to suggest for member profiles, {:color, :label}
    title: String | Null // board title
    headerJs: String | Null // deprecated (javascript to insert in header, unsafe)
    registrationOpen: Boolean | Null // allow public to register?
    registrationMessage: String | Null // message to display on new-member registration page a new member
    registrationLink: String | Null // replaces default "New Account" link href, for invitation-only flows where user will be sent an invite by email after signing up on a 3rd party form
    authMethods: Map<String, Boolean> // deprecated
    legacyGroupTags: Boolean | Null // deprecated
}
```

There are some private settings, not exposed to the public, at a different path:
```js
type BoardSettingsPrivate {
    registrationCode: String | Null
    webHooks: Map<HookId, SecureURL> // webhook urls for `newMember` and `updateMember`
}
```

### Orgs
(firebase)
```js
type Org {
    creator: String // id of firebaseAccount
    title: String
    description: String | Null
    domain: Subdomain
    boardTemplate: BoardKey | Null // board settings to use as template for new boards
    images: Map<String, String> // same as board.images
    allowPublicViewing: Boolean | Null
    showOrgTab: Boolean | Null // whether to show the org as a tab on child boards (default: false)
}
```

### Collections
(firebase) - recently added for the covid19.sparkboard.com page, to display a collection of items not all 'owned' by the same parent
```js
type Collection {
  title: String
  description: String | Null
  creator: String | Null
  domain: Subdomain
  images: Map<String, String>
  boards: Map<BoardIdEscaped, TrueBoolean>
}
```

### Org/Board roles
(firebase)
To see if a user has a given role, we check if data at the path `/roles/e-u-r/{entityId}/{userId}/{roleName}` is `true`.
```js
path /roles/e-u-r/{entityId}/{userId} is Map<Role, TrueBoolean> {
    read() {
        true
    }
    validate() {
        isUserId(userId) && (isBoardId(entityId) || isOrgId(entityId) || isCollectionId(entityId))
    }
    write() {
        isEntityAdmin(entityId, auth.uid)
    }
}
```

### Projects
(mongodb)

```coffee
ProjectSchema = new mongo.Schema {
  boardId: {type: String, index: true}
  title: String
  intro: String
  approved: {type: Boolean, default: false} # used when projectsRequireApproval
  deleted: {type: Boolean, default: false}
  members: [{}] # {user_id: '<id:string>', role: '<role>'}
  looking_for: [String] # List of plain-text requests for project
  owner: mongo.Schema.ObjectId
  ready: {type: Boolean, default: false} # corresponds to !"Looking for members?"
  active: {type: Boolean, default: true} # false after project is "archived" by admin
  number: String # when groupNumbers is true
  discussion: [{}] # {user_id: '<id:string>', text:<string>}
  votes: [{}]
  badges: [String] # displayed on project card, used to indicate award winners or categories
  tags: [String] # only partly/poorly implemented. displayed on project. should be fully superceded by filter-enabled fields.
  htmlClasses: String # custom css classes to be added to project
  demoVideo: String # may contain youtube video id, controlled by admins
  originalBoardId: String # deprecated
  field_description: String
  publicVoteCount: Number
  sticky: {type: Boolean, default: false} # sticky projects are for admins, show up with colored border at top of board
  lastModifiedBy: String
  links: [Schema.Types.Mixed]
}, {
  usePushEach: true
}
```
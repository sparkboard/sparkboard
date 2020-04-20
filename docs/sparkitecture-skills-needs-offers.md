**People** bring **skills & interests** (currently: a text field),

**Projects** post **needs** (currently a list of text fields, labeled as **Looking for...**) 

**\[People, Projects, Boards, Organizations\] **post **offers/resources** (currently: not implemented)

**Desired queries**

* Show **people** in **\[board, organization\]** with **skill/interest** related to **\[X\]**
* Show **offers/resources** available to a project in **\[board, organization\]**
  * = those posted by **sibling projects** and **parent board+organization**
* Show all **needs** in **\[board, organization\]** (matching **\[skill/interest\])**

## Discussion of existing "Looking for.." feature

Every project has a "Looking for..." field, which is a list of text strings describing what kind of help the project is looking for.:

![acae7e1b-b584-4176-9d06-e3a02137be1e.png](https://files.nuclino.com/files/c1d684ad-0dd2-4594-955b-51c0ee99df8d/acae7e1b-b584-4176-9d06-e3a02137be1e.png)

Similarly, every user profile asks participants to briefly describe their skills and interests:

![b0ed5567-4178-41ae-8cd3-e990ed2a3ef6.png](https://files.nuclino.com/files/82e48fa6-e1ac-4e28-86ab-1a8124ba7edc/b0ed5567-4178-41ae-8cd3-e990ed2a3ef6.png)

* What we like
  * it is simple, everybody knows how to use it
  * for small boards it is feasible to scan all projects and get a sense of "who needs what", and all members to see "who is coming and what can they do"
* What we don't like:
  * because it is unstructured, we can't filter by "projects looking for designers" if people use different words to describe similar needs
  * the downsides are minor for small groups, because the necessary coordination happens informally. As groups grow in size, it breaks down. (Eg: Hacking Health has had many thousands of participants, yet no way to easily find past participants who have specific expertise)
* Taxonomy possibilities
  * A very small/constrained taxonomy selected by the event organizer (this is how we do user tags - typically 4-7 tags, on the level of "developer", "designer", "health professional") -- probably insufficient
  * An existing, external/open taxonomy that we find ([Skills | LinkedIn Developer Network](https://developer.linkedin.com/docs/ref/v2/standardized-data/skills))
  * Topic modeling / auto-tagging based on content
  * many non-technical people use Sparkboard, we want an extremely simple experience (avoid clicking through deep hierarchies, or confronting user with intimidating choices)
* **Non-goal: **"automatic assigning of people to teams". Efforts to do this have failed because it interferes with the principle that people should work on what interests them -- and vote with their feet (by joining the project they think **\[a\]** is a good idea and **\[b\]** they can contribute to). 
* In early stages of an event, projects are not well-defined. Many domain experts don't know how to describe their needs in the language of the implementors, or know "who" exactly they need. Specifics are articulated later, by the first "technical" team members, who make the architectural decisions (what language/approach to take). So on the first pass, participants **browse** projects to find one that catches their attention. Not much filtering happens yet (other than "show me projects that still want team members").
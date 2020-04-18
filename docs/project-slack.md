# Project: Slack Integration

## Topic #1: Team Collaboration

*Background:*

* Slack is a popular tool that many people are already familiar with, and it has well-developed collaboration features for real-time chat, file-sharing, audio/video calls, integration with many other apps.
* Large communities on Slack can feel sprawling, noisy and disorganized. Some participants who are new to Slack complain that it is confusing.
* Slack is not a good place to get a high-level overview of "who is working on what" - that would require joining many channels and reading message histories.
* Slack is not a good place to record updates that should persist across time, because the free plan deletes history beyond 10,000 messages (& paid plans are prohibitively expensive for many communities).

*Approach*

Use **Sparkboard** for a clean & organized place where we show **who** is working on **what**, and \[future\] durably record progress/updates over time. Use **Slack** for ad-hoc, real-time communication (messy/noisy/transient).

**Feature: Linked Channels**

* Create a channel for each project on Sparkboard
  * Questions
    * *is this fully automatic, or does the project leader click a button to create a linked channel? *
  * Events
    * when a new linked channel is created:
      * Add existing members of the project to the channel
      * Pin a message to the top of the channel, linking back to the Sparkboard project
      * Store the channel ID with the project
    * when a new member joins the Slack workspace
      * Look up projects they are a member of, add them to those channels
    * when a new member joins a project
      * Add them to the linked channel
    * project page
      * \[members-only\] show a link/button to the channel in Slack

**Feature: Users are invited to the Slack workspace**

* When a user joins Sparkboard, they should be invited to the linked Slack workspace.
* The only reliable API for this is an "Invitation Link" which must be manually created by an admin. Each link is limited to 2,000 participants. Potentially need to keep track of invitations and remind organizers to create+enter a new invitation link when old ones are "out".
* Users should be encouraged to use the same email in Slack as they use on Sparkboard -- *or* some other means of linking the accounts.

## Topic #2: Team Facilitation & Progress Tracking

*Background*

* Hackathons are by nature messy and chaotic in the large (many people mixing, coming up with new ideas, rapidly collaborating) but involve careful, dedicated thought and effort in the small (individual team members trying very hard to solve difficult/complicated problems in their own way).
* From this chaos, we want to surface (in a simple way) how teams are **making progress** and **hitting obstacles** along the way. This serves a couple of purposes:
  * Build momentum/motivation within the community, by sharing achievements
  * Attract additional resources (internal/external)...
    * when people see that progress being made, they want to see it continue & grow (invest further in promising teams/ideas, join the exciting projects)
    * when people see teams hit obstacles, they can offer to help (join the team, find answers/resources)
  * Organizers want to measure their own success at creating an environment suitable for productive work
* Help can come from within the community of participants, as well as organizers/facilitators who have time to dedicate to following & supporting teams.

**Feature: "prompted updates" from teams**

* Teams are prompted to give updates, to communicate things such as
  * What are you currently working on?
  * Is there anything somebody else could do to help you right now?
  * Have you made any recent achievements?
  * What problems/questions are keeping you up at night?
  * What is the status of your project? \[ongoing / finished / blocked\]
* Organizers could design and customize the flow/structure of prompts, we should not try to control or design the content (though we can start with some recommandations/templates)
* It should be possible for these updates to be posted back to Sparkboard (to be shared with the community on the project page) or privately to organizers
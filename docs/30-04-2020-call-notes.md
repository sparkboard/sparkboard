# Sparkboard Call Notes
_Matt Huebert, Thomas Maillart, Julia Dallest_

Discussion of Slack feature involving sending prompts to all teams & persistence/display of responses.

### Workflow
- Organizer initiates a prompt to be sent to all teams
    - Sender specifies:
          - what channel to post responses to
          - should responses appear on the project's timeline
          - the prompt itself (eg. what question to ask, fields to be filled in)
    - Results are posted to ^the channel and persisted to sparkboard-db
    - Default expectation is that results are shared with the community
      - \[maybe\] user can check a box, "Send privately to $user/channel" when admin has configured "Allow private responses to $user/channel"
    - **Example**: admin sends "pizza order" prompt to all teams, updates are posted to `#logistics` channel


### Permissions
- Only admins can send prompts to teams
    - Admin status is read from sparkboard-db

### VERSION 1
  - support for project:channel linking, & sending of simple prompts to teams with responses reported to designated channel
  - leverage Slack UI as much as possible to avoid adding UI surface-area to Sparkboard (for now)
  - Thomas will get back to use by mid/late next-week with the specific prompts they will plan to send during the hackathon
  - Next hackathon will occur in 6 weeks, mid-June

## Later

### User-initiated interactions

Preconfigured threads that teams can always post to, via a slack command or [shortcut](https://api.slack.com/interactivity/shortcuts#).

Examples: `/team-update`, `/request-for-help`, `/question`.

Results are posted to the project timeline (ie. in sparkboard-db)

### Scheduled prompts

An organiser can create a list of prompts to be sent in the future.
- UI is admin-only, in Sparkboard.
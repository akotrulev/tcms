# TCMS — Feature Inventory

A self-hosted test case management system serving **multiple client companies from one instance**.
It manages the test *artefacts* — cases, suites, plans, runs, results, documentation — and ingests
results from your CI. It is not a test runner and not a defect tracker; it links out to both.

Written for a reader deciding what the product does. Implementation detail is confined to
[Platform and Architecture](#platform-and-architecture) at the end.

*Implemented sections verified against the code on 2026-08-18.*

---

## Signing In

- Sign in with an email and password
- Join a company by redeeming an emailed invitation link, setting your password as you accept
- Sign in through your organisation's identity provider (OpenID Connect), with the right provider
  chosen automatically from your email domain
- Sign in against a corporate LDAP directory, with repeated failed attempts throttled
- Sign in with a GitHub, GitLab or Google account, and link or unlink those accounts later from
  your own security page
- Company Admins configure which providers their company accepts
- Sessions survive a token expiring mid-action — the request retries transparently rather than
  bouncing you to the login screen
- Signing out in one browser tab signs out the others

## Who Can Do What

- Nine built-in roles: **Viewer**, **Automation Service**, **Test Engineer**, **Test Lead** and
  **Project Manager** on a project; **Account Manager**, **Finance** and **Company Admin** across a
  company; **Instance Admin** across the instance
- Roles are fixed and cannot be edited or cloned — a client needing something between two tiers
  gets the higher one
- 89 distinct permissions, granted per project, per company, or instance-wide
- Grant a role to a person on a project, or to a whole team at once — everyone in the team inherits it
- Test Lead adds exactly ten things over Test Engineer: the three approvals, permission to manage
  the project and its integrations, the project audit trail, and deleting issues, configuring the
  issue workflow and importing from another tracker
- Nobody can approve their own work, by default
- Buttons you lack permission for are **visible but disabled**, so the product never hides that a
  capability exists

## Projects

- Create projects, edit them, and archive ones that are finished
- Each project carries its own execution-status vocabulary and the order it reads in — the app
  never assumes "Passed" and "Failed" — but the six are seeded at provisioning and
  ~~naming your own~~ is not built: `GET /projects/{projectId}/statuses` is the only verb in
  the contract, and there is no screen. Listed under Phase 1 below.
- Define your own classification dimensions and values — Priority, Component, whatever the client
  calls them — including nested values
- Turn review and approval gating on or off per project, and choose what it applies to
- Bind the project to an issue tracker — a Jira Cloud, Jira DC or Azure DevOps site and the
  project key or team project inside it. The binding holds no credential; the settings page
  also lists which team members have connected one of their own
- Manage who is on the project and what role they hold
- An **overview per project**, which is where opening a project lands: delivery and quality
  together — the epics with their completion and the basis each percentage was computed on, the
  story-level work sitting under no epic, and test health, with the repository figures underneath
  (what needs attention, how cases break down by lifecycle and classification, how execution is
  trending). ~~A dashboard per project showing the repository figures alone.~~ → superseded
  (011 M1), which put the tracker's half above them rather than replacing them
- **A section the product has no data for explains itself; it does not render empty.** The overview's
  release panel says what a release *is* and what it buys, because a release is a thing the project
  will have and has none yet. A budget panel is not rendered at all — that module is not installed,
  and an empty card for it would be an advertisement rather than an explanation
- ~~A project belonging to a company with **no clients** is the same screen with no client chip, no
  client column and no prompt to attach one. Nothing nags a company that has none~~ →
  **superseded 2026-09-05 (Mc): that is now a description of what the product does rather than a
  promise about what it will.** Every client surface is conditional on the company having at least
  one client, and the absences are asserted as tests. See **Clients** below
- Say which client a project is for, on its General settings — **and "None (internal)" is a value,
  not a gap.** The field only exists once the company has recorded a client

## Clients

- Record a client — an organisation your company delivers software for — and **a name is the only
  thing it needs.** Location, industry and notes can come later, or never
- Add contacts for a client: name, role, email, phone. **A contact is a record of a person to talk
  to and never a login** — nobody at a client can sign in, see a board, or be granted a permission,
  and adding one creates no account of any kind
- Attach a project to a client from the project's own settings, change it, or detach it. One client
  has many projects; a project has at most one client — or none
- Open a client to see its projects and its contacts in one place
- Filter the projects list by client, with the filter held in the URL so the narrowed list can be
  sent to whoever asked
- Archive a client you no longer work with. **Deleting one that projects still name is refused**,
  and the refusal says to archive it instead — leaving live work with no customer is the thing that
  cannot happen
- **A company with no clients is a first-class, complete state, not an unfilled one.** If you build
  your own products you may never record one, and nothing in the product nags, blocks or degrades:
  no Client column, no Client filter, no Client field, no "attach a client" prompt, and no project
  marked as missing one. The Clients screen stays reachable so the first one can still be created —
  that is the only trace a client-less company ever sees
- Clients are visible to Company Admins, Account Managers and Finance. **Finance can read a client
  and not edit one**: an invoice has to name a customer, and maintaining the record is the Account
  Manager's job
- What a client's work is **worth** is a separate module — see [Billing](#billing) — and an Account
  Manager still knows the client and the hours and never a rate. ~~It is not here.~~ →
  **superseded 2026-09-05 (plan 019, M8):** the client screen now carries the hours and the
  invoices raised against them, and **gains a value column only for a reader Finance's permission
  covers.** The absence of that column is the server's answer, not the browser's guess

## Writing Test Cases

- Write cases with a title, description, preconditions, postconditions, and numbered steps pairing
  an action with its expected result
- Or write the case in **Gherkin** instead — the editor seeds a `Feature:` scaffold and the detail
  view renders the keywords
- Every case gets a readable key (TC-1, TC-2 …) that is unique across the whole company
- Set priority, tags, classification values, automation status and the automation key that binds
  the case to its automated test
- Link a case out to an external system — a spec, a ticket, a source file at a specific commit
- Attach files to a case
- Get step suggestions as you type, drawn from steps already written elsewhere in the project
- Reuse preconditions and postconditions from a project-wide **condition library**, and let cases
  inherit the conditions their suite imposes
- Two people editing the same case cannot silently overwrite each other
- Your unsaved work is buffered per person per case, and offered back when you return

### Keeping cases healthy

The product continuously flags cases needing attention, and you can filter the repository by any of
these signals:

- Its automation has stopped reporting results
- It runs in CI but is not declared as automated
- The specification it links to has changed since it was written
- Its approval has gone stale
- The conditions it depends on have drifted
- It has never been executed
- It has not been executed in a long time
- It is failing repeatedly
- It belongs to no suite
- It links to no documentation
- It is incomplete
- Separately: cases can be marked **flaky** from their result history, and **quarantined** by hand

### Reviewing and versioning

- Submit a case for review, approve it, unapprove it, or request changes, with a named reviewer
- Comment on cases, suites and plans, and resolve comment threads
- Every edit is versioned — browse the full timeline and see what changed between any two versions
- Cases move through a lifecycle: Draft → Active → Approved → Deprecated → Archived

### Working in bulk

- Filter the repository by suite, tag, lifecycle status, health signal, classification, or free text
- Change status, assign classifications, apply tags, or move cases between suites — many at once
- Clone a case, or delete one

### AI-proposed cases

- An agent can file a proposed test case for a person to review
- Proposals are listed, reviewed, and then accepted into the repository or rejected — nothing an
  agent writes lands unreviewed

## Organising Cases

**Suites** — a suite is a *named collection*, not a folder. Its membership comes from three places
at once: cases filed into it, cases added to it by hand, and cases matching its tags.

- Create, rename and delete suites
- Assign preconditions and postconditions that every case in the suite inherits
- Suites are versioned as their composition changes, so you can see when a suite's contents shifted
- Suites move through their own lifecycle and can be submitted for review and approved

**Tags** — create reusable tags per project, apply them to cases, get suggestions as you type, and
use them to drive suite membership.

## Test Plans

- Create plans, edit them, clone them, and archive them
- Break a plan into sections, each with its own scope and prose
- Pull cases into the plan by naming suites, individual cases, or tags
- Submit a plan for review and approve it
- Generate the plan as a specification document, linked into the documentation tree
- A statistics view showing execution rollups and progress against the plan
- Plans move through Draft → Active → Closed → Archived

## Running Tests

- Create a run scoped by suites, cases and tags — the scope you asked for is remembered, so you can
  see later what the run was *meant* to cover
- Record a result per case using your project's own status vocabulary, with per-step outcomes
- Attach evidence files to a result, and remove them
- Link a failed result out to a defect in your tracker, or file a new one from the execution
  screen with the case, the failed step and what was recorded composed into the body
- Filing and issue search happen **as the caller, with their own credential**, so the control
  is disabled — with the reason, before the click — for anyone who has connected no tracker,
  however many permissions they hold. Connections live on the person's own account page
- Assign executions to people
- Clone a run to repeat it
- Close a run when it is done
- Runs carry release version, build number, commit SHA and branch, so a run is traceable back to
  what was built
- Runs move through Not Started → In Progress → Completed → Aborted

### Seeing the work

- A run detail view with live pass/fail/blocked counts and progress
- A **What Failed** panel putting the run's failures and their steps on one screen
- ~~A **cross-project execution board** with drag-and-drop~~ → **superseded (011 M1).** The project
  board was never cross-project — it is one project's, and it composes that project's **issues**
  with the work the repository already knows about: executions assigned to you, reviews asked of
  you, cases the health computation flagged, and quarantined tests. The old line is left visible
  because a reader who finds `GET /work-items` in the git history should know it became
  `GET /board`, one operation still serving all three boards
  - ~~one operation still serving all three boards~~ → **still true, and now one of two kinds of
    board (012 M2).** The paragraph above describes the **composite** board — four kinds of card,
    columns assembled from whatever cards came back, and no configuration anywhere. Beside it there
    is now a **project board**, described under *Boards and the backlog* below, which shows issues
    only and whose columns are rows a person defined. They are different screens over different
    endpoints and this document does not use the bare phrase "team board" for either, because
    `settings-boards.html` also ships a board *called* Team board that is one of the second kind
- **My Work** — everything assigned to you, across every project, as **two tables over one read**:
  your issues in one, your executions and reviews in the other, beside a private note and personal
  due date on any card that no teammate sees. ~~A kanban with `mine=true` pre-applied.~~ →
  superseded (011 M1): two projects name their statuses separately, so columns across them were a
  grouping the data cannot support
- **Team board** — everything assigned to a team, across every project. Bounded by the *caller's*
  project visibility, never the team's, so two people asking for one team's board legitimately see
  different card sets — which is why nothing built on it presents a team total
- Configurable handling of retries versus genuinely distinct runs when results are collapsed

## Boards and the Backlog

**A board is a view, not a copy.** It owns no issue: it is a grouping and a mapping from the
project's statuses onto columns, so a project can carry several boards over the same work and
deleting one loses nothing. That single property is what the rest of this section is a consequence
of, and it is written on the settings screen itself because it is the answer to the only word on
that screen anybody hesitates over.

### Three boards over one workflow

A new project is seeded with **eleven statuses, twenty-two legal moves and three boards** — a PM
board, a Dev board and a QA board — over that one workflow. They are not three workflows and not
three copies of the work: each is a different team's fold of the same statuses, so a card moved on
one is in the matching column on the others the instant it lands, because the move changes the
*issue's* status.

- **The hand-offs are shared columns.** `Ready to develop` is the Dev board's first column and the
  PM board's last-but-one; `Ready for test` is the QA board's door and the Dev board's exit. A
  story dragged into `Ready for test` on the Dev board is on the QA board with no second action —
  nobody re-files anything, because there is nothing to re-file
- **`Blocked` is on every board**, and a blocked card remembers where it came from. `Block…` in the
  card menu records the status it left; the card face reads `⛔ from In test`, so a blocked card
  sitting on somebody else's board says whose problem it was; `Unblock` returns it to that status
  rather than to the first column
- **`Done` is the PM board's and nothing else's**, collapsed to a count strip that is still a drop
  target. *Tested is not shipped* — `Ready to deploy` is the tester's last word, and the deploy
  confirmation is the product owner's drag
- **Epics move like stories.** An epic's *status* is stored and is a card in a column of its own;
  its *completion* stays derived from its children. When every counted story under an epic is done
  and the epic is not, the epic card says so — a nudge, not an automatic transition: "every story
  is finished" and "this body of work is finished" are different claims, and nothing ships without
  a person's drag
- **A lane is an epic**, drawn as one band with the epic's own card in it, one sub-bar row per
  story, and the story's tasks as the cards in that row. A card whose descendants this board does
  not show says how many, and on which board they are
- **The PM board's card face carries readiness signals** — acceptance criteria present, estimate
  set, epic set, task count — from fields that already exist. A story in `Refining` with all of
  them is what "ready" looks like, and the card says which are missing
- **The QA board's card face carries the linked cases** — `4/6 passing`, `1 blocked`, and
  `Run linked cases` opening the issue where those cases and their latest results are. A card whose
  cases have never been executed reads **`0/6 run`**, never `0/6 passing`; a card with none reads
  `no cases linked`; and a card the server has not yet computed a summary for shows nothing at all,
  because a number nobody computed is not a zero

### Boards in general

- **Several boards per project, each a different fold of the same statuses.** A per-status board for
  the team and a three-column board for a stakeholder are two rows in the same table over one
  workflow
- ~~**Every status is claimed by exactly one column, on every board** — refused when a status would
  be claimed twice or left unclaimed.~~ → **superseded (021 R7).** A board now claims a **subset**;
  the project's boards *together* claim every status. What the old rule protected — a card in a
  status no board shows — is kept as a **reported warning** rather than a refusal: Settings → Boards
  draws the status × board matrix, names any status no board claims, and the half of the rule that
  is a real invariant (a status claimed *once per board*) is still a unique index. The old rule was
  what forced one board to show the entire lifecycle, which is the thing three boards exist to stop
- **A column claiming more than one status has a default**, and a card dropped into it takes that
  one. A coarser board never invents a status the workflow does not have
- **Swimlanes by epic, assignee, label or priority**, collapsible, with a completion roll-up on the
  lane header. The epic/story/task row structure above is specified for epic lanes; the other three
  keep flat cards
- **WIP limits report a breach, they do not refuse the drop.** A limit that blocked work is one
  people route around by not using the board, so the column header carries the count and the move
  still lands, saying so
- **Every drag has a keyboard twin.** Cards carry a `⋮ Move to…` listing every column, with the
  illegal ones disabled and the reason on them; backlog rows carry `↑ ↓`. WCAG 2.2 *Dragging
  Movements*, and the standing rule that dragging is never the only way
- **A ranked backlog** — one project-wide order over every story-level issue that is not done,
  held as a fractional rank, so inserting a row between two others rewrites no other row. The board
  says where a story *is*; the backlog says in what order the ready ones get pulled
- **One filter, across every view.** Label, assignee, epic, level, priority and unparented live in
  the URL, so a filter set on the board is still applied after switching to the backlog or the
  table — and every view shows a chip for every filter its own endpoint applies, whether or not that
  view offers a picker for it
- **Labels** — the informal, fast-moving axis, many per card, ten colours, and deliberately not the
  classification vocabulary, which is controlled and lives on its own screen

Not yet, and named rather than implied: the backlog does not group by release and has no *Move to
release* action; there is no pull-request badge on a card. ~~And none of this has been operated in a
browser.~~ → still largely true and still deliberately listed — see the module plan's
built-but-unverified list.

## Navigation

The product is reached through **three zones** in one left rail: what is yours, the project you are
in, and the company. Everything below is what 021 changed about finding things, as opposed to what
the product does.

- **The rail is flat inside a zone and ordered by how often a screen is opened.** The project zone
  leads with **Work** — the boards and the backlog, where the day starts and ends — and puts
  **Overview** seventh, because it is the project's dashboard: read at stand-up rather than worked
  in. There are no subject headings inside a zone
- **One entry per screen, at most twenty rows.** Five screens that were rail rows are now **tabs**
  on the screen they extend (Suites under Test cases, Proposals under Test cases, CI results under
  Test runs, and so on), and eleven settings rows are now **cards on one Settings landing** with a
  subnav — so the rail names places, not every page
- **The project switcher is the Project zone's header**, not a control in the top bar. Collapsed to
  64px the zone header becomes the project's key chip and still opens the switcher
- **Breadcrumbs are the shell's**, built from the route rather than passed up by each page, so the
  trail cannot disagree with the rail — and a page whose read failed still has its way back up
- **`⌘K` (or `/` outside a text field) opens a palette** over **issues, cases, docs, places and
  projects**, plus a key jump: type `ACME-118` and go to the issue, `TC-118` and go to the case.
  Issues and cases are searched across every project you can read; **doc pages are searched inside
  the project you are in**, and the palette says so rather than quietly returning nothing. Places
  are the rail, the tabs, the settings sections and the user menu — indexed by name, and filtered by
  exactly the permissions the rail filters by, so the palette cannot offer a screen the rail hides
- **The user menu is the person's own things** — your account, your security and identities, your
  API tokens, your notifications, **your timesheets** (a timesheet is one person's fortnight, not a
  place in the product), and `Your access`, a page that explains which entries are hidden from you
  and which permission would show them
- **Badges only where a read already exists**, and every one is an *awaiting-you* count rather than a
  total: what is at each board's door, pending proposals, unmatched CI tests. Timesheet approvals
  deliberately carry none
- **`My work` is the post-sign-in landing** for anyone holding a project grant, and carries an
  **Awaiting you** strip: reviews assigned to you, the boards with work at their door you can move,
  your blocked cards, proposals waiting, and a plain link to timesheet approvals
- ~~**A composite board at `/projects/{id}/board`** joining executions, reviews and flagged cases to
  issues.~~ → **retired (021 R9).** Its issue half duplicated the Dev board and its QA half is now
  the card face's linked-cases line. The **team** board (`/teams/{id}/board`) is unaffected and
  still mixes all four card kinds, because a team's work genuinely spans projects

## Results From CI

- Post results from your pipeline to `POST /projects/{projectId}/ingestions`
- Four report formats are understood: **JUnit XML**, **TestNG XML**, **Cucumber JSON** and **Allure**
- Automated tests bind to TCMS cases by automation key
- A binder screen for tests that arrived but matched nothing, so unmatched results are visible
  rather than silently dropped
- Every ingestion is a tracked job with its own list, detail and report of what matched
- Ingested results feed the stale-automation and undeclared-automation signals, and the flake
  detection behind quarantine

## Documentation

- Write documentation pages from blocks — paragraphs, headings, lists, code, images, and links to
  test cases
- Organise pages into a tree
- Keep pages as drafts, then publish them
- Search across all documentation
- Attach files to a page
- Link cases and documentation to each other in both directions — a page can cite a case, and a case
  shows which pages cite it
- A document can act as the spine of a coverage report

## Artifacts

- Upload, list, download, edit and delete project artifacts
- Each artifact has a kind, a scope and a status

## Reports

Three report types:

- **Run summary** — one run's outcome
- **Aggregate execution** — results across many runs, including a section calling out inconsistent
  results and a section showing where versions have diverged
- **Coverage matrix** — requirements to cases to their last result, with a document as its spine

And around them:

- Reports generate in the background as tracked jobs, with a list showing progress
- Read a finished report on screen, or download it
- Available formats are **HTML**, **Markdown** and **JSON** (PDF is offered but refused unless the
  server has the renderer installed)
- Save a report configuration and re-run it later
- The product tells you which report types and formats *this* server can actually produce, rather
  than offering a choice that will fail
- Reports carry freshness information so you know how current the data is

## Delivery analytics

Four figures on one screen, answering whether a project is converging on its next release. They are
**not** report jobs: there is nothing to generate, poll for or download — the release picker changes
the answer immediately, which is the whole reason they are a screen rather than a document.

- **Release burn-up** — done against total scope over time, and the one thing most burn-ups leave
  out: **scope added after the release was created is distinguishable**. The total-scope line steps
  where the work arrived, and a count beside it says how much. That number cannot be reconstructed
  after the fact, which is why the product records it at the moment an issue is first pointed at a
  release.
- **Cycle time** — how long issues take to finish, **per issue type**, as a median and a p90 with
  the population on the row. It is measured from when an issue was **created**, and the screen says
  so: this product does not record a moment at which work started, and a figure labelled otherwise
  would be a number whose label describes a different number.
- **Flow** — issues per status band, weekly, as four small charts. Three are the statuses. The
  fourth is *issues that existed then whose status on that date is recorded nowhere*, drawn as a
  hatch rather than a colour, because it is the absence of a record and not a fourth state. It is
  empty at today's edge and grows going back.
- **Estimate accuracy** — estimate against **approved** hours per epic, with the drift. An
  unapproved timesheet is not evidence, and an epic nobody estimated shows no drift rather than 0%.

Two things this screen deliberately does not do:

- **There is no velocity chart, and there will not be one.** This product plans by release and has
  no sprint, so there is nothing to measure a velocity over — a chart implying otherwise would
  report a number nobody planned against.
- **Utilisation is not here.** It is a staffing figure — approved hours against allocated capacity —
  and it lives on the Staffing screen with its denominator printed beside it. One number, one place.

**When there is not enough data, each figure says so in a sentence** and draws nothing. A new
project, a release nothing targets yet, a window with two completions, a project whose history
predates what the product records — all of those get an explanation rather than an empty axis or a
flat line at zero, which would read as *nothing has happened* where the truth is *there is not
enough here to say*.

## Issues

~~**Work Items** — create and track work items, tasks and action items attached to test activity;
each carries a status and a priority; they surface on the cross-project and team boards alongside
executions.~~ → **superseded (011 M1), and the correction is not cosmetic.** A stored card was a
`work_item` — a task somebody typed beside a test activity, with a status from a fixed five-member
enum. It is now an **issue** in the project's own tracker, and every task that existed before the
upgrade is still there, still called a Task, now with a per-project key. The old paragraph stays
visible so a reader who finds `work_item` in the git history knows where it went.

What replaces it:

- **A hierarchy, in three levels** — an epic holds story-level issues, and a story-level issue holds
  sub-tasks. The depth rule is expressed in *levels*, not in type names, so a fourth story-level
  type is a row rather than a migration
- **Six issue types per project** — Epic, Story, Task, Bug, Spike and Sub-task, seeded on every new
  project. `Task` sits at the **story** level deliberately, so a solo or internal project never
  needs an epic and a story before "a thing to do"; `Sub-task` is the level below it
- **A per-project key** — `ACME-124`, allocated server-side from the project's own counter and never
  chosen by the caller. It is what a person types into a commit message, and it is what the issue's
  URL takes
- **The project names its own statuses** — six are seeded (Backlog, Ready, In Progress, In Review,
  Blocked, Done) and a project may add, rename and reorder them. Each status carries what it
  *means* — not started, in progress, done — which is what lets a percentage be computed without
  knowing what a project called its columns
- **The legal moves are an allow-list.** A move that is not allowed is refused, and the card says
  which moves are legal instead rather than snapping back in silence
- **Removing a status cannot orphan the issues holding it.** The screen names the count, shows which
  issues are affected, and asks where they go; the move and the removal are one transaction. The
  project's last status meaning *done* cannot be removed at all
- **An epic's completion, with the basis beside it** — a percentage over its story-level children,
  weighted by estimate when every counted child carries one and by head count otherwise. The page
  always says which it got, because a number that changes meaning silently is a number people learn
  to distrust. Spikes are excluded from both halves, and the page says how many
- **An epic's status is derived from its stories** and is not editable. A story's status is its own:
  closing it is a person's decision, whatever its sub-tasks say
- **Comments, watchers and acceptance criteria** on an issue — commenting adds the author as a
  watcher
- **A link from an issue to a specification block**, kept as a projection that marks itself stale
  when the linked page changed after the issue last did
- **A work table** with five views over one project's issues, and a one-field quick add
- **Settings per project** — the issue types the project holds, the workflow screen where statuses
  are reordered with ↑ / ↓ as well as by drag (because dragging is never the only way), and the
  boards screen where a project's boards, their columns and its labels are defined

### Moving in from Jira

An **offramp**, not an integration: it runs once, brings a Jira or Azure DevOps project into this
one, and finishes. Nothing keeps syncing afterwards — a change made in Jira after the migration does
not arrive here, and a change made here does not go back. (The per-project tracker binding under
*Settings → Issue tracker* is the separate, still-live thing that files issues outward.)

- **Four steps: connect, preview, map statuses, report.** Connect takes a site URL and a token; the
  token is used for that one run and is **never stored** — not on the run, not encrypted, nowhere.
  A reload means entering it again, and the screen says so before the field
- **The preview writes nothing at all.** It reads the source project and shows exactly what would
  come across, what would not, and how many values each loss holds. Leaving the page changes not one
  issue, status or label — and a test on the server snapshots every table before and after to keep
  that a fact rather than a reassurance
- **Original keys survive.** `PROJ-4812` is still `PROJ-4812` after the migration, so a commit
  message, a pull request title and a year of Slack history all still point at the right issue. The
  project keeps its own prefix as well, and both resolve
- **Every source status is mapped by hand** onto one of this project's own, with a suggestion where
  the names match. Confirm stays refused while one is unmapped, and the mapping step links across to
  the workflow screen and back with the half-made mapping intact, for the status this project does
  not have yet
- **What cannot come across is named before it is lost, not after.** Story points, sprints, epic
  colour, flags, service-desk request types, time tracking and every custom field — each with the
  number of values it holds and one sentence saying why. **`time tracking` on that list means the
  source system's worklogs**, and it stays on it now that this product tracks time of its own (M6,
  2026-09-04): the importer carries no worklog across, so a project's history of hours starts on the
  day it is imported and the list is what says so before the confirm rather than after. **It cannot be recovered from this product
  later**, so the list is shown before the confirm and kept in the report word for word
- **Sprints are dropped and counted**, with an option to keep each one as a label instead. Off by
  default: a project with sixty sprints becomes sixty labels nobody chose. The sprint row stays in
  the dropped list either way, with its sentence changed to say what was kept in its place
- **The source's backlog order is imported when it is asked for** (on by default for Jira). When it
  is not, the report says the order is insertion order rather than letting it read as imported
- **Comment authors are matched by email**, and an author who does not match is **kept as text on
  the comment** rather than reassigned to somebody who did not write it
- **The whole write is one transaction after the source has been read.** A run that fails leaves the
  project exactly as it was — there is no half-imported project to clean up — and a run left hanging
  by a lost connection is closed out rather than spinning forever
- **A second import of the same source project is refused**, not merged. Two runs would be two sets
  of issues with different keys, and refusing is the only outcome that does not do that silently
- **The report is kept indefinitely and needs nothing but this product to read.** Every count, the
  dropped list, the mapping as applied — with each target status's name and kind as they were at the
  time — and who was matched. It is readable a year later with the source system decommissioned, the
  statuses renamed and the person who ran it gone, and it downloads as JSON, or the dropped fields
  alone as a CSV
- **Jira Cloud, Jira Data Center and Azure DevOps**, through one port. Also reachable from the
  command line: `tcms import list` and `tcms import show` print a past run and its dropped fields.
  Starting one is deliberately not available there, or to an agent over MCP

## Time

Logging hours against work, and having somebody sign for them. Landed 2026-09-04 (plan 016, M6).

- **A timesheet is a semi-monthly period** — the 1st to the 15th, and the 16th to the end of the
  month — and nothing configures it. One shape per instance means a person moving between projects
  has one fortnight, not several, and an approver comparing two people is comparing the same range
- **An hour is logged against an issue, a meeting, leave, training or admin.** Four of the five
  categories name no project, which is why a period spans projects: the mockup's own example carries
  two projects and a project-less meeting in one submission
- **The period is submitted whole and approved whole.** Submitting locks it; approving is final;
  **rejecting unlocks it** and leaves the note on it, which is the only route back to work that was
  sent back
- **Approving is a project-scope grant over a period that spans projects.** A submitted period
  reaches an approver when *at least one* of its entries names a project they may approve for, and
  approving it approves the whole period, including entries against projects they cannot see. That
  is the sharp edge, and it is stated rather than hidden: per-project partial approval would make
  "this period is locked" untrue for half a timesheet
- **Approved and pending hours are two numbers and nothing sums them.** Approved hours are a fact
  somebody signed for; pending hours are a claim nobody has checked. Every figure in the product
  says which of the two it is — the story's Time panel, the epic's estimate-vs-actual table, the
  work table's `Logged` column and its footer, and the timesheet's own tiles
- **The figures reach the work.** A story shows its estimate against its approved hours; an epic
  shows a row per counted child with the drift and highlights the ones over estimate; the work table
  carries a `Logged` column and a footer total over the rows on screen
- **Time tracking is a per-project switch, on by default.** Turning it off hides the `Logged` column
  and both time panels and refuses new entries against that project's issues. It **deletes nothing**
  — every hour already logged and approved is kept, and turning it back on shows them again
- **Nobody sees what an hour costs, from any of these screens.** There is no rate, no cost and no
  bill anywhere in the product, and each screen that shows hours says so rather than leaving the
  absence to be discovered
- **Also on the command line and over MCP** — logging, listing and submitting a period

**What is deliberately absent, with the module that lands it:**

- ~~**Capacity.** `timesheets.html` shows a `Period capacity 64h` tile and `my-work.html` a
  `Capacity 32h` figure beside Logged. Both need person profiles, allocations and absences, which
  are M7's tables. The tile row ships with three tiles rather than four and My Work's panel ships
  with Logged alone and no meter, because a capacity figure this module would have to invent is
  worse than a missing one~~ → **landed 2026-09-04 with Staffing (plan 017).** The timesheet shows
  four tiles and My Work shows Capacity beside Logged with the meter under it. What the old
  reasoning got right is still true and still visible: a person with no staffing profile reads `—`
  and *no profile recorded*, never `0h` and never a defaulted week
- ~~**Budget and burn.** `settings-general.html`'s Budget row reads `600h · Edit in Billing`; there
  is no hours ceiling, no rate and no Billing screen. M8's~~ → **landed 2026-09-05 with Billing
  (plan 019).** The row shows the ceiling and links to Billing, and the project overview draws the
  burn. What the old reasoning was protecting is still true and is now enforced rather than
  incidental: **neither surface carries a currency**, because the endpoint they read has no amount
  and no currency field on it at all
- **Approve-all and Remind on the approvals queue.** There is no batch operation on the contract for
  the first — it would be N irreversible writes with a partial outcome nobody could word, and there
  is no un-approve — and no endpoint at all behind the second
- **A waiting-count badge on the rail.** The count needs a read that refuses anybody without
  approval rights on a project, and there is no company-wide "where may I approve" question to ask
  first. The count is on the approvals screen's own subtitle instead

## Staffing

Who is on what, at what percentage, and who is free. Landed 2026-09-04 (plan 017, M7).

**Four screens, and two of them answer different questions for different people:**

- **`/staffing` — the company's capacity**, one bar per person per project over a date window you
  pick, plus the people over 100%, who has capacity left, and utilisation. Behind
  `staffing:directory`, which Instance Admin, Company Admin, Account Manager and Finance hold
- **`/directory` — the skills directory.** Filter by skill and by a *free from* date, both held in
  the URL so the answer is shareable, and read "3 of 11 people match" against the whole company
  rather than against the filtered list. Same permission
- **`/projects/:projectId/staffing` — this project's people.** Allocate somebody at a percentage
  over a range, change it, remove it. Behind `staffing:read` on that project, with the writes behind
  `staffing:allocate` — which is what a Project Manager holds, and they hold neither company key
- **`/directory/:userId` — one person**, carrying their Profile, Skills and Absence panels. Reached
  from the directory's `Profile` button, and open to that person about themselves whether or not
  they hold `staffing:directory`. **There is no member page under Teams** — `member.html` draws
  these three panels on a screen this product does not have, so they live on the directory's own
  detail view rather than having Staffing port another module's screen

**What the screens do, and what they deliberately do not:**

- **A profile is contracted weekly hours, a job title, a location and the date the person's
  experience started.** The directory shows "6 years"; what is stored is the date, so the figure is
  right every year rather than on the day somebody typed it
- **Capacity is computed on every read and stored nowhere.** A working day is Monday to Friday,
  daily capacity is the weekly hours over five, and there is no public-holiday calendar: a holiday
  is recorded as an absence, which is per person and is already the thing that zeroes capacity
- **An allocation is a percentage over a date range, against one project.** A person allocated to
  two projects over the same days is the ordinary case; **the same person twice on the same project
  over overlapping days is refused** with the conflicting range named, rather than silently merged
- **Over-allocation is flagged, never blocked.** The flag fires at 101% and above, and it is the
  **peak day** rather than a month's sum — somebody at 100% for the first half of a month and 100%
  for the second is not over-allocated, though a monthly total would call them 200%. Nothing stops
  you allocating over 100%; the product's job here is to say so
- **The project screen names no other project.** It shows each person's company-wide total and the
  flag — enough to know not to add to somebody already at 130% — and not what the other 80% is.
  A Project Manager's grant is on their own projects, and listing the rest by name would disclose
  projects they cannot open. The breakdown is on the company view, under the company permission
- **An absence zeroes capacity everywhere it is shown** — the company view, the directory, the
  timesheet's capacity tile and My Work's meter, because all four read the same computation. It
  carries a fraction of a day, so a half day is a half day and not a whole one
- **Recording your own absence needs no permission at all.** `staffing:absence` is what it costs to
  record somebody *else's*. A product where telling it you are away on Thursday was an administrative
  act would be a product where nobody did, which would make every capacity figure here a lie by
  omission. Contracted hours and skills are the opposite — `staffing:profile` even on your own row,
  because they are what the whole company's figures are denominated over
- **Utilisation is approved hours ÷ allocated capacity, and the screen says so.** Approved rather
  than logged: hours nobody has accepted are not yet a fact about the money, which is why Finance
  holds the key that shows this. A person nobody has allocated reads *not allocated* rather than
  `0%` — those are different things, and only one of them is somebody being idle
- **A person with no profile reads `—`, not `0h` and not a default week.** Nobody has recorded their
  contracted hours; that is a real state with something to go and do about it, and inventing 40 for
  it would put two contradictory numbers on the same screen
- **Also on the command line and over MCP** — the directory, one project's allocations, allocating
  somebody, and recording absence. Maintaining a profile or a skill set is deliberately screen-only

## Billing

What an approved hour is worth, what a project may spend, and the invoice that says so. Landed
2026-09-05 (plan 019, M8).

**Two screens, and three figures on screens other modules own:**

- **`/billing` — rate cards, project budgets and invoices**, behind `billing:read`, which Instance
  Admin, Company Admin and Finance hold and nobody else does
- **`/billing/invoices/:invoiceId` — one invoice**: its lines, the approved hours behind each, the
  hours that are *not* on it and why, and the budgets it moves
- **The client screen** gains the hours-and-invoices table and two tiles; **a project's Overview**
  gains a Budget burn panel; **General settings** gains a Budget row

**How it works:**

- **A rate card belongs to a client, and there is one per client.** A card carries a currency and a
  ladder of rates: **a person's rate beats their role's rate, and a role's beats the all-roles
  rate**, each effective from a date. The ladder is read **as of the day the hour was worked**, so
  an invoice for August prices August's hours at August's rate whatever the card says today
- **Only approved hours are priced.** Hours nobody has signed for are not a fact about the money —
  they are counted and **named** on every draft as excluded, never silently dropped, and the same
  goes for hours whose client has no rate card
- **A budget is a ceiling on a project, in hours or in money**, set by Finance. Setting one is
  optional and a project without one is in an ordinary state, not a deficient one
- **A Project Manager sees the burn and never the rate.** The project-scope read carries a
  percentage, the approved hours and the ceiling in hours — and has **no amount and no currency
  field at all**, which is why it costs no money permission. A money-basis budget therefore reads as
  a percentage alone, and where the client has no rate card the burn is **unknown rather than
  zero** — a project that has spent a quarter must not read as one that has spent nothing
- **The burn is not capped at 100%.** 102% draws as a full bar with the overrun beside it, in the
  danger tone, on every screen that draws it. Clamping would make an overrun look identical to
  landing exactly on budget
- **A draft invoice stores no lines — it is a live report.** Draft one for a client and a period and
  it recomputes from the approved hours that exist *now*, on every read: approve a timesheet and the
  draft moves, with no recalculate button, no staleness flag and no window in which it disagrees
  with the timesheets behind it
- **Issuing writes the lines once and assigns the number, and after that nothing can change them.**
  Not by a guard somebody can forget — there is no operation anywhere that edits a line. The rate is
  stored **as applied**, so doubling a rate card next month does not reprice an invoice sent last
  month, and discarding an issued invoice is refused
- **Issuing sends nothing.** Sending an invoice is your mail client's job; what issuing does is make
  the figures stop moving
- **Expenses are recorded against a project and are never invoice lines.** Reachable from the
  command line and over MCP; there is no expenses screen
- **Billing is absent from the sidebar** — not greyed out — for anybody without `billing:read`. The
  deliberate exception to this app's disable-don't-hide rule: what a colleague costs is not
  something to advertise the existence of to the colleague. The route stays reachable and refuses
  with a sentence naming who does hold the key
- **A Test Engineer who logs the hours cannot see what they cost, and an Account Manager sees the
  client and the hours and never a rate.** Both are pinned by tests that ask the server, not the
  screen
- **Also on the command line and over MCP** — rate cards, budgets, drafting, issuing and expenses

**What this module does not do, and will not:**

- **No payment tracking.** Nothing anywhere says *Sent* or *Paid*, and there is no running balance
  per client. An invoice's states are Draft and Issued, and issuing is the last thing this product
  knows about it
- **No fixed-fee or retainer invoicing.** An invoice line is approved time at a rate-card rate, and
  nothing else — stated on the Billing screen itself rather than only in a decision document
- **AI-assistant usage is a cost, never a line.** An assistant's hours are never charged to a
  client
- **No PDF.** There is no rendering of an invoice to a document, and no *Preview PDF* button that
  would promise one
- **A project with no client cannot be invoiced, and nothing marks that as a deficiency.** It
  appears in the budgets table as *None (internal)*, with a budget of its own if it has one, no
  warning tone and no call to action — which is the whole state a company that builds its own
  products lives in

## Teams and People

- Create teams and manage their membership
- Grant a role to a team on a project and every member inherits it
- An **org chart** showing everyone in the company, the roles they hold across projects, and their
  team memberships — the people axis is the whole company, while the project axis shows only the
  projects *you* can see
- Invite people to the company with a role, list the directory, and deactivate people who leave

## API Tokens

- Issue a token for CI or for an AI agent, with a role and optionally scoped to a single project
- The credential is shown once, at creation
- Revoke a token, set an expiry, and see when each was last used
- **A token never grants more than its owner has.** Its reach is its owner's permissions
  intersected with the token's role — so demoting a person immediately shrinks every token they
  issued

## Notifications

- Configure notification channels per project — email or webhook
- Get notified when work is assigned to you
- Every delivery attempt is recorded, so a channel that stopped working is visible

## Command-Line Tool

`tcms`, driven by `TCMS_URL` and `TCMS_TOKEN`. ~~**27 commands**~~ → **35**, the eight added by the
board module being the difference. The authority is the registry and
`docs/specs/2026-08-08-mcp-tool-and-command-surface.md` §6; this list agrees with it rather than
re-deriving it, and the in-app guide is compared to the registry by a test in both client packages.

*Reading* — `whoami` · `format` (what a payload must look like, reaching no instance) ·
`project list` · `case list` · `case show` · `library list` (the condition library) · `suite list` ·
`suite show` · `plan list` · `run list` · `doc list` · `doc show` · `proposal list` ·
`proposal show` · `issue list` · `issue show` · **`board list`** · **`board show`** ·
**`backlog`** · **`label list`**

*Writing* — `case edit` · `case propose` · `suite create` · `suite edit` · `suite status` ·
`plan create` · `plan edit` · `plan status` · `run create` · `run status` · `result record` ·
`doc create` · `doc edit` · `doc link` · `doc unlink` · `issue create` · `issue move` ·
`issue comment` · **`board move`** · **`rank`** · **`label add`** · **`label set`** ·
`review comment`

**`board move` and `rank` take a board or a neighbour by name and refuse before sending** when the
argument is ambiguous — `board move` names every board when a project has more than one and none was
given, and `rank` requires exactly one of `--after` / `--before`, because both and neither are the
two payloads the server answers 400 for.

**Not one of these has been run against a running instance.** They are checked against the API
contract, which is the contract's shape and not a handler's answer.

## AI Agent Access (MCP Server)

An MCP server exposing ~~**26 tools**~~ → **28**, over stdio or HTTP, so an AI assistant can work
with TCMS directly.

*Orientation (3)* — `tcms_whoami` · `tcms_orient` (project summary with vocabularies and
capabilities) · `tcms_shape` (what a payload of a given kind must look like, reaching no instance)

*Reading (13)* — `tcms_search_cases` · `tcms_open_case` · `tcms_list_suites` · `tcms_list_library` ·
`tcms_list_artifacts` · `tcms_doc_tree` · `tcms_open_doc_page` · `tcms_list_plans` · `tcms_open_run` ·
`tcms_read_issue` · **`tcms_read_board`** · `tcms_list_proposals` · `tcms_triage`

*Writing (12)* — `tcms_write_case` · `tcms_write_suite` · `tcms_write_doc_page` · `tcms_write_plan` ·
`tcms_write_run` · `tcms_record_result` · `tcms_review_comment` · `tcms_review_verdict` ·
`tcms_write_issue` · **`tcms_write_backlog`** · `tcms_write_artifact` · `tcms_report`

**Defining a board is not on this surface**, and neither is creating or deleting one: composing a
column array blind against a rule the server enforces in a 422 spends an agent's turns on the
refusal. Moving a card and setting an issue's labels are arms of `tcms_write_issue` because they
cost `issue:edit`; ranking and the three label writes are `tcms_write_backlog` because they cost
keys only a Project Manager and up hold — one write tool over both would be listed to a caller who
is refused two thirds of it.

**Neither board tool has been called against a running instance.** `tcms_write_backlog` in
particular is invisible to the fixed `Agent` credential the server is usually pointed at, by
design.

What makes it safe to point at real data:

- The agent's credential is capped at a fixed **Agent** role, and the server tells the model up
  front what that credential can actually reach — so it does not attempt work it will be refused
- Destructive operations prompt for consent, and **refuse outright when the host cannot ask**
- Tools are filtered by permission, so a narrowly-scoped token sees a narrower tool list

~~Connecting one is handed over rather than described. The guide's MCP section generates the agent
token in place — one press, for anybody who may issue one — and drops the secret straight into the
`mcpServers` block for this instance, so setup is *copy this*.~~ → **superseded 2026-09-04 (plan
015).** That path still exists and still costs `token:issue`, which most people do not hold; the
one a developer uses now is **self-service** and is described below.

## Assistants — a developer's own AI, working a task

A person connects their own AI assistant from their profile, flags a task as AI-eligible, and the
assistant fetches it with its whole working context, reports a pull request back and moves it to In
Review. **This product never runs code, never holds a repository and never calls a model.** It hands
over a task and records what comes back as *that person, via that assistant*.

**An assistant is a credential, not an account.** There is no agent principal, no orchestrator, no
runner and no model key anywhere in this system: an assistant is an API token its owner issued for
themselves, carrying the fixed `Agent` role, and its reach is that role intersected with the
owner's own. Connecting one therefore needs **no permission at all** — it can never grant more than
the person already holds, and if their permissions are reduced tomorrow its reach shrinks with
theirs the same moment. Every act it performs already carries both halves of who did it, so the
history reads *"Dana Petrov, via claude-code"* with nothing built to make it.

- **Connect, scope, revoke** — `/account/assistants`. Name it, pin it to one project or leave it as
  wide as you are, and take a one-line setup snippet. The token is shown **once** and lives only in
  that page's memory; leaving loses it. A revoked assistant stays on the list rather than
  disappearing, because its acts are in every issue's history and a vanished row would read as
  though it had never existed
- **AI-eligible tasks** — a flag on a sub-task, set from the task page and marked on the task, the
  work table and the board card. Only flagged tasks reach an assistant's queue; nothing else about
  the task changes
- **One fetch, whole context** — `GET /issues/{key}/agent-context` returns the task, its parent
  story's acceptance criteria, the epic's goal, the target release, the linked spec excerpts and the
  project's repositories and build commands in a single read. An assistant that has to assemble
  context from six calls generates without it. The task page renders the **same payload**, so a
  person can see exactly what was handed over — which is how you tell "it misread the spec" from
  "it never received it"
- **A pull request comes back as a link, not an integration** — `prUrl` on the issue, recorded on
  the history. Nothing here reads the branch, the diff or the review state
- **It cannot close its own work, where the project says so** — "Require review to close issues" is
  a per-project switch, **off by default**, and it binds on the issue's *assignee*. An assistant
  acts as its owner, so it is bound exactly as they would be — there is **no assistant-specific
  rule**, which is why a one-person company with the gate off closes its own work and is not
  dead-ended. When it does bite, the move is dropped from the caller's own legal-move set, the
  refusal names the move to make instead, and the attempt is recorded
- **Repositories are reference data** — a project lists where its code is, the default branch, the
  working folder and the build command, under Settings → Repositories. Nothing is cloned, fetched or
  run. **A project with none is not degraded in any way**; an assistant is simply told where the
  code is by the person running it

On the tool surface this is three arms rather than three tools: `tcms_read_issue` gains a **queue**
mode (the caller's own AI-eligible tasks) and a **context** mode, and `tcms_write_issue` gains
**attach-pr**. On the CLI: `tcms issue queue`, `tcms issue context KEY`, `tcms issue attach-pr KEY
--pr-url URL`.

**Not built, and named rather than implied:** an assistant cannot *propose* a breakdown of a story
into sub-tasks for a person to accept. That needs an issue-proposal record of its own — the existing
proposal surface is the test-case one, a different subject in a different module — and neither the
table nor the endpoint exists. Nothing in the product pretends otherwise.

## Running It

- One deployable: REST API plus the React app
- `docker compose up` for a local stack, Postgres included
- Companies are created by an operator command (`provision-company`), which is safe to re-run to
  top up a company after a release
- Operator commands for granting instance-admin and issuing invitations
- A health endpoint, rate limiting, and filesystem storage for evidence and generated reports

## Platform and Architecture

*The technical shape, for readers who need it.*

**Tenancy.** Every company-owned table carries a `company_id`, and Postgres row-level security is
forced on 70 policies. The application connects as an unprivileged role that owns nothing and holds
no `BYPASSRLS`; migrations run as a separate owner role. Company context is a scope guard rather
than a setter, established for every transaction — not just web requests — so background jobs and
event handlers are covered too. An unset context yields **zero** rows, never all rows.

**Backend.** Spring Boot 3.5.6, Java 21, Gradle. A Spring Modulith monolith whose modules are Java
packages — `tenancy · identity · project · storage · repository · execution · documentation ·
reporting · work · integration · provisioning`. Cross-module references outside the foundation
modules go through domain events, enforced by ArchUnit. Flyway owns all DDL; Hibernate only
validates. Compiles with `-Werror`.

**Frontend.** Vite 8, React 19, TypeScript strict, MUI v7 with X DataGrid and TreeView. React Router
7. TanStack Query owns all server state; filter state lives in the URL, so any filtered view is
bookmarkable and shareable. Errors are centralised — one place turns an error into copy, one place
raises a notification, and root/route/region error boundaries keep a broken panel from taking down
the page. Heavy screens are code-split. A dev-only fault injector (`tcmsFaults`) makes the retry,
timeout and session-recovery paths drivable from the browser console.

**The contract.** `openapi.yaml` is authoritative and generates the TypeScript types for both the
SPA and the client package. Tests compare every mocked response — and every request payload — against
it, in both directions, including status codes and enum values.

**Tests.**

| Suite | Count |
|---|---|
| Backend (Testcontainers, real Postgres 17 — no in-memory fallback) | ~3,082 |
| Frontend (Vitest + React Testing Library, 119 files) | 1,582 |
| Packages (client 130 / CLI 78 / MCP 128) | 336 |

Several are structural rather than behavioural: the tenancy column and RLS predicate are asserted
over every table, the permission matrix is compared cell-by-cell against its specification in both
directions, every repository is checked for the annotation whose absence would silently empty its
reads, every file path cited in a comment must exist on disk, and any sentence in the codebase
claiming somebody lacks a permission is checked against the spec.

**Delivery.** GitHub Actions builds and lints on every push; a nightly lane runs the full backend
suite on a self-hosted Linux runner, deliberately a different shape from a developer's Mac.
---

## Planned but Not Yet Implemented

*Verified against the code on 2026-08-18. Items that turned out to be built, or built in part,
are annotated rather than listed as missing; two claims (suite-level condition inheritance, and
SAML as pending work) were removed outright as wrong.*

### M6 — Multi-Company Activation (~152 tasks across 5 child plans, not started)

- Company switcher & URL routing (`/c/{slug}`) — company picker in the app bar, session re-issuing on switch, slug in all URLs *(M6 Plan D)*
- Company-in-the-session — `GET /me/companies`, `POST /auth/session/company`, `instance:read` permission *(M6 Plan A)*
- Suspension honoured — `SuspendedCompanyFilter` refusing non-Instance-Admin access, 503 from ingestion *(M6 Plan B)*
- Per-company branding — logo, brand colours driving a per-company MUI theme, branded login page and report headers *(M6 Plan B)*
- Per-company metrics — Micrometer company tags for operator visibility *(M6 Plan B)*
- Federation login routing — email-domain → company routing, per-company IdP selection *(M6 Plan C)*
- IdP group-to-role/team claim mapping — mapping IdP group claims to TCMS roles or teams *(high-level plan §6r, M6 Plan C)*
- Instance Operator Console — browser-based console for Instance Admins to manage companies, invitations and system settings (1 of 92 tasks done) *(M6 Plan E)*

### Phase 2 — Not started

- **~~Issue tracker integration (Jira, GitHub Issues, GitLab Issues, Azure DevOps) — link cases ↔
  issues, one-click defect creation from a failed step, status sync-back, inline status
  hover-cards~~** → **partly superseded 2026-08-29 (plan 004, Phases 1 and 3).** Built: Jira
  Cloud, Jira DC and Azure DevOps bindings per project, a per-person credential (D2's
  inversion — the credential belongs to the user, not the project), case ↔ issue links, and
  filing a defect from a failed execution. **Not built:** GitHub Issues and GitLab Issues
  providers, and status sync-back — `defect_link.statusCache` is rendered when present and
  nothing writes it, so the hover-card has no source yet
- `.feature` export/import — generate `.feature` files from Gherkin cases, import existing ones, direction-of-truth per project *(§6a)*
- Cucumber step-level result mapping in CI ingestion
- Git traceability, remaining half — **the run half is implemented**: `test_run` carries `commit_sha`, `branch`, `build_number` and `release_version`, `RunCreateDialog` collects them and the run detail returns them. Not built: PR linkage and case↔requirement linking

### Phase 3 — Not started

- Trend dashboards — pass rate over time, MTTR on failing cases, execution time trends, coverage drift
- Bulk import/export — CSV/Excel import, TestRail/Xray migration importers
- Parameterized cases / data-driven execution — Scenario Outline `Examples` and structured-case DataSets
- Step phrase library — catalogue of Gherkin step phrases with autocomplete and near-duplicate detection
- Undefined-step detection — compare authored Gherkin against the automation repo's step-definition inventory
- ~~Milestones & release readiness — gate view per milestone~~ → **built (013 M3 and 020 M9).**
  Releases replaced milestones as the product's only planning cadence, release readiness answers
  *"can we ship 2.4?"* across both the delivery and the test halves, and Delivery analytics above
  is the trend view this line was reaching for. Struck through rather than deleted so a reader who
  finds *milestone* in the schema knows where the idea went.
- SCIM provisioning — push-based user/group sync from IdP
- Generic outbound webhooks — webhook events for custom automation beyond notifications
- Public versioned REST API — first-class, versioned, documented API layer

### Phase 1 — Planned but incomplete

- Gherkin syntax-aware editing — **the format itself is implemented**: a `STRUCTURED`/`GHERKIN` toggle, a seeded `Feature:` template, non-empty validation, and `GherkinView` keyword rendering on the detail view. Not built: keyword autocomplete, server-side parse/validation, Background/Scenario Outline handling, `.feature` preview
- Editing the execution-status vocabulary — the data model and the read exist (a project's
  statuses are its own, ordered, and every screen reads them); the six are seeded at
  provisioning and no endpoint or screen adds, renames or reorders them
- Test plan templates — reusable plan skeletons (plan cloning exists; templates do not) *(§6v)*
- Per-configuration matrix **view** in plans — the data model exists (a plan carries a `configurations` array, a run carries its `configuration`); the matrix screen does not *(§6p)*
- Paste-from-clipboard and drag-drop evidence UX
- Version restore — version history and diffs exist; restoring a previous version does not *(§6j)*
- Saved/named searches — composable filters and shareable URLs exist; saved searches do not *(§6q)*
- PDF report rendering — HTML, JSON and Markdown renderers exist. `ReportFormat.PDF` is declared and offered in `ReportCapabilitiesView`, and `ReportJobService.refusalFor` answers "not installed on this server": the headless browser is deliberately out of the image, so this is absent by decision rather than unbuilt *(§6e)*
- Virus-scan hook on file upload

### Explicitly deferred by decision

- Company CRUD screens — companies are provisioned by operator script *(Phase 4)*
- Cross-company "all my work everywhere" view *(Phase 4)*
- Storage-quota management — deferred until real contention *(Phase 4)*
- SAML 2.0 — ruled out of scope by resolved question #40. `IdentityProviderKind` has **no** SAML member and `V53` says so explicitly; OIDC and LDAP cover the enterprise case
- Custom roles / clone-and-edit — deliberately rejected; nine immutable roles only
- Elasticsearch — deferred until Postgres FTS proves insufficient *(§4)*

---

## Gaps Not Currently Planned

*Identified 2026-08-18 by reading the shipped surface against what the product is for. These are
**not** on any milestone or phase list above — they are additions to the backlog, ordered by the
implementation plan at `docs/plans/2026-08-18-unplanned-gaps.md`.*

- **The audit log has no reader.** `AuditEvent` and `InstanceAuditEvent` rows are written on every
  act, `audit:view` and `project:audit` are seeded, and the SPA carries UI labels for both — but
  there is no endpoint and no screen. `AuditEvent.java:220` states it: *"`audit:view` and
  `project:audit` still have zero consumers."* The expensive half is built and returns nothing.
- **Notifications do not cover the review workflow.** Two events fire — item-assigned and
  run-completed. Nobody is told they have been asked to review, that their case was approved, or
  that changes were requested, so a workflow that depends on someone noticing stalls silently.
- **~~No bulk execution assignment.~~** ~~Cases have `/projects/{projectId}/cases/bulk`; executions
  have only a per-execution assignee endpoint. Splitting a 300-case run across five testers is 300
  operations.~~ → **superseded 2026-08-22 (§245).**
  `POST /projects/{projectId}/runs/{runId}/executions/bulk` assigns a selection to one person — or
  **distributes** it round-robin across a list, which is the request the gap was actually about.
  Preview-then-commit, one transaction, no new permission and no migration.
  **It is also `audit_event.bulk_operation_id`'s first writer in the product's history**: one row
  per target sharing one id, because one row with 300 `target_ids` cannot state an act that sends
  twelve cases to five different people — and the audit trail groups on that id rather than being
  flooded by it. There is deliberately **no `Idempotency-Key`**: nothing is created, so a replay has
  no resource to be answered with, and determinism is the property instead.
  **Built, ungated and unverified** — `docs/handoff/245-bulk-execution-assignment.md`.
- **~~No time tracking on results.~~ ~~No elapsed or duration field on an execution~~, so "how long
  did this cycle take last time" — the question a lead plans the next cycle from — is unanswerable.**
  → **superseded 2026-08-22 (§244).** The second sentence was false when it was written and the
  first was true for a different reason. `test_execution.duration_ms BIGINT` has existed since
  `V1__initial_schema.sql:845`, is mapped at `TestExecution.java:96`, is served on
  `TestExecutionSummary`, `TestExecution` and `ExecutionHistoryEntry`, is copied into
  `execution_fact` by `ExecutionSource` and is summed into the run summary report's "recorded
  duration" at `RunSummaryReport.java:396`. **What it had was no writer at all** — the ingestion
  parsers recovered a duration and `IngestionService#payload` stashed it in the ingestion job's
  payload JSON, never in the column — so five readers and one report answered null for every run in
  the product from the schema's first day. Closed by area A of
  `docs/plans/2026-08-18-unplanned-gaps.md`: `ExecutionResultInput.durationMs` gives a person
  recording a result by hand a way to set it, and `IngestionService` now writes the value it
  already had. **Built and unverified** — see `docs/handoff/244-the-duration-that-was-already-there.md`.
- **No cross-project case search.** The repository is project-scoped throughout, though My Work,
  the boards and the org chart all went cross-project. "Have we tested this anywhere in the
  company" cannot be asked.
- **No keyboard-driven execution.** Recording results is the highest-volume repetitive task in the
  product and every result costs a pointer round-trip.
- **No internationalisation layer.** No translation infrastructure of any kind; a retrofit is far
  more expensive than the groundwork.
- ~~**No scheduled runs.** Internal jobs are scheduled, but a regression cycle cannot be.~~ →
  **closed 2026-08-22 by §247** (area C of `docs/plans/2026-08-18-unplanned-gaps.md`, stepped into
  `docs/plans/2026-08-18-gap-c-scheduled-runs.md`). `V68`'s `run_schedule` is a standing request —
  what to ask for and when — and a per-company poller re-asks it, entering `RunCreateService`
  through a third door so nothing about run creation is reimplemented. **Built and unverified**: the
  batch it landed in ran no gates at all, and `docs/verification/backend-mutation-checks.md` §247
  and `docs/verification/frontend-browser-checks.md` §247 are both unrun.
- **No company data export.** Companies are provisioned by script with no offboarding path —
  relevant both to GDPR and to a client leaving.

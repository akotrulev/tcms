# TCMS → Project Management — User Stories & Flows

**Companion to** `2026-08-30-project-management-expansion-scope.md` (D1–D34). That document decides
*how*; this one describes *what a person sees and does*. No tables, no modules, no identifiers —
if a sentence here needs a schema to be true, it belongs in the other document.

**Status:** Draft, 2026-08-30. Stories are grouped by the order they ship (§9 of the scope doc).
Each story stands alone; the "Flow" sections between groups show how they chain.

---

## 0. Who is in these stories

| Role | Who they are in the owner's words |
|---|---|
| **Company Admin** | Owns the company's profile in the product. Can do everything below. |
| **Project Manager** | Runs a project: epics, stories, releases, the board, staffing, approving time. |
| **Test Lead** | Owns the test side of a project; can also manage its issues. |
| **Test Engineer** | A developer *or* a QA. Works tasks, files bugs, logs time. The only difference between a dev and a QA is which screens they open. |
| **Account Manager** | Knows the clients and their contacts. Never sees money. |
| **Finance** | Rate cards, budgets, invoices. Reads clients, writes money. |
| **Viewer** | Looks; never changes anything. |

**The company may have no clients at all.** The owner's own case is a company building its own
products. Every story below must read correctly for that company too — nothing nags them to create
a client they do not have.

---

## 1. Navigation — where everything lives

```
Sidebar
├─ My Work                  everything on my plate, across every project
├─ Projects
│   └─ <a project>
│       ├─ Overview         ← opening a project lands here (D27)
│       ├─ Work             ← one entry; Board · Backlog · Table · Timeline · Calendar toggle inside (D34)
│       ├─ Releases
│       ├─ Documents        (exists today)
│       ├─ Test Cases · Suites · Plans · Runs   (exist today)
│       └─ Settings         issue types, statuses, boards, review gate, repositories
├─ Clients                  company-level; always reachable, even when empty (D1)
├─ Timesheets               mine to fill, others' to approve
├─ Billing                  Finance and Company Admin only
├─ Teams · Directory        (exist today)
└─ Help                     includes "Connect an AI assistant"
```

Three navigation rules that every story below assumes:

1. **A project opens on its Overview.** Delivery and quality on one page. Nothing is one click away
   that the owner said should be visible on opening.
2. **All views of a project's work are one place.** Board, Backlog, Table, Timeline and Calendar are
   tabs inside *Work*, sharing the same filter. Switching views never loses a filter.
3. **Client-related columns and filters appear only once a client exists.** The *Clients* entry
   itself is always there so the first one can be created.

---

## 2. Foundations — what changes for existing users on day one

### Story F1: Existing tasks keep their name

**As a** Test Engineer who has been using the current task list
**I want** the tasks I already created to still be called tasks after the upgrade
**So that** nothing I typed changes meaning underneath me

**Functional**
- [ ] Given I created a task before the upgrade, when I open My Work afterwards, then it is still there with the same title, status, assignee and due date
- [ ] Given I created a task before the upgrade, when I look at its type, then it reads "Task", not "Story"
- [ ] Given a task existed before the upgrade, when I open it, then it now carries a project-prefixed key (e.g. `ACME-12`) and the old link still opens it

**Out of scope**
- Re-keying existing test cases, suites, plans or runs — those keys do not change

### Story F2: New roles exist to be granted

**As a** Company Admin
**I want** to grant someone Project Manager, Account Manager or Finance
**So that** a person's job in the company matches what the product lets them do

**Functional**
- [ ] Given I am on a member's page, when I choose a role, then Project Manager appears among project roles and Account Manager and Finance among company roles
- [ ] Given a member holds Account Manager only, when they open Billing, then the entry is absent from their sidebar
- [ ] Given a member holds Test Engineer, when they open an issue, then they can create a new issue and edit one, but cannot delete one or change the project's workflow

---

## 3. Clients (deferrable — build the week the first real client exists)

> **Flow:** Company Admin or Account Manager creates a client → attaches a project to it → the
> client column, filter and per-client rollups appear everywhere they are relevant.

### Story C1: Create a client

**As an** Account Manager
**I want** to record a client organisation and its contacts
**So that** every project we do for them is traceable to who we do it for

**Functional**
- [ ] Given the company has no clients, when I open Clients, then I see an empty state that explains what a client is for and offers "Add client"
- [ ] Given I am adding a client, when I save with a name only, then the client exists — contacts, rates and contracts are optional
- [ ] Given a client exists, when I add a contact, then I can record name, email, phone and role, and the contact never becomes a login

**Out of scope**
- A client logging in or seeing anything (a client portal is a separate decision, not an extension of this)

### Story C2: Attach a project to a client

**As a** Project Manager
**I want** to say which client a project is for
**So that** time and billing roll up to the right customer

**Functional**
- [ ] Given at least one client exists, when I edit a project, then a "Client" field appears with the client list and a "None (internal)" option
- [ ] Given no clients exist, when I edit a project, then no Client field is shown at all
- [ ] Given a project is internal, when I look at any list or report, then nothing marks it as missing a client

### Story C3: See work by client

**As a** Company Admin
**I want** to filter projects and rollups by client
**So that** I can answer "what are we doing for Acme?" in one place

**Functional**
- [ ] Given at least one client exists, when I open the projects list, then a Client column and a Client filter are present
- [ ] Given I open a client, when I look at its page, then I see its projects, contacts, and — once time and billing exist — hours and invoices to date
- [ ] Given no clients exist, when I open the projects list, then no Client column is shown

---

## 4. The delivery hierarchy — epics, stories, tasks

> **Flow (the core loop):** PM creates an epic, links it to the spec → adds stories with acceptance
> criteria → a dev or QA breaks a story into tasks and works them → progress rolls up to the epic →
> the Overview shows completion.

### Story H1: Create an epic and link it to documents

**As a** Project Manager
**I want** to create an epic with a goal, dates and links to the specification it comes from
**So that** the team can see what a body of work is for without asking me

**Functional**
- [ ] Given I am in Work, when I choose "New epic", then I can enter a title, description, start and target dates, and a target release
- [ ] Given an epic exists, when I link it to a block in a project document, then the epic shows the linked excerpt and a link that opens the document at that block
- [ ] Given a linked document block has been edited since I linked it, when I open the epic, then the link is marked "changed since linked" and I can re-confirm it
- [ ] Given I open an epic, when I look at it, then I see its stories with their status, its completion percentage, and the count behind it (e.g. "4 of 7 done")

**Out of scope**
- Epics spanning more than one project

### Story H2: Add stories, bugs and spikes under an epic

**As a** Project Manager
**I want** to add stories, bugs and spikes, optionally under an epic
**So that** work is recorded at the size a person can pick up

**Functional**
- [ ] Given I am on an epic, when I choose "Add story", then the new story is created already under that epic
- [ ] Given I am in Work, when I create a story without choosing an epic, then it is saved without one and appears in the backlog
- [ ] Given I create a bug, when I look at its parent epic's completion, then the bug counts as unfinished work until it is done
- [ ] Given I create a spike, when I look at its parent epic's completion, then the spike is excluded from the percentage, and the epic page says so

### Story H3: A quick task for a solo or internal project

**As a** Test Engineer working on my own company's product
**I want** to add a task with one field and no ceremony
**So that** my to-do list does not require an epic and a story first

**Functional**
- [ ] Given I am in Work, when I press "Add" and type a title, then a Task is created at the story level with no parent required
- [ ] Given I created a quick task, when I open it later, then I can optionally attach it to an epic
- [ ] Given a story exists, when I choose "Add sub-task" on it, then the new task is created under that story and cannot be detached into an orphan

### Story H4: Break a story into tasks for dev and QA

**As a** Test Engineer assigned a story
**I want** to break it into tasks and assign each
**So that** a developer and a tester can each own their part of the same story

**Functional**
- [ ] Given I am assigned a story, when I add tasks under it, then each task has its own assignee, status and estimate
- [ ] Given a story has tasks, when I open the story, then I see the tasks with who holds each and where each stands
- [ ] Given every task under a story is done, when I open the story, then it does not close itself — closing the story is my decision

### Story H5: Move work through its states

**As a** Test Engineer
**I want** to change an issue's status from the issue itself or by dragging it on the board
**So that** progress is visible without a status meeting

**Functional**
- [ ] Given I open an issue, when I change status, then only the moves the project's workflow allows are offered
- [ ] Given I drag a card to a column the workflow forbids, when I release it, then the card does not move and the board tells me why
- [ ] Given I move an issue to a done state, when I return to its epic, then the epic's completion has updated
- [ ] Given every story in an epic is done, when I look at the epic, then its status reads Done without anyone editing it

### Story H6: Estimate in hours

**As a** Project Manager
**I want** to estimate stories in hours
**So that** later, budgets and invoices are in the same unit as the plan

**Functional**
- [ ] Given I edit a story, when I enter an estimate, then it is in hours
- [ ] Given every story in an epic has an estimate, when I open the epic, then completion is weighted by hours and the page says "by estimate"
- [ ] Given at least one story lacks an estimate, when I open the epic, then completion is by count and the page says "by count"

### Story H7: Link a story to the test cases that prove it

**As a** Test Lead
**I want** to link a story to its test cases
**So that** "done" and "proven" are the same screen

**Functional**
- [ ] Given I open a story, when I choose "Link test cases", then I can pick from the project's cases and the story lists them
- [ ] Given a story has linked cases, when I open it, then I see the latest result of each case beside it
- [ ] Given I open a test case, when I look at it, then I see which stories it verifies

### Story H8: Comments, watchers and history

**As a** Test Engineer
**I want** to discuss an issue in place and follow ones I care about
**So that** decisions are recorded where the work is

**Functional**
- [ ] Given I open an issue, when I add a comment, then it appears with my name and time, and watchers are notified
- [ ] Given I watch an issue, when its status or assignee changes, then I am notified
- [ ] Given I open an issue's history, when I read it, then I see every status, assignee and field change with who made it and when

---

## 5. Boards, backlog and views

> **Flow:** the PM configures the workflow once → sets up a board for the team and a coarser one for
> stakeholders → everyone works on the board → the same issues appear on both, always in agreement.

### Story B1: Configure the project's workflow

**As a** Project Manager
**I want** to define the statuses a project uses, in order, and which count as done
**So that** the board and every percentage mean what my team means

**Functional**
- [ ] Given I open project Settings, when I edit the workflow, then I can add, rename, reorder and remove statuses and mark each as "not started", "in progress" or "done"
- [ ] Given a project is new, when I open its workflow, then a sensible default is already there so I can start without configuring anything
- [ ] Given I try to remove a status that issues currently hold, when I confirm, then I am asked which status those issues move to

### Story B2: Two boards over the same work

**As a** Project Manager
**I want** a detailed board for the team and a simpler one for stakeholders
**So that** each audience sees the granularity they need without the work being copied

**Functional**
- [ ] Given I create a board, when I define its columns, then each column claims one or more workflow statuses, and every status is claimed by exactly one column
- [ ] Given a stakeholder board's "In flight" column covers three engineering statuses, when I drag a card into it, then the card takes that column's default status
- [ ] Given an issue is moved on one board, when I open another board of the same project, then the card is in the matching column there
- [ ] Given I delete a board, when I confirm, then no issue is lost — the board was only a view

### Story B3: Work the board

**As a** Test Engineer
**I want** a board with labels, assignees, due dates and swimlanes
**So that** I can scan the day's state in a glance

**Functional**
- [ ] Given I look at a card, when I read it, then I see key, type, title, labels, assignee, due date, estimate, and how many sub-tasks are done
- [ ] Given I choose "Group by epic", when the board reloads, then each epic is a swimlane and I can collapse it to a single roll-up card showing its completion
- [ ] Given I choose group by assignee, label, release or priority, then the swimlanes follow that grouping
- [ ] Given a column has a work-in-progress limit and I exceed it, when I drop the card, then the drop is accepted and the column header shows the breach
- [ ] Given I filter, when I pick several labels and one assignee, then I see cards matching *any* of the labels *and* that assignee
- [ ] Given I set a filter, when I switch to Table, Timeline or Calendar, then the same filter applies

**Non-functional**
- [ ] Every board action is reachable from the keyboard, including moving a card between columns

### Story B4: Order the backlog

**As a** Project Manager
**I want** to rank the backlog by dragging
**So that** "what's next" is a list, not a debate

**Functional**
- [ ] Given I am in Backlog, when I drag an issue above another, then the order persists for everyone
- [ ] Given I filter the backlog to a release, when I look at it, then I see only that release's issues, in rank order
- [ ] Given I select several issues, when I choose "Move to release", then all of them get that target release

### Story B5: My Work still shows everything on my plate

**As a** Test Engineer
**I want** My Work to show my issues *and* my test executions, reviews and case maintenance
**So that** one screen answers "what should I do today?"

**Functional**
- [ ] Given I am assigned issues in two projects, when I open My Work, then both appear beside my pending reviews and executions
- [ ] Given I add a personal note or personal due date to a card in My Work, when a teammate looks at the same issue, then they do not see my note

---

## 6. Releases, roadmap and the project overview

> **Flow:** PM creates releases → assigns epics and stories to them → the Overview shows the next
> release, what's left in it, and whether it is ready (stories done *and* tests passing).

### Story R1: Create a release

**As a** Project Manager
**I want** to create a release with a version and a target date
**So that** planning has a cadence without inventing sprints

**Functional**
- [ ] Given I open Releases, when I create one, then I give it a version and optionally a date and description
- [ ] Given releases exist, when I list them, then `1.10` sorts after `1.9`
- [ ] Given a release exists, when I edit a story or epic, then I can pick it as the target release

**Out of scope**
- Sprints, velocity, sprint burndown — there are none
- A release shared between two projects — a version shipped from two projects is two releases

### Story R2: The project overview

**As a** Project Manager
**I want** opening a project to show me where delivery and quality stand together
**So that** I never have to visit two pages to answer "how is this project doing?"

**Functional**
- [ ] Given I open a project, when the Overview loads, then I see: epics with completion and counts, the next release and its open work, unscheduled backlog, and the test health summary
- [ ] Given a percentage is shown, when I read it, then it says whether it is by count or by estimate
- [ ] Given the project has no releases yet, when I open the Overview, then the release section explains what a release is and offers to create one, and the epics section still works
- [ ] Given the project has no epics, when I open the Overview, then the epics section says so and offers to create one

### Story R3: The roadmap

**As a** Company Admin
**I want** to see releases on a timeline with their epics inside
**So that** I can see what ships when

**Functional**
- [ ] Given I open Work → Timeline, when it loads, then releases are laid out in version order with their epics spanning their start and target dates
- [ ] Given an epic has no dates, when I look at the timeline, then it appears in an "unscheduled" lane rather than disappearing
- [ ] Given I click an epic on the timeline, then I land on that epic

### Story R4: Release readiness

**As a** Test Lead
**I want** to see, for one version, whether every story is done *and* every linked test passes
**So that** "can we ship 2.4?" has one answer

**Functional**
- [ ] Given I open a release, when I look at it, then I see stories done vs total, and linked test cases passing vs total, for that version
- [ ] Given a story is done but one of its linked cases failed in the latest run, when I open the release, then the release is marked not ready and names that story
- [ ] Given no test run has been recorded against this version, when I open the release, then it says so rather than showing zero failures

---

## 7. Moving in from Jira

> **Flow:** an admin connects their Jira account → picks a project → sees a preview of what would
> come across and what would be dropped → maps Jira statuses to this project's workflow → confirms →
> gets a report they can return to later.

### Story J1: Preview before import

**As a** Company Admin
**I want** to see exactly what an import will and will not bring
**So that** I am not asked to trust a migration blind

**Functional**
- [ ] Given I connect Jira and pick a project, when I run the preview, then I see counts by type, every field that will be dropped with how many values it holds, and no data has been written
- [ ] Given the preview lists a dropped field, when I read it, then I understand it is not imported and cannot be recovered later from this product
- [ ] Given I cancel after the preview, then nothing has changed in my project

### Story J2: Map statuses

**As a** Company Admin
**I want** to say which of my project's statuses each Jira status becomes
**So that** the imported board is correct on arrival, not guessed

**Functional**
- [ ] Given the preview is done, when I proceed, then every Jira status is listed with a picker for the target status, pre-filled where the names match
- [ ] Given I leave a Jira status unmapped, when I try to confirm, then I am told which one is missing

### Story J3: Keys survive the move

**As a** Test Engineer who migrated from Jira
**I want** `PROJ-4812` to still be `PROJ-4812`
**So that** commit messages, PR titles and Slack history keep pointing at the right thing

**Functional**
- [ ] Given a Jira issue was `PROJ-4812`, when I search for that key after import, then I find it
- [ ] Given the import completes, when I open the import report later from the project's settings, then the same dropped-field list from the preview is there

**Open:** what becomes of Jira sprint membership (a label per sprint, or dropped and reported). See
the scope document §10.

---

## 8. Working with your own AI assistant

> **Flow:** a developer opts in on their profile → gets a token and a setup snippet → in their own
> tool (e.g. Claude Code) says "what's on my queue?" → the assistant fetches the task with its
> story, epic, acceptance criteria and spec → works in the developer's local checkout → opens a PR
> with the developer's own git → reports the PR link back → moves the task to In Review. A human
> closes it.

The product never runs code, never holds a repository, never calls a model. The assistant is the
developer's, on the developer's machine, and everything it does is recorded as *that developer, via
that assistant*.

### Story A1: Connect an assistant

**As a** Test Engineer
**I want** to connect my own AI assistant to the product from my profile
**So that** it can read my tasks and report back, using my identity and no more

**Functional**
- [ ] Given I open my profile, when I choose "Connect an AI assistant", then I can name it (e.g. `claude-code`), optionally limit it to one project, and receive a setup snippet
- [ ] Given I limited the assistant to one project, when it asks for another project's tasks, then it gets nothing from that project
- [ ] Given my own permissions are reduced later, when the assistant acts, then its reach shrinks immediately with mine
- [ ] Given I open my profile, when I look at connected assistants, then I see when each last did anything and can revoke it

### Story A2: Mark tasks for the assistant

**As a** Test Engineer
**I want** to flag which of my tasks are suitable for my assistant
**So that** it does not pick up work I intend to do by hand

**Functional**
- [ ] Given I open a task, when I toggle "AI-eligible", then the task shows a marker on cards and lists
- [ ] Given my assistant asks for its queue, then it receives only my AI-eligible tasks by default
- [ ] Given I filter the board by AI-eligible, then only flagged tasks are shown

### Story A3: The assistant gets the whole picture

**As a** Test Engineer
**I want** my assistant to receive a task with its story, epic, acceptance criteria, target release and linked spec
**So that** it works from the same context I would

**Functional**
- [ ] Given the assistant fetches a task, then the response includes the parent story, its acceptance criteria, the epic goal, the target release, the linked document excerpts, and the repository and build command the project declares
- [ ] Given the assistant fetches a task, then it also learns which status moves are allowed from the task's current state

### Story A4: The assistant reports back, a human closes

**As a** Project Manager
**I want** an assistant to be able to comment, attach a PR link and move a task to review — but never to done
**So that** a machine's work is always accepted by a person

**Functional**
- [ ] Given the assistant opened a PR, when it reports the link, then the task shows the PR link
- [ ] Given the assistant moves the task, then the only moves offered are those a Test Engineer could make, and "done" is refused with the reason
- [ ] Given I open the task's history, then each of the assistant's actions reads "<person>, via <assistant name>"
- [ ] Given the assistant's build failed, when it reports the failure, then the failure appears as a comment on the task

### Story A5: Let the assistant propose the breakdown

**As a** Test Engineer assigned a story
**I want** my assistant to propose sub-tasks that I accept, edit or reject
**So that** decomposition is fast but still mine

**Functional**
- [ ] Given the assistant proposes tasks for a story, when I open the story, then I see the proposal as pending, not as tasks
- [ ] Given a proposal is pending, when I accept some, edit one and reject the rest, then only the accepted and edited ones become tasks
- [ ] Given I reject a proposal, when I add a note, then the note is kept with the rejection

### Story A6: A one-person company can still close its own work

**As a** Company Admin who is also the only engineer
**I want** to be able to close a task my own assistant worked on
**So that** the review gate does not dead-end me

**Functional**
- [ ] Given a new project, when I look at its settings, then "Require review to close issues" is off
- [ ] Given the gate is off, when I move my assistant's task to done, then it is done
- [ ] Given a lead turns the gate on, when the author (via any assistant) tries to close, then a second person's approval is required

### Story A7: A new developer sees what to check out

**As a** Test Engineer joining a project
**I want** the project to list its repositories and how each is built
**So that** I can set up without asking

**Functional**
- [ ] Given I open project Settings → Repositories, then I see each repository's address, default branch, folder and build command
- [ ] Given a project has no repositories listed, then nothing else in the project is degraded — this is optional reference information

---

## 9. Time

> **Flow:** engineers log hours against issues (or against non-issue work) → submit a timesheet
> period → the PM approves → approved hours become the basis for utilisation and, later, invoices.

### Story T1: Log time

**As a** Test Engineer
**I want** to log hours against an issue, or against meetings and leave
**So that** my day is recorded where it happened

**Functional**
- [ ] Given I open an issue, when I choose "Log time", then I enter hours, a date and an optional note
- [ ] Given I open Timesheets, when I add an entry not tied to an issue, then I can pick a category such as meeting or leave
- [ ] Given I log time, then I never see what my hour costs or bills

### Story T2: Submit and approve

**As a** Project Manager
**I want** to approve my team's timesheets per period
**So that** only agreed hours reach billing

**Functional**
- [ ] Given a period ends, when an engineer submits, then it locks for them and appears in my approval queue
- [ ] Given I reject a timesheet with a note, then it unlocks for the engineer with the note visible
- [ ] Given I approve, then the hours show as approved on each issue and roll up to the story, epic and project

### Story T3: Estimate vs actual

**As a** Project Manager
**I want** to see estimated vs logged hours per story and epic
**So that** I can see where estimates drift

**Functional**
- [ ] Given a story has an estimate and logged time, when I open it, then both are shown side by side
- [ ] Given an epic's stories have both, when I open the epic, then the totals are shown and over-estimate stories are highlighted

---

## 10. Staffing (nice to have)

### Story S1: Allocate people to projects

**As a** Project Manager
**I want** to allocate a person to my project at a percentage for a date range
**So that** capacity and utilisation are answerable

**Functional**
- [ ] Given I open a project's staffing, when I add a person at 50% for March, then their profile shows the allocation and the company view shows them at 50% free
- [ ] Given a person is allocated over 100% across projects, when I look at the company staffing view, then they are flagged
- [ ] Given a person records absence, then their capacity for those days is zero everywhere

### Story S2: Skills and profiles

**As an** Account Manager
**I want** to see who has which skills and when they are free
**So that** I can answer a client's staffing question

**Functional**
- [ ] Given I open the directory, when I filter by a skill, then I see who has it and their allocation over the next months

---

## 11. Billing (nice to have)

> **Flow:** Finance sets rate cards → approved time is priced → Finance drafts an invoice per client
> per period → issues it → the client page shows invoiced vs budget.

### Story M1: Rate cards and budgets

**As a** Finance user
**I want** to set rates per role or person per client and a budget per project
**So that** every approved hour has a price and a ceiling

**Functional**
- [ ] Given I open a client, when I set a rate card, then each approved hour on that client's projects is priced by it
- [ ] Given I set a project budget in hours or money, when approved time is logged, then the project shows budget burn and the Overview shows it to the PM without showing rates

### Story M2: Invoice a client for time

**As a** Finance user
**I want** to draft an invoice from a client's approved hours for a period
**So that** billing is a report, not a spreadsheet

**Functional**
- [ ] Given I pick a client and a period, when I draft an invoice, then each line is approved time at the rate-card rate, and unapproved time is excluded and counted
- [ ] Given I issue the invoice, then the lines are locked and the time entries show which invoice they are on
- [ ] Given a Test Engineer or Account Manager opens Billing, then it is not there for them

**Out of scope**
- Fixed-fee and retainer invoicing
- Charging a client for AI-assistant usage — recorded as a cost, not invoiced

---

## 12. Reporting (nice to have)

### Story P1: Delivery analytics

**As a** Project Manager
**I want** release burn-up, scope change per release, cycle time and flow
**So that** I can see whether we are converging on a release

**Functional**
- [ ] Given a release, when I open its burn-up, then I see done vs total over time, and scope added after the release was created is distinguishable
- [ ] Given I open cycle time, then I see how long issues take from started to done, per type

**Out of scope**
- Velocity — there are no sprints to measure it over

---

## 13. Flow summary — a day in each role

**Project Manager, Monday.** Opens the project → Overview shows 2.4 is 70% by estimate, two epics
left, one story blocked, test health green. Opens Work → Board grouped by epic, collapses the done
ones. Ranks the backlog for 2.4. Approves last week's timesheets. Adds an epic for 2.5 and links it
to the spec.

**Developer (Test Engineer), Tuesday.** Opens My Work → three tasks and one review. Flags one task
AI-eligible, tells Claude Code "pick up my queue". It fetches the task with story, AC and spec,
works in the local checkout, opens a PR, posts the link and moves it to In Review. The developer
reviews the PR, then does the other two tasks by hand and logs four hours.

**QA (Test Engineer), Wednesday.** Opens the story under test → sees its linked cases and the
latest results. Runs the plan for 2.4. Opens the release → readiness now says one story's case
failed; files a bug under the epic from there. Epic completion drops from 80% to 75%, by count —
and says so.

**Company Admin of a one-person company, any day.** Opens the project → Overview with epics and the
next release; no client column anywhere, because there are no clients. Adds a quick task with one
field. Closes it when done — no reviewer needed.

**Account Manager, month end.** Opens Clients → Acme → three projects, hours to date, contacts.
Never sees a rate.

**Finance, month end.** Opens Billing → Acme, March → drafts the invoice from approved hours,
checks budget burn, issues it.

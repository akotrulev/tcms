# TCMS → Project Management — Module Scope & Architecture Decision

**Status:** Scoping complete. Decisions D1–D26 taken 2026-08-30; **D27–D34 (usability review) taken 2026-08-30**, see §13. One open question (§10).
**Scope of this document:** which modules exist, what each owns, which way the dependencies
point, and whether the deployment shape changes. **Not** a task plan — that follows per phase.

---

## 0. What this is for, in the owner's words

> *We have an instance — the deployed code. Then we have a company — that's one using the product.
> A company may have its own clients that contract them for some software delivery. So if I own a
> company and use this system for managing my projects, I want to know what project is tied to what
> client (a client can have multiple projects), and so I can manage the staffing of people to each
> project and then be able to bill the client for their time.*
>
> *Apart from that, on project level, I want to extend the tasks workflow, so I can create an epic,
> link it to a document/s, assign stories with tasks in each of them. Then the dev and QA team can
> work on them and mark the progress. Opening a project will reveal the roadmap, its completion %
> and a quick overview of what's left in terms of epics.*
>
> *The billing and staffing modules are a nice to have but are a lower priority.*

So the product stops *linking out* to Jira and starts *replacing* it. The existing tracker adapters
become the migration offramp rather than the integration.

**Single company throughout.** Multi-company support is untouched: no change to `CompanyContext`,
`CompanyAwareTransactionManager`, RLS policies, or the M6 activation backlog. Every table added
here carries `company_id` and a forced RLS policy like every other company-owned table
(`docs/specs/2026-07-30-tcms-erd.md` §0.1).

**The priority order is delivery-first.** Epics → stories → tasks → boards → roadmap is the
product. Staffing and billing are explicitly lower priority and are phased last. What §9 does
*not* do is drop them from the design: `client_id` and the time-entry shape have to be right in
phase 0, because retrofitting them across 30 tables later is the expensive version.

---

## 1. Decisions taken

### D1 — A Client is an attribute of a Project, not a level of the hierarchy

```
Instance                                  the deployment
 └ Company                                the organisation using the product — the isolation boundary
    ├ Client   (profile, contacts, rates) ← new: the company's own customer
    └ Project ──references──▶ Client      ← nullable FK; one client, many projects
       └ Epic → Story → Task
```

`client` is a company-owned lookup table. `project.client_id` is a **nullable** FK — one client has
many projects; a project has at most one client. Nullability is load-bearing: every project that
exists today has no client, and an internal project never will.

**A Client carries no isolation semantics and no permission scope.** It is not a third gate. The
two gates stay exactly as `docs/specs/2026-07-30-tcms-roles-permissions-naming.md` §A1 states
them — company filter, then project grant — and a client is visible iff you can see a project that
references it, or you hold `client:read` at company scope. Adding a scope here would have meant a
new `RoleAssignment` target, a fourth term in `evaluateCompanyWide`, and a second place
authorization is decided; the rollups this feature actually wants need none of that.

**A client never logs in.** Confirmed 2026-08-30: a client is a record describing an organisation,
not a principal — no `app_user`, no `company_membership`, no invitation, no authentication path.
This is what makes the scope-less design safe rather than merely cheap: the only reason a Client
would have needed to become a level of the hierarchy is to gate a login, and there is no login to
gate. `client_contact` is likewise contact information — a name, an email, a phone — and not an
identity. **If that ever changes, D1 is reopened, not extended**: a client portal is a second
authentication path and a real confidentiality boundary, and bolting it onto a lookup table is how
a tenancy model gets quietly broken.

**Consequence:** billing rolls projects up *by* `client_id` — client → its projects → their time
entries → an invoice. It does not scope by it.

#### A client is optional, and the product must be whole without one

The owner's worked example, and it is the common case rather than an edge:

> *I have my own company, I create a profile for it in TCMS. Given that I'm developing my own
> products, there is no client to bill as the client is me. So in that case I'd just need a way to
> manage the tasks. Now if I ever get an outside client that contracts me for some work, I'd want to
> track that, so I will create a client and a project for that client where I will track the work.*

Three rules follow, and each of them refuses a shortcut that would otherwise look tidy:

1. **`project.client_id` is nullable forever.** There is **no** synthetic "Internal" client row to
   make the FK non-null. That row would put a fake customer on every internal project, pollute every
   client rollup with a company billing itself, and make "which projects are contracted work?" —
   the question the column exists to answer — unanswerable.
2. **Zero clients is a supported, first-class state**, not an empty list to be filled in later. A
   company that never takes outside work never creates a client, and nothing in the product nags,
   blocks or degrades because of it.
3. **The client surfaces reveal themselves.** The Clients screen is always reachable, so the first
   one can be created; the client **column, filter and rollups** appear only once at least one
   client exists. A company managing its own products never sees a column that is empty by
   definition.

This is also why P0 in §9 ships **no client UI at all**.

### D2 — One canonical status per issue; boards map statuses onto columns

An issue has exactly one `status_id`, drawn from its project's status vocabulary. A **board** owns
its columns, and each column claims one or more of those statuses. Two boards over one project
show the same truth at different granularity — which is what "different ticket states for different
stakeholders" needs, without the state actually forking.

```
issue.status = IN_REVIEW                    ← single truth, one history, one cycle-time answer

Dev board     To Do │ In Progress │ In Review │ Done
Exec board    Not Started │      In Flight      │ Done
                          └ claims IN_PROGRESS + IN_REVIEW
```

Dragging a card to a column claiming more than one status sets that column's **default status**.
Per-board independent state was rejected: it makes "what state is this in" unanswerable and forces
every cycle-time, CFD and **completion-%** calculation to name a board before it can compute. Since
completion % is a headline number (D8), that rejection matters more than it did when it was made.

This mirrors a pattern the codebase already has: `case_status` is a per-project, ordered,
`kind`-categorised, `is_final`-flagged vocabulary. `issue_status` is the same shape, and `kind` is
what lets a report say "done" without knowing a project's local vocabulary.

**One vocabulary per project, shared by every issue type** (decided 2026-08-30). An Epic, a Story, a
Bug, a Spike and a Task all draw from the same ordered list, exactly as `case_status` works today.
One column mapping covers every type, completion % is computed the same way at every level, and
there is one thing to configure per project. The cost is accepted: an Epic can be moved to a status
like `IN_REVIEW` that was really meant for a Task. Per-type workflows would have keyed `issue_status`
on `(project, type)` and multiplied every board mapping by four.

### D3 — Three new built-in roles; still no custom roles

| Role | Scope | New | Intent |
|---|---|:--:|---|
| Instance Admin | SYSTEM | | unchanged |
| Company Admin | COMPANY | | unchanged — gains the new permissions by holding everything |
| **Account Manager** | COMPANY | ✅ | Clients, contacts, contracts. **No** `billing:*`. |
| **Finance** | COMPANY | ✅ | Rate cards, budgets, invoices, margin. Reads clients, writes money. |
| **Project Manager** | PROJECT | ✅ | Issues, boards, releases, backlog, roadmap, staffing allocation, time approval. **No** test-case approval. |
| Test Lead | PROJECT | | unchanged + `issue:*` |
| Test Engineer | PROJECT | | + ~~`issue:edit`, `time:log`~~ **`issue:create`, `issue:edit`, `time:log`** (D28). **Never** `billing:*` or `ratecard:read`. |
| Viewer / Automation Service / Agent | | | + `issue:read`, `board:read` |

The "six immutable roles" decision is **amended, not reversed**: nine built-in roles, still seeded,
still immutable, still no clone-and-edit. `role` has no `company_id` (it is on the RLS exception
list) and `uq_role_name` is global, so these are three seeded rows in a migration.

The roles spec, `docs/specs/permission-matrix-summary.md` and `README.md` all need the amendment
in the same commit — `packages/client/src/test/matrixCopies.test.ts` compares them and will fail
otherwise. Write it in the struck-through-and-superseded style the working agreement requires; the
"six are the complete set" sentence has already been superseded twice and this is the third.

**A note on "the dev and QA team".** There is no new role for a developer. A developer is a
Test Engineer whose day is issues rather than cases, or a Project Manager. Adding a Developer role
would need a permission it holds that no existing role does, and there isn't one — the difference
is which screens they open, not what they may do.

### D4 — It stays one deployable. No microservices.

Confirmed. The reasoning, specific to this codebase rather than generic:

1. **Tenancy is enforced in-process.** `CompanyAwareTransactionManager` hooks `doBegin` to set both
   the Hibernate filter and the `app.current_company` GUC; `CompanyContext` is a deliberately
   non-inheritable `ThreadLocal` exposed only as a scope guard; RLS with `FORCE ROW LEVEL SECURITY`
   is the backstop. Every process boundary re-implements all three, with its own `tcms_app` pool,
   and multiplies the two documented silent-RLS traps by the number of services. The failure mode
   is **zero rows, fail-closed, silent** — the worst possible thing to distribute.
2. **This data is aggressively relational.** An invoice line → a time entry → an issue → a person
   → a project → a client. Completion %, margin, utilisation and burn-up are exactly those joins.
   A service split turns the reporting this feature exists for into N+1 over HTTP.
3. **One migration, one database, 87 tables.** There is no seam to cut without abandoning V1's
   premise, and V1 only ever runs on an empty database.
4. **"One deployable" is a product property.** This is self-hosted software an operator installs.
   Shipping a mesh is a regression in what is being sold.
1. **There is no scale problem.** One instance, a handful of companies, a handful of projects each.

The modulith already *is* the preparation: Spring Modulith packages, ArchUnit-enforced dependency
rules, and `CompanyScopedEvent` as the async seam. If anything ever needs independent scaling it
will be report rendering or ingestion — extract *that worker*, not the domain.

**The real risk is module sprawl, not scale.** This roughly doubles the backend. §3 is the
mitigation.

**This decision is about the domain, not about every process.** D4 refuses splitting company-owned
domain data across services. It does not refuse a worker with a genuinely different runtime — its
own wording is *extract that worker, not the domain*. §11's agent orchestrator is the first thing in
this product's history that actually qualifies, and D9 records why it is an exception rather than a
crack in this one.

### D5 — Issue keys are per-project

`ACME-1`, `PORTAL-1` — each project has its own prefix and its own counter, Jira-style.

This costs a change to `key_sequence`'s primary key, which is `(company_id, entity_type)` today, to
add `project_id`. And it makes issues **the one key family in the product that is not
company-scoped**: cases, suites, plans, runs and doc pages all draw from a single per-company
counter. That inconsistency is real and is accepted deliberately.

**Why it is worth it:** the P3 importer can preserve a client's existing Jira keys. A team migrating
in has `PROJ-4812` in commit messages, PR titles, branch names, Slack history and their own
documentation — none of which this product can rewrite. Renumbering to `MYL-77` breaks every one of
those references permanently, on the day you are asking them to trust the new tool.

`docs/specs/2026-07-30-tcms-erd.md` §366 explains the current key design and needs the amendment
recording this, in the superseded style.

### D6 — Releases, not sprints

**No `sprint` table, no `sprint_issue`, no sprint planning screen, no velocity.** The only planning
cadence is the **release**, identified by a version. `issue.release_id` is a nullable FK; a story or
a task either has a target release or it does not.

The board is a continuous flow ordered by fractional rank, optionally filtered to a release. The
roadmap is releases on a timeline with their epics inside. "What's left" is the open issues in the
next release.

#### This is not a new concept — it is three loose ones finally given a table

The schema already half-implements releases in three places, none of which know about each other:

| Where | What it is today |
|---|---|
| `test_run.release_version TEXT` | Free text, collected by `RunCreateDialog`, filtered by exact string match everywhere |
| `test_run.release_sort_key TEXT` | Computed on write by `execution.ReleaseSortKey` — `%05d.%05d.%05d.<precedence>` so `1.10` sorts after `1.9` — propagated into `execution_fact`, and **read by nothing** |
| `test_plan.milestone_name TEXT` + `milestone_date DATE` | Free text and a date, denormalised onto the plan |

Plus an unbuilt Phase 3 backlog item, *"Milestones & release readiness — gate view per milestone"*
(`docs/features.md:371`), which is this.

So a `release` table is the structured form of a concept the product has been carrying as strings
since V1. Three consequences worth stating before someone rediscovers them:

1. **`release_sort_key` gets its first reader.** It is currently listed in
   `ArchitectureRulesTest.READERLESS_BY_DECISION` — built, correct, deliberately consumed by
   nothing. A roadmap ordered by version consumes it, and the entry must come **out** of that list
   in the same commit or the rule fails. This is a pleasant outcome: the hard part (semver ordering
   that does not silently answer about a release from three months ago) is already written and
   tested.
2. **Every existing release filter is an exact match on `release_version` text.** Introducing
   `release.id` does not get to break them. `release` is keyed on `(company_id, project_id, version)`
   and `test_run` gains a nullable `release_id` alongside the text column it keeps — the text stays
   the ingestion-friendly form, because CI passes a string and always will.
3. **`test_plan.milestone_name`/`milestone_date` are superseded but not dropped in P2.** Migrating
   them is a data question, not a schema one — see §10.

#### What this buys that sprints would not

A release is the one object both halves of this product care about. Delivery asks *are the stories
for 2.4 done?*; QA already asks *did the runs for 2.4 pass?* — and `execution_fact` has carried
`release_version` the whole time. **Release readiness** — every story closed *and* every test passing
for one version — is a screen neither Jira nor a test tool can produce alone, and it falls out of
joining two things that already exist.

Velocity and sprint burndown are dropped from §9's analytics phase. **Release burn-up**, scope change
per release, cycle time and CFD replace them, and none of those need a sprint to compute over.

### D7 — ~~The QA dashboard keeps the project landing; the roadmap is a tab~~ → superseded by D27

> **Superseded 2026-08-30 by D27.** Opening a project shows one overview carrying delivery *and* QA health; the roadmap is a view of it, not a tab beside a dashboard. The original text is kept below for the reasoning it records.

`/projects/:projectId` keeps rendering `DashboardPage` unchanged. The roadmap lives at
`/projects/:projectId/roadmap`.

Nothing existing moves and no current user is surprised by a changed landing page. The cost is
stated plainly rather than hidden: the roadmap — which §0 describes as what opening a project should
reveal — is one click behind the QA dashboard. If that reads wrong in use, promoting it later is a
route change and a redirect, not a rebuild, and the decision can be revisited cheaply once there is
a real roadmap to look at.

### D8 — Completion % is story-level count by status kind, with an hours override

Counted over **story-level issues** — Story and Bug — not tasks, not epics, and **not spikes**:

- Default: story-level issues whose `issue_status.kind` is terminal, over the total in the epic.
- Override: when **every** counted issue in the epic carries an estimate, switch to
  estimate-weighted.
- **Spikes are excluded from both numerator and denominator.** A spike produces knowledge, not
  shippable work (§4); counting it would let an epic read 40% done having shipped nothing. Spikes
  still consume hours and so still count toward budget burn — the two numbers answer different
  questions and are allowed to disagree.
- The page **states which basis it used**. Always.

**Estimates are in hours** (decided 2026-08-30), not story points. The reason is P5 and P7: hours are
directly comparable to `time_entry` actuals, so estimate-vs-actual and budget burn work with no
conversion — which is what matters on contracted client work, where the estimate and the invoice
have to be the same unit.

**The known cost, recorded rather than discovered later:** hours read as commitments and get quoted
back at you, and D6 removed sprints, so there is **no velocity to recalibrate them against**. An
epic's completion % will move in hours that were guessed once. Two mitigations, neither of them
clever: the page always names its basis, so an hours-based number is never mistaken for a measured
one; and P5's actuals make the drift visible per story, which is the feedback loop a velocity figure
would otherwise have provided.

Rationale for the shape: it fails honestly. An unestimated epic gets a coarser number rather than a
wrong one, and nobody is forced to estimate for a number to exist. It reuses the `kind` column D2
already requires, so no new vocabulary is introduced.

**No partial credit for in-progress work.** A number that moves before anything ships is the number
people learn to distrust, and once distrusted it is decoration.

**An Epic's status is derived from its stories, not edited (D31).** The number and the status cannot disagree, and the count (`4/7`) is shown beside the %.

**One number, one place.** Completion % is computed in `reporting` and read everywhere; it is never
recomputed in a component. `lib/results.ts` and `DataRegion` exist because this repo has already
shipped two contradictory percentages on one screen.

### D9 — Mission Control's engine ports in as a Node worker; its run model does not

Assessed 2026-08-30 against `~/RiderProjects/mission-control`. §11 is the capability inventory. The
decision:

- The **agentic-friendly** half is already largely built and gets extended in P1–P2 at almost no
  cost (`@tcms/mcp`, `@tcms/cli`, the `Agent` role). That is the cheap 80%.
- The **agentic-driven** half takes Mission Control's *engine* — dispatch, worktrees, streaming,
  verify, budget, crash-safe resume — and **discards its run/plan/phase/stream model**, which D10
  replaces with something the tracker already expresses.
- It runs as a **second container in the same compose stack**, sharing the one Postgres, with its
  rows carrying `company_id` under the same RLS. One product, one install, one database, two
  runtimes. This is the D4 exception, not a reversal.

### D10 — An agent is an assignee. Agent execution is triggered by assignment, not by a run.

**A Story is assigned to a person. That person breaks it into Tasks. A Task assigned to an agent is
attempted by the agent.** There is no run object, no plan to paste, no phase, no stream, no separate
orchestration screen.

This is the decision that makes agentic execution affordable, and it is a *smaller* feature than the
port it replaces. Four mechanisms it needs are already in the schema:

#### 1. An agent is an `app_user` with `kind = 'AGENT'`

`app_user.password_hash` is already nullable and `status` already carries `DEACTIVATED`, so a
non-login principal is not a new idea here. Adding
`kind TEXT NOT NULL DEFAULT 'HUMAN' CHECK (kind IN ('HUMAN','AGENT'))` buys the whole existing
apparatus at once: `assignee_id` needs no polymorphism, the org chart and grantee lookup already
resolve it, role assignments and `api_token` already attach to it, and the `Agent` role
(`V40`, Test Engineer minus `company:read`) already exists to be granted to it.

`kind` gates it out of people pickers, invitations, the directory and email notification — and
filtering a principal out of a picker is an established idiom in this codebase, not a new one:
`role.assignable = false` and `Company Member`'s exclusion from `GET /roles` are the same move.

**The alternative — a separate `agent` table and a polymorphic `assignee_type`/`assignee_id`** — was
rejected. It duplicates the org chart, the notification path, the audit trail and every assignee
join, to model something that is a *kind* of principal rather than a different one.

#### 2. `ItemAssignedEvent` is already the trigger, and already has a consumer

`work.ItemAssignedEvent` exists, carries `(eventId, itemId, title, projectId, assigneeId)`, and is
already consumed by `integration.ItemAssignedNotifier`. **The orchestrator is a second subscriber to
an event the product already publishes.** Its own javadoc anticipates the reassignment call site:
*"The name stays correct when the field edit lands: that slice adds a second call site here, not a
second event type."*

`eventId` is documented as the consumer's idempotency key against at-least-once delivery — which is
exactly what stops a redelivery from dispatching an agent twice and spending twice.

#### 3. The closing gate already exists, and it is the right one

**An agent must never move its own task to a terminal status.** That rule is already built:
`project_settings.author_may_approve_own_work` defaults to `false`, beside `required_peer_reviews`
and `review_enabled_case/suite/plan/doc`. P2 adds `review_enabled_issue` to that row — **defaulting to `false` (D33)** — and the
existing review machinery — `review`, `review_assignment`, `review_participation`, `review_comment`
— is the gate.

So Mission Control's third gate is not ported. It is a flag on a table that has enforced this
property for other artifact kinds since V1.

#### 4. `ai_proposal` is the result shape, already designed for exactly this

An agent's output is a proposal a human accepts or rejects. `ai_proposal` already carries
`payload JSONB`, a `verdict` of `GROUNDED · PARTIAL · HALLUCINATED · UNVERIFIED`, `grounding JSONB`,
a status of `PENDING · ACCEPTED · REJECTED`, `decided_by` / `decided_at` / `decision_note`, and
`accepted_case_id` naming what it became — plus **`created_token_id`**, which records the credential
the AI acted under. A task result is the same record with a different accepted target.

#### 5. Authority: an agent borrows the assigner's

The agent acts under a token **owned by the person who assigned the task to it**. This extends an
invariant the product already enforces and documents — *a token never grants more than its owner
has; its reach is its owner's permissions intersected with the token's role* — so:

- Every agent action traces to a human who chose to delegate it.
- **Demoting that person immediately shrinks what their agents can do**, with no separate revocation.
- An agent assigned work by a Viewer can do almost nothing, which is correct.

Accountability is the point. "The agent did it" is never an answer to who authorised it.

### D11 — The cross-project boards stay composite; issues become a fourth card kind

**Corrects §12, which assumed the issue board was the only board.** It is not. Three surfaces exist
today — `/projects/:projectId/board`, `/my-work` and `/teams/:teamId/board`, the last two
cross-project — and `work.BoardFacts` composes **derived** cards from three sources beside stored
tasks: outstanding executions, `case_health` flags, and pending reviews.

That composite is the product's answer to *"what is on my plate"*, and it spans delivery and QA.
Replacing it with an issues-only board would have narrowed the one screen that already works.

- `/my-work` and `/teams/:teamId/board` **stay composite.** `BoardFacts` gains a generator; it does
  not retire. An issue is a fourth card kind beside `EXECUTION`, `CASE_MAINTENANCE` and `REVIEW`.
- `/projects/:projectId/board` becomes **issue-first**, with the derived kinds available as a filter
  rather than removed.
- **`derived_item_annotation` survives and matters more.** Per-user note, personal due date and
  flagged — personal metadata on a card you do not own — is exactly right for a board mixing your
  issues with reviews someone else asked of you.

`WorkItem`'s own javadoc already states the shape this preserves: the wire's `kind` spans six values,
five of which are derived, and *"a task somebody typed"* is **the only kind of board card that is a
row**. D11 keeps that true, with `issue` as the row instead of `work_item`.

### D12 — Existing `work_item` rows migrate as parentless Stories

Determined rather than chosen, once the code was read: **`work_item.kind` is written exactly once,
always `"TASK"`** (`WorkItem.java:170`), and `WorkItemWriteRequest` deliberately carries **no `kind`
field** — a client cannot set one, because doing so *"would let a client POST `EXECUTION` and so
materialise a derived card"*. So every existing row is a parentless TASK, and **no user ever chose
that word.**

Under §4 a Task now requires a story-level parent and these have none, so they migrate to the story
level as **Stories**, keeping title, description, status, priority, due date, assignee and audit
columns. Relaxing the depth rule to permit orphan Tasks was rejected: it would apply to every future
task, not just to migrated rows, and it would weaken the one rule that makes Epic → Story → Task mean
anything — to accommodate a label the user never picked.

The migration's comments must say this, per V1's convention.

### D13 — Releases and epics are both project-scoped

A `release` is keyed on `(company_id, project_id, version)`; an epic belongs to one project. Neither
crosses a project boundary.

**The cost is accepted and is real:** a company shipping version 2.4 from a web project and an API
project keeps two `release` rows named `2.4`, gets two roadmaps and two completion percentages, and
**cannot ask "is 2.4 ready?" across both.** Release readiness (D6) answers per project.

Chosen because the alternative pushes a cross-project visibility filter into every roadmap,
completion and board query — a caller may hold a grant on one of the two projects and not the other,
and a company-scoped release would have to render a number computed partly from rows that caller
cannot see. That is the §A1 boundary, and a roadmap is not a good enough reason to blur it.

If the portfolio case becomes real, D1 makes the **client** the natural owner of a cross-project
roadmap, not the company — and that is an additive read over `project.client_id`, not a re-key.

### D14 — No backfill. Releases start empty.

`test_run.release_version`, `test_run.release_sort_key` and `test_plan.milestone_name` /
`milestone_date` all keep working exactly as they do. Nothing is migrated into `release`.

The consequence to expect on day one: **the roadmap is empty and release readiness has no history**
until someone creates releases by hand, and P3's Jira import lands into an empty release set. That is
the trade for having no migration to get wrong — milestone names are prose and version strings across
a real instance are not guaranteed to be as clean as they look.

**`release_sort_key` still gets its first reader** (D6) — it just reads rows created going forward
rather than backfilled ones. The `READERLESS_BY_DECISION` entry still comes off the list in P2.

### D15 — P3 imports standard fields only; custom-field values are dropped

A Jira project's custom-field values are **not** imported. The importer produces a report naming
every field it dropped and how many values that was.

This is a deliberate clean break from accumulated Jira cruft, and the cost is stated plainly: it is
**irreversible loss at the moment a team is being asked to trust the migration**, and "Story Points"
or "Team" may be load-bearing for them in a way the standard fields do not cover.

**D32 adds a preview and a mapping step** before anything is written; see §13.

**One mitigation that does not reverse the decision:** the import report is **persisted as an
`artifact`**, not merely displayed and lost on navigation. The `artifact` table and its storage port
already exist. So the record of what was dropped survives the import session, which costs nothing and
is the difference between a clean break and an unexplainable gap six months later.

### D16 — A project has many repositories, each with its own verify command

A new `project_repository` carries, per row: repository URL, default branch, **working
subdirectory**, and **its own verify command**. A Task names the repository entry it works in; the
agent's worktree and verify are scoped to that entry.

This is modelled on the case in front of us. TCMS is one git repository containing three stacks with
three separate gates — `./gradlew build` in `backend/`, `npm run build` in `frontend/`, `npm run
build` in `packages/` — and its own CLAUDE.md states that **no stack builds from the repo root**. A
single verify command per project would have to wrap all three in a shell script that returns one
exit code, and a failure would say "the build broke" rather than which stack.

The same shape covers the other common case without bending: a project whose frontend and backend
live in two different git repositories is two rows with different URLs instead of two rows with the
same URL and different subdirectories.

**A project with no repository rows is normal** — agent execution is opt-in per project, and a
project that never runs an agent never needs one.

### D17 — The server dispatches; a paired runner on your own machine executes

TCMS never checks out a repository, never holds a git write credential, and never executes a build.
It dispatches a task to a **runner** installed on hardware you control — a developer machine, or the
lab host — which does the work locally and streams events back.

**This is the single largest reduction in risk in the whole plan.** The question it answers was
*"how do we run arbitrary build commands for many companies without one company reaching another's
source?"*, and the answer is that the server never has the source at all. Company isolation stops
being a property of correct path handling in application code and becomes a property of *different
people's machines*. A path bug on the server can no longer leak a client's code, because the code was
never there.

Three things it needs, none of them free:

1. **A runner protocol** — dispatch, event stream, result, and heartbeat.
2. **Pairing** — a runner is registered to a company and authorised for specific projects.
3. **Honest offline handling.** The server **cannot guarantee a task ever runs.** A task assigned to
   an agent with no reachable runner must say so on the card — *waiting for a runner*, not
   *in progress*. Silently sitting in a queue looking busy is the failure mode to design against, and
   it is the same rule §11's inventory takes from Mission Control: *every idle card says why it is
   idle*.

**Two things already point this way.** Mission Control's plan 008 — *remote dispatch: a runner
service with a resumable event stream, per-dispatch credentials that never touch the remote disk,
tree sync out and back, and recovery from all three ways the connection can fail* — is this design,
with phases 1–2 already built. And this repo already works this way by hand: the standing directive
is that the gates run on `shanks` via `~/bin/tcms-remote`, not locally
(`docs/specs/shanks-runner-details.md`).

### D18 — Model credentials are per person, and travel with the dispatch

The Claude Agent SDK credential belongs to the **person who assigned the task**, encrypted with the
existing `integration.SecretCipher`.

This mirrors `tracker_credential` exactly — documented as *"D2's inversion — the credential belongs
to the user, not the project"* — and it completes D10's authority chain rather than adding a second,
differently-shaped one: **an agent borrows the assigner's permissions and the assigner's model
credential.** One person, one delegation, one bill.

**With D17 this needs care, and Mission Control already names the rule:** the runner may be on a
different machine from the assigner, so the credential is a **per-dispatch secret that never touches
the runner's disk** — handed over for the run, held in the subprocess environment behind an
allowlist, and gone when the task ends. `SecretRedaction` already exists to keep it out of logs.

**Two accepted costs:**

- **No company-level cap or rollup.** The per-task budget ceiling is therefore the *only* brake, which
  makes it non-optional rather than a nice-to-have.
- **TCMS does not pay for the tokens, so agent spend is not automatically a client-billable line.**
  It is still *recorded* per task and still rolls up to project and client for reporting — a cost you
  can see is worth having even when it is not a cost you invoice. P7 decides whether it becomes one.

### D19 — One retry carrying the reason, then a human

A task rejected by the verify command or by the reviewer is re-dispatched **once**, carrying the
verify output or the reviewer's own words as context. A second failure moves the task to a `BLOCKED`
status and notifies the assigner.

Two attempts, bounded spend. The reasoning behind the bound: a second *identical* failure is usually
evidence the task was specified wrong, not that the agent needed another go — and with D18 putting no
company-level cap in place, an unbounded retry loop is the shape that burns a whole ceiling on
something that was never going to work and reports it an hour later.

The rejection reason travelling with the re-dispatch is the part that matters. Mission Control does
this and it is why its second attempts succeed at all.

### D20 — The agent opens a pull request

The agent commits to `tcms/<issueKey>` and opens a PR against the repository's default branch. The PR
URL is stored on the issue.

**This closes git traceability's unbuilt half.** `docs/features.md` lists PR linkage as the missing
piece — *"the run half is implemented… Not built: PR linkage and case↔requirement linking"* — and
`test_run` already carries `commit_sha`, `branch`, `build_number` and `release_version` with nothing
but a human typing into `RunCreateDialog` to fill them. An agent that opens a PR produces all of it.

It also means **agent output goes through the review tooling the team already uses** rather than
through a second review surface invented inside TCMS. The D10 gate
(`author_may_approve_own_work = false`) still governs whether the *task* closes; the PR is where the
diff is actually read.

**What this costs:** a forge integration per provider. `integration.tracker` already carries adapters
for Jira Cloud, Jira DC and Azure DevOps behind `TrackerProvider`, so the shape exists — but GitHub
and GitLab are named in `docs/features.md` as **not built**, and a PR API is not an issue API. P4
needs at least one forge adapter, and which one is a P4 scoping question, not a P4 surprise.

### D21 — A Story may be assigned to an agent; the agent proposes Tasks and a human accepts

**This reverses the recommendation in D10's open question**, and the reversal is deliberate: rather
than refusing the assignment, an agent given a Story **decomposes it into proposed Tasks**, a human
accepts, edits or rejects them, and the agent then works the accepted ones.

The propose-then-accept gate is what makes this safe rather than a planner smuggled back in. It is
also a shape the schema already has: `ai_proposal` carries `payload JSONB`, a
`GROUNDED · PARTIAL · HALLUCINATED · UNVERIFIED` verdict, `PENDING · ACCEPTED · REJECTED`,
`decided_by` / `decision_note`, `created_token_id` and an `accepted_*_id` naming what it became.

**Two consequences to plan for, not discover:**

1. **P4 grows a planner.** Mission Control's planner comes back — reading the Story, its acceptance
   criteria and its linked spec blocks (§4) — which is a real increase in P4's scope over D10's
   original shape. It is a *better* planner input than MC has today, but it is not free.
2. **`ai_proposal` lives in `repository`** (it proposes test cases) and §3 forbids `tracker` from
   importing it. `tracker` gets its **own** `issue_proposal` of the same shape rather than a shared
   table — the same "a shared row is not a shared class" reasoning `BoardFacts` records.

**A human still owns decomposition as a decision.** The agent drafts it; nobody is obliged to take
the draft.

### D22 — The agent authors; the assigner is co-author

```
Author: TCMS Agent <agent@tcms.local>

    Fix rounding in refund totals

    Co-Authored-By: A. Kotrulev <…>
```

`git blame` truthfully says a machine wrote the line, and the trailer records who authorised it — so
D10's accountability chain survives *outside* the product, in a repository someone reads without
TCMS in front of them. This repo's own CLAUDE.md already requires a `Co-Authored-By` trailer on
commits, so the convention is not new here.

### D23 — P4 ships GitHub and Azure DevOps forge adapters

Two adapters for D20's pull-request API.

**Azure DevOps is cheaper than it looks**: plan 004 already built its tracker binding, its
*per-person* credential and its connection check, so the auth and plumbing exist and only the PR API
is new. **GitHub is the one that gets dogfooded** — this repo is on GitHub, `gh` is already the
documented CLI here, and CI is GitHub Actions, so P4 can prove itself on its own source on day one.

GitLab and Bitbucket wait for demand. `TrackerProvider` is the extension point and adding a third is
additive.

### D24 — P7 bills time and materials only

An invoice line is approved time entries at a rate-card rate. No milestone invoicing, no retainer
drawdown, no running balance per client.

This keeps the chain already designed in §2 and §6 — `time_entry → rate_card_entry → invoice_line` —
as the *only* path, which is also why D8's estimates are in hours: the estimate and the invoice are
the same unit, so budget burn means one thing.

Fixed fee and retainer are the two obvious extensions and both are additive: a fixed-fee line is
milestone-triggered rather than hours-derived, and a retainer adds a balance. Neither changes the
T&M path, so deferring costs no rework.

### D25 — The local LLM drives TCMS through MCP/CLI. TCMS dispatches nothing.

**Decided 2026-08-30, after D17–D24, and it supersedes most of them.** This is a *pull* model, not a
push model, and it is a large simplification.

```
Developer's machine                          TCMS server
──────────────────────                       ───────────
Claude Code / local LLM
  │
  ├─ MCP ──────── "what tasks are assigned to my agent?" ──▶ issue list
  ├─ MCP ──────── "give me ACME-45" ───────────────────────▶ task + AC + spec
  │                                                            blocks + parent
  │                                                            story + epic
  ├─ works in the local checkout        (TCMS is not involved)
  ├─ opens the PR with local gh/git     (TCMS is not involved)
  │
  ├─ MCP ──────── "PR is at <url>" ────────────────────────▶ issue.pr_url
  └─ MCP ──────── "move to In Review" ─────────────────────▶ status transition
```

**The premises, all of which come from the deployment being what it already is:**

- Repositories are **already checked out locally**, and git and forge authentication are **already in
  the local environment**. TCMS handles neither, and does not need to.
- The model credential is the developer's own, in their own environment. **TCMS never calls a model.**
- A user opts in by configuring their profile, issuing a token, and setting up the MCP server. Their
  MCP config holds, *locally*, which TCMS project this working tree belongs to and which repositories
  it spans.

**The D10 gate is unchanged and is what keeps this honest.** The agent moves the task to a review
status; it cannot close it, because `author_may_approve_own_work = false` and the review gate is
server-side. The agent's authority is still its owner's permissions ∩ the `Agent` role — which
already holds 23 permissions and already includes `doc:edit`, so this is the surface that role was
designed for.

#### What this supersedes

| | Status |
|---|---|
| ~~**D17** — a paired runner service on your own machine, with a dispatch protocol, registration, heartbeat and offline handling~~ | → **superseded.** There is no runner and no dispatch. The developer's own agent asks TCMS for work when *they* choose. The security property D17 was reaching for — the server never holds source or git credentials — is achieved more completely here, by TCMS not being in the execution path at all. |
| ~~**D18** — per-person model credentials in `SecretCipher`, handed over as a per-dispatch secret that never touches the runner's disk~~ | → **superseded.** TCMS stores **no model credential**, because it never invokes a model. The entire class of problem disappears rather than being solved. |
| ~~**D19** — one retry carrying the rejection reason, then `BLOCKED`~~ | → **superseded as a server behaviour.** TCMS does not dispatch, so it does not retry. A failed verify is something the local agent sees immediately and the developer decides about. The *reason* still lands on the issue as a comment, which was the valuable half. |
| ~~**D20/D23** — TCMS opens the pull request through GitHub and Azure DevOps forge adapters~~ | → **superseded in mechanism, preserved in outcome.** The **local agent** opens the PR with the credentials already in its environment, and reports the URL back. `issue.pr_url` is still stored, so **git traceability's unbuilt half still closes** — with no forge adapter, no provider-specific code, and no OAuth app to register. Reading PR *status* later may still want an adapter; creating one does not. |
| **D9** — the orchestrator as a second container in the compose stack | → **withdrawn.** There is no orchestrator process. **D4's "one deployable" now holds with no exception at all**, which is a better outcome than the exception D4 was careful to carve out. |
| **D16** — `project_repository` with per-row verify commands | → **amended, not dropped.** See below. |
| **D21** — an agent may decompose a Story into proposed Tasks | → **unchanged**, and now simpler: the local LLM proposes, `issue_proposal` records, a human accepts. No server-side planner exists or is needed. |
| **D22** — agent authors, assigner co-authors | → **unchanged.** The local agent writes the trailer. |

#### D16, amended: the server knows *which* repositories, the client knows *where*

The split falls out naturally and is worth stating so nobody puts a local path in the database:

| Lives in TCMS (`project_repository`) | Lives in the local MCP config |
|---|---|
| Repository URL, default branch, working subdirectory, verify command | The absolute path that repository is checked out at *on this machine* |
| Reference data — *"this project spans these three repositories, and here is how each is built"* | Machine-specific mapping — different for every developer |

That keeps `project_repository` useful for the reason a human wants it (a new developer can see what
to check out and how to build it) without TCMS ever holding a path it cannot verify, and without a
row that is wrong on every machine but one.

#### What P4 actually is now

Not an orchestrator. Five things, and the first four are small:

1. **`app_user.kind = 'AGENT'`** and agent principals — D10, unchanged.
2. **Issue tools on the MCP and CLI surfaces** — list ~~tasks assigned to my agent~~ **my tasks flagged `agent_eligible` (D30)**, fetch one with its
   acceptance criteria, parent story, epic, target release and linked spec blocks, transition its
   status, comment, attach a PR URL, propose tasks (D21). `packages/mcp/src` already has
   `reads.ts` / `writes.ts` / `tools.ts` and `docs/specs/2026-08-08-mcp-tool-and-command-surface.md`
   already governs the shape — this is an extension of a designed surface, not a new one.
3. **`issue.pr_url`**, plus exposing the legal-transition set so the agent knows which moves exist —
   the same payload §12.4 already requires for the board's drag rule.
4. **A profile screen** — opt into LLM use, issue the token, show the MCP setup snippet.
5. **`project_repository`** as reference data, per the amendment above.

**No `packages/orchestrator`. No second container. No runner. No forge adapter. No model credential.
No budget ceiling in TCMS.** P4 stops being the largest phase in the plan and becomes one of the
smaller ones.

### D26 — There is no agent principal. The API token is the identity.

**Decided 2026-08-30. Withdraws `app_user.kind = 'AGENT'` from D10** and closes §10's last question
by rejecting all three of its options.

A user already issues **multiple named API tokens**. That mechanism is the whole answer, and every
column it needs already exists:

| Column | What it gives us |
|---|---|
| `api_token.name` | *Which* agent — `claude-code`, `laptop`, `ci-agent` |
| `api_token.owner_id` | *Whose* — the person accountable |
| `api_token.role_id` | The `Agent` role, already seeded, already 23 permissions |
| `api_token.project_id` | Optional scope to **one project** |
| `api_token.last_used_at` | When that agent last did anything |
| `audit_event.actor_user_id` **and** `actor_token_id` | Every act records the person *and* the credential |

So "who worked on this task" resolves to **"Alex, via `claude-code`"** — and it resolves that way
today, for every act in the product, with nothing built.

#### This restores the roles spec rather than amending it

`docs/specs/2026-07-30-tcms-roles-permissions-naming.md` already states, of the `Agent` role:

> **nobody is ever *an Agent*** — it is a property of a credential, not of a person.

D10 proposed making it a principal and contradicted that sentence. D26 withdraws the proposal. The
`Agent` role stays exactly what `V40` made it: **a capability set a credential carries**, issuable
only at the token-issue form, never grantable to a person. `role.assignable = false` continues to
mean what it means, and no migration touches `app_user`.

#### What "assigned to an agent" means, then

**Assignment stays person → person. There is no polymorphic assignee and no second assignment
target.** A task is assigned to a human being, as tasks always have been; that person then points
their local LLM at their own queue. Delegation is an act the assignee performs on their machine, not
a state stored in TCMS.

Two properties fall out of this that a separate agent principal would have had to reinvent:

1. **The token's project scope is already the containment boundary.** A token issued for one project
   cannot read or write another. A developer who wants their LLM nowhere near a client's project
   issues a token scoped to the one it should touch — and that is enforced server-side, by machinery
   that predates this plan.
2. **Demotion still shrinks reach immediately**, because a token's authority is its owner's
   permissions ∩ the token's role. D10's accountability chain survives intact with no new entity
   under it.

~~**Deferred, and only if a lead asks for it:**~~ → **taken into P4 by D30:** a lightweight `agent_eligible` hint on an issue, so a
lead can *signal* "this one is a good candidate for an LLM". That is a boolean and a filter, not an
assignment target, and nothing in P4 needs it.

#### D22, restated without a principal

Git authorship needs no TCMS row. The commit reads:

```
Author: TCMS Agent (claude-code) <agent@tcms.local>

    Fix rounding in refund totals

    Co-Authored-By: A. Kotrulev <…>
```

The author line carries the **token's name**, so `git blame` says which agent wrote it; the trailer
carries the **owner**, so the accountability chain is readable outside the product. D22's two
properties are unchanged — only the thing supplying the name is.

#### P4 gets smaller again

`app_user.kind`, the agent-principal migration and the owner FK all come out. P4 is now: **the issue
tool surface on MCP and CLI, `issue.pr_url`, the legal-transition set, `project_repository` as
reference data, `review_enabled_issue`, and a profile screen that issues a token and shows the MCP
setup snippet.** Its migration is a handful of columns and one small table.

---

## 2. Module map

### New modules

| Module | Owns | Priority |
|---|---|---|
| `tracker` | Issues (Epic/Story/Task/Bug), the status vocabulary and workflow, links, comments, backlog rank, **releases**, **boards**, **the roadmap** | **P1–P2 — the product** |
| `crm` | `client`, `client_contact`, contract/engagement terms | P0 — small, and `client_id` must exist early |
| `time` | Time entries, timesheet periods, submission and approval | P5 |
| `staffing` | Person profile beyond auth (skills, capacity, cost rate), allocations, absence | P6 — nice to have |
| `billing` | Rate cards, budgets, invoices + lines, expenses, margin | P7 — nice to have |

All five depend on **foundation modules only**. §3 explains why that is not optional and what it costs.

### Existing modules that change

| Module | Change |
|---|---|
| `work` | **Retired.** `work_item` migrates into `tracker`'s `issue` as kind `TASK`; `work_item_link` becomes `issue_link`. `workitem:manage` is superseded by the `issue:*` family. 14 files and one permission out of 56 — it is a placeholder, not a foundation. |
| `documentation` | `doc_link.source_type`'s CHECK gains `'ISSUE'`. That is the whole backend change for epic↔document — see §4. |
| `execution` | `test_run` gains a nullable `release_id` beside its existing `release_version` text. `ReleaseSortKey` is unchanged and finally read. |
| `reporting` | New report types: completion, release burn-up, release readiness, cycle time, CFD; later utilisation and budget burn. Existing renderer/capability machinery is reused as-is. |
| `integration` | The Jira/ADO adapters gain an **importer** direction. Do not delete them — a client mid-migration needs both. |
| `project` | `project.client_id` (nullable FK). The only foundation-module change. |
| `identity` | Three seeded roles and their grants. No structural change. |

### `board` and `roadmap` are sub-packages of `tracker`, not modules

A board is meaningless without the status vocabulary it maps, and D2 makes that vocabulary the
issue's single truth. The roadmap is a read over epics and releases. Splitting either would put a
projection on the far side of an event boundary from the thing it projects. `tracker.board` and
`tracker.roadmap` start as packages; promote only on a genuinely independent lifecycle.

---

## 3. The dependency problem, and why there is no sixth foundation module

`ArchitectureRules.FOUNDATION_MODULES` is `{api, tenancy, identity, project, storage}` and its
javadoc states the bar a sixth must clear. Two clauses are decisive:

> *it holds no domain concept* … *and it answers a question the database cannot, which is the test
> the other three pass and a feature module proposing itself will not*

A `tracker` module holds the largest domain concept in the product. It fails both clauses outright.
**There is no sixth foundation module.** §76 already refused a `review` module on this bar, and the
sentence stands: *a foundation module is a decision, not an import.*

That is a real constraint, because in a PM system nearly everything points at an issue — a time
entry, an invoice line, a board card, a release membership, a document link. So:

**Downstream modules reference an issue polymorphically and keep their own projection.** Every
piece of this already exists in the codebase; none of it is new machinery:

| Existing thing | What it establishes |
|---|---|
| `work_item_link (target_type, target_id)`, no FK | polymorphic reference across a module boundary |
| `doc_link.source_type CHECK ('TEST_CASE','SUITE','DEFECT','DOC_PAGE')` | the authority table enumerates its source kinds; the source module is not imported |
| `case_doc_link` — `page_key`, `page_title`, `block_excerpt`, `stale` | the consuming module keeps a denormalised projection of the link, with staleness tracked |
| `execution_fact`, `suite_run_summary`, `work.BoardFacts` | event-fed read projections that `reporting` joins without importing the writer |
| `HealthRecomputeOnLinksChanged`, `tenancy.OutboxEvents` | the event plumbing that keeps a projection current |

So `time_entry` carries `subject_type` + `subject_id` — an issue, a meeting, PTO; timesheets cover
work that is not an issue anyway, which makes this the honest model rather than a workaround. And
`tracker` publishes a `CompanyScopedEvent` maintaining an `issue_fact` row — id, key, title, type,
status kind, epic, release, project, estimate — for `reporting` and `billing` to join.

**What this costs, stated plainly:** referential integrity between a time entry and its issue is
enforced in application code, not by the database. A deleted issue can orphan a time entry. The
mitigation is soft-delete (`issue` inherits `work_item`'s `deleted_at`) plus a consistency check in
the nightly suite. This is the price of the module rule, and `work_item_link`, `doc_link` and
`execution_fact` already pay it.

**A trap to avoid:** `issue_fact` must be fed by an event, never written by `time` or `billing`.
The moment a downstream module writes it, `tracker` is no longer the authority on issue state and
the two disagree silently.

---

## 4. The delivery hierarchy

### Three levels, several types, one table

```
EPIC     Epic                    a body of work with a goal, dates, a target release, spec links
  └ STORY  Story │ Bug │ Spike   the workable unit — three types, one level
      └ TASK   Task              the dev/QA breakdown inside it
```

One `issue` table with a self-referencing `parent_id` and an `issue_type` FK.

**The depth rule is expressed in levels, not type names.** `issue_type` carries a
`level TEXT CHECK (level IN ('EPIC','STORY','TASK'))`, and the rule is *a type may only parent to a
type one level above it*. Story, Bug and Spike all sit at `STORY`, so all three hold Tasks and all
three may sit under an Epic.

This matters more than it looks. A rule written against type *names* — "Task parents to Story" —
would have to be edited in code the day a fourth story-level type is wanted. Written against levels,
**a new type is a row**: adding `Chore` or `Tech Debt` at `STORY` needs no migration and no service
change. It is the same reasoning `issue_status` follows in D2 by categorising on `kind` rather than
on a status's name.

**Two parents are optional, one is not:**

| | Rule |
|---|---|
| Task → story-level parent | **Required.** A Task is a breakdown *of* something. **D29:** the *workable* unit for a solo or internal project is a story-level type also named `Task`, so the depth rule never forces a Story to exist first. |
| Story/Bug/Spike → Epic | **Optional.** Not all work belongs to an epic, and requiring one would force a junk epic per stray bug. |
| Epic → anything | Never. Epics are top level. |

**This is deliberately not Jira's shape.** Jira is Epic → Story → Sub-task, where a Sub-task is a
second-class citizen that cannot be estimated or moved independently. Here a Task is a first-class
issue that happens to have a parent — so a dev and a QA can each hold a Task under one Story, which
is the "dev and QA team work on them and mark the progress" case.

**Spike is not deliverable scope, and D8 has to say so.** A spike is a time-boxed investigation that
produces knowledge, not shippable work. It consumes hours and therefore budget, so it counts for
burn — but counting it toward an epic's completion % would let an epic read 40% done having shipped
nothing. See D8's amendment.

### Epic ↔ documents — one CHECK constraint and a projection

`doc_link` is already the authority for "this thing is linked to that documentation block", with a
polymorphic `source_type` and no FK on `source_id`, and it already supports external Git ranges and
external URLs alongside doc blocks. Epic↔document is:

1. `doc_link.source_type`'s CHECK gains `'ISSUE'`. That is the entire `documentation` change.
2. `tracker` gets `issue_doc_link` — its own projection, mirroring `case_doc_link` field for field,
   including **`stale`**. Staleness is the interesting half: an epic linked to a spec block should
   say so when the spec moves underneath it.
3. `HealthRecomputeOnLinksChanged` is the template for the event that maintains it.

`relationship` already has `('VERIFIES','DERIVED_FROM','REFERENCES')`. An epic derived from a spec
is `DERIVED_FROM`; there is probably no new relationship needed.

### The QA link is the differentiator, and it is nearly free

This product already owns test cases, suites, plans, runs and executions. A Story linked to its test
cases means the roadmap can answer *"is this done, and is it proven?"* — the one question Jira
structurally cannot answer without a plugin, and the reason to build this rather than buy it.
`issue_link` with a target type of `TEST_CASE` costs one enum member; `execution_fact` already
carries the results, keyed by the same `release_version` D6 gives a table to.

Together with D6 this is **release readiness**: for version 2.4, every story closed and every test
passing. **Recommended for P2**, not deferred to the analytics phase.

---

## 5. The roadmap surface

Per D7 this is `/projects/:projectId/roadmap`, not the project landing page. It carries three things:

- **Roadmap** — releases on a timeline, ordered by `release_sort_key`, each showing its epics.
  Epic dates come from `issue.start_date` / `issue.target_date`.
- **Completion %** — per D8, per epic and per release, always stating its basis.
- **What's left** — epics ordered by remaining work, each with story counts by status kind, and the
  open issues in the next release.

---

## 6. Data model sketch

~30 new tables. Every one gets `company_id UUID NOT NULL` leading every composite index, and a
forced RLS policy. `SchemaConventionTest` enforces this — but per the working agreement, **the check
for a catalogue-wide invariant is computed, not read**: reading V1 top to bottom once missed five
join tables without `company_id`.

| Module | Tables |
|---|---|
| `crm` | `client`, `client_contact` |
| `tracker` | `issue`, `issue_type` (carries `level`: EPIC·STORY·TASK), `issue_status`, `issue_transition`, `issue_link`, `issue_comment`, `issue_watcher`, `issue_doc_link`, `release`, `issue_fact` |
| `tracker.board` | `board`, `board_column`, `board_column_status`, `board_swimlane`, `issue_label`, `issue_label_assignment` |
| `time` | `time_entry`, `timesheet_period` |
| `staffing` | `person_profile`, `skill`, `person_skill`, `allocation`, `absence` |
| `billing` | `rate_card`, `rate_card_entry`, `budget`, `invoice`, `invoice_line`, `expense` |

No `sprint`, no `sprint_issue`, no `issue_release` join table — per D6 a release is a nullable FK on
`issue`, not a many-to-many.

Reuse rather than duplicate: `attachment` for issue attachments, `audit_event` for issue history,
`outbox` for the events, `doc_link` as the link authority, `ReleaseSortKey` for version ordering.

**`key_sequence` needs a primary-key change, not just a row.** Per D5 issue keys are per-project, so
`(company_id, entity_type)` becomes `(company_id, entity_type, project_id)` with `project_id`
nullable for the five existing company-scoped types. Its `entity_type` CHECK also gains `'ISSUE'`.

---

## 7. Permissions

~35 new permissions across `client:*`, `issue:*`, `board:*`, `release:*`, `time:*`, `staffing:*`,
`billing:*`, `ratecard:*`. The catalogue goes from 56 to roughly 91.

Two rules from the working agreement apply directly:

- Derive "holds every permission" from the catalogue. There is no `'*'` sentinel and must not be one.
- `tierClaims.test.ts` fails if a **comment** claims somebody lacks a permission §A3 says they hold.
  Every comment written about the old six-role model is a candidate failure.

The sharpest new boundary is **money**: `billing:*` and `ratecard:read` are held by Finance and
Company Admin and nobody else. A Test Engineer with `time:log` logs hours and cannot see what they
cost. That needs a test asserting the negative, not a careful reading of the matrix.

---

## 8. The cross-cutting tax

Itemised rather than assumed, because this is where an expansion of this size actually fails:

| Surface | Work |
|---|---|
| `docs/specs/openapi.yaml` | 16,326 lines today; expect ~+55%. **Every new schema needs its `required` array, including on inline nested objects** — `required` does not descend. |
| Generated clients | **Two** copies: `frontend/src/api/schema.d.ts` and `packages/client/src/schema.ts`. Regenerate both, in the same commit as the contract change. |
| `contractBodies.ts` | The marked region is byte-identical across both copies and pinned by `contractPairing.test.ts`. Edit one, edit both. |
| Frontend | ~22–30 new screens on top of 42, all on the render kit — no `sx`, no CSS-in-JS, no second stylesheet. New enums go in `lib/labels.ts` as `satisfies Record<Union, string>`. |
| Frontend data layer | One `keys` factory in `src/api/queries/keys.ts`. **Never add a second.** Timeout budget per endpoint class. |
| Board drag-drop | The single hardest piece of frontend in the plan. `frontend/src/features/board/cardMove.ts` and `boardKeyboard.ts` exist and are the starting point, but a Trello-grade board is optimistic reordering on a fractional rank, WIP limits, swimlanes and keyboard parity. |
| `READERLESS_BY_DECISION` | `release_sort_key` comes off the list in the same commit that first reads it (D6), or `ArchitectureRulesTest` fails. |
| CLI | 27 commands today. `issue`, `board`, `release` verbs are the obvious additions. |
| MCP | `docs/specs/2026-08-08-mcp-tool-and-command-surface.md` needs the new capability surface. An agent that can file and move a story is a large part of this product's point. |
| Roles docs | Spec + `permission-matrix-summary.md` + `README.md`, all three in one commit. |
| ERD | §366's key-design rationale needs the D5 amendment, in the superseded style. |
| `docs/features.md` | The Work Items section is replaced wholesale; the Phase 3 "Milestones & release readiness" line is closed by D6. |
| Migrations | `out-of-order` is unset and Testcontainers cannot see a numbering conflict. **Versions are pre-assigned in §9** — see `docs/specs/parallel-work-rules.md`. |

---

## 9. Phasing

> ~~**The horizontal cut below — schema and roles first, screens several phases later — is how this
> ships.**~~ → **superseded 2026-08-30 by
> [`docs/plans/implemented/009-project-management-expansion-modules.md`](../plans/implemented/009-project-management-expansion-modules.md),
> which cuts the same work vertically:** each module lands its own migration, endpoints and screens
> and is usable the day it merges. The reasoning below is kept rather than deleted, because it was
> not wrong — it optimised for schema that is cheap to add and expensive to retrofit, and one
> module (`M0`) still exists for exactly that reason. What it did not survive is its own
> consequence: `P0` shows nobody anything, and `P1` delivers a tracker with no board.
>
> **Two things in this section outlive the supersession and are still authoritative:** the
> must / should / nice-to-have **priority** column, and the rule that **migration versions must be
> pre-assigned because `out-of-order` is unset and Testcontainers cannot see a numbering conflict.**
> Plan 009 restates the second as a reservation that must be re-checked whenever the order moves.
>
> ~~One claim is withdrawn outright:~~ that the **client tables** must land in `P0`.
> `project.client_id` is one nullable column on one existing table; adding it later is a two-line
> migration, not a retrofit across 28 tables. The client tables move to `Mc`, which is deferrable.

Sequential. `docs/specs/parallel-work-rules.md` and the working agreement both say work is
sequential, and parallel lanes have been reversed four times in this repo. Migration versions are
pre-assigned so no two phases collide.

| Phase | Migration | Delivers | Priority |
|---|---|---|---|
| **P0 — Foundations, no UI** | `V2` | `client` + `client_contact` tables, `project.client_id`, the three roles, the full permission catalogue, `key_sequence` gaining `project_id` and `'ISSUE'`, `doc_link.source_type` gaining `'ISSUE'`. Contract + both generated clients + the three role docs + the ERD amendment. **Nothing user-visible.** | must |
| **Pc — Clients** | — | Client CRUD, contacts, the client column and filter on projects. **Deferrable.** Schema landed in P0, so this is screens and endpoints only, and it is worth building the week a first real client exists — not before. | when needed |
| **P1 — Tracker core** | `V3` | `issue` + `issue_type` + `issue_status` + workflow, Epic→Story→Task, `issue_link`, `issue_comment`, `issue_doc_link` + staleness, per-project issue keys. **Migrates `work_item` in and retires the `work` module.** | must |
| **P2 — Boards, releases, roadmap** | `V4` | Boards with column→status claims, swimlanes, WIP limits, fractional-rank ordering; backlog; `release` + `test_run.release_id`; the roadmap tab with completion %; story↔test-case links and release readiness. | must |
| **P3 — Jira offramp** | `V5` | Importer from Jira Cloud/DC and Azure DevOps, reusing the existing adapters and per-person credentials. Preserves original issue keys (D5). **Dry-run preview and a status-mapping step before commit (D32).** | must |
| **P4 — Agent execution** | `V6` | `project_repository` as reference data, `issue.pr_url`, **`issue.agent_eligible` (D30)**, `review_enabled_issue`, the legal-transition set on the wire, the issue tool surface on MCP + CLI, and a profile screen that issues a token and shows the MCP snippet. **No orchestrator, no runner, no forge adapter, no model credential, no agent principal** — D25, D26. | high — the differentiator, and the smallest phase that carries one |
| **P5 — Time** | `V7` | `time_entry`, `timesheet_period`, submit/approve, `issue_fact` and the event that feeds it. Agent task spend rolls up here too. | should |
| **P6 — Staffing** | `V8` | Profiles, skills, capacity, allocations, absence. Utilisation becomes answerable. | nice to have |
| **P7 — Billing** | `V9` | Rate cards, budgets, invoices, expenses, margin, client rollup. | nice to have |
| **P8 — PM analytics** | `V10` | Release burn-up, scope change per release, cycle time, CFD; later utilisation and budget burn. **No velocity** — D6 removed the sprint it would measure. | nice to have |

**P4 depends only on P1 and may be pulled forward.** D25 removed everything in it that needed a
board — an LLM reads a task and moves a status, and neither requires a column layout. If agentic
work matters more than boards do, P4 can run immediately after P1, with its migration version
travelling with it. Its listed position assumes boards first, not a hard dependency.

**P0 ships no screens on purpose.** Its schema is cheap now and expensive to retrofit across 28
tables later, but a company developing its own products has no client to enter, so the client CRUD
would be built for nobody. The column and the roles land; the UI waits for a reason. **P0–P2 are the
product**, and P3 sits above the rest because an importer is worth little once a backlog has been
hand-keyed.

---

## 10. Open questions

**One, from D32:** where Jira sprint membership lands on import — a project label per sprint, or dropped and
reported like custom fields. Everything else is closed: **D1–D34 close every question this scoping raised**, across P0 through P8. The last one — how
many agent principals and owned by whom — was closed by **rejecting its premise**: D26 uses the
named API tokens a user can already issue, so no principal exists to count.

What remains is not a question list but a set of stated deferrals, each additive and each costing no
rework if taken later:

| Deferred | Where it would land | Why deferring is safe |
|---|---|---|
| Custom fields as a real system | Its own phase | D15 drops them on import and keeps the report as an artifact, so nothing is silently lost. |
| Cross-project releases and epics | A read over `project.client_id` | D13 keeps both project-scoped; D1 makes the client the natural owner if the portfolio case ever becomes real. |
| Backfilling historical release strings | Any time | D14 leaves `test_run.release_version` and `test_plan.milestone_name` working untouched. |
| Fixed-fee and retainer billing | P7+ | D24's T&M path is unchanged by either. |
| GitLab / Bitbucket forge adapters | On demand | D23 ships two; `TrackerProvider` is the extension point. |
| Company-level agent spend caps | With P7 | D18 makes the per-task ceiling the only brake, so that ceiling is non-optional. |
| A client portal | Reopens D1 | D1 states explicitly that this is a reopening, not an extension. |

**The three things most likely to prove wrong in use**, flagged so they are watched rather than
defended:

1. ~~**D7** — the roadmap sits behind the QA dashboard~~ → closed by D27; the watch item is now whether one
   overview page can carry both delivery and QA health without becoming a wall.
2. **D8** — hours estimates with no velocity to recalibrate them (its own text records this).
3. **D13** — two `release` rows named `2.4` in a company that ships one version from two projects.
---

## 11. Mission Control — what it has, and where each piece lands

Assessed 2026-08-30 against `~/RiderProjects/mission-control`. Next.js 15, better-sqlite3,
`@anthropic-ai/claude-agent-sdk`, `@modelcontextprotocol/sdk`, dnd-kit, Radix + Tailwind + shadcn +
sonner + lucide. 178 files, 36,236 LOC — of which **23,323 is `src/lib`, the engine**, densely
unit-tested and independent of Next except for `db.ts` and `config.ts`.

> **Superseded in large part by D25 (same day).** This section was written when the plan was to port
> Mission Control's execution engine into TCMS. D25 removed the need: the local LLM drives TCMS
> through MCP, so **no engine is ported, no orchestrator process exists, and `packages/orchestrator`
> is not built.** What survives is §11.2's capability mapping — read as *what TCMS already has that
> Mission Control had to invent*, which is why so little of it needed porting — and §11.5's loop,
> which D25 does not change. §11.3's layer table and §11.4's subtraction list are kept for the record
> and are no longer the plan.
>
> **Mission Control's own role changes rather than ending**: it is a local agent driver, so under
> D25 it is a natural **client** of TCMS's MCP surface, alongside Claude Code. It consumes the
> product rather than being absorbed into it.

### 11.1 Separate the two asks first

| | What it means | Where it stands |
|---|---|---|
| **Agentic-friendly** | An agent can *drive TCMS* — read a project, file a story, move a card, record a result | **Largely built.** `@tcms/mcp` (stdio + `--http`), `@tcms/cli` (27 commands), the `Agent` role, and a token whose reach is *its owner's permissions ∩ the token's role*. P1–P2 add `issue`/`board`/`release` verbs — already itemised in §8. |
| **Agentic-driven** | TCMS *runs the agents that do the work* — plans, dispatches, verifies, reviews, commits | **This is Mission Control.** P8. |

The first is nearly free and ships with P1–P2. Keeping them separate stops the tracker phases from
looking twice as expensive as they are.

### 11.2 The capability inventory, and what each one becomes here

| Mission Control has | In TCMS it becomes | Fit |
|---|---|---|
| **Planner: prose in, task graph out** — reads the plan *and* the target repo, emits phases, tasks, per-task agent + skills, dependencies, target files | Takes a **Story** instead of pasted prose — with its acceptance criteria, linked spec blocks (§4), epic and target release already attached | **Better input than it has today.** This is the single strongest argument for the merge. |
| **Open questions block the run** — planner asks with concrete options; answers become decisions handed to every implementer | An `issue_status` of kind `BLOCKED` with the question on the issue. The answer is a comment that unblocks it, and it is on the board where a human sees it | Native. `issue_status.kind` (D2) already carries the vocabulary. |
| **Three gates before `done`** — implementer claims success · project verify command builds · reviewer judges against acceptance criteria; a rejection re-dispatches with the reviewer's own words, twice | **TCMS already owns two of the three.** `review`/`review_assignment`/`review_participation`/`review_comment` is gate 3; a test run over the story's linked cases (§4) is a stronger gate 2 than "it builds" | **The deepest convergence in the whole assessment.** MC invented a review model; TCMS has one, with approvals, participation and audit. |
| **Worktree isolation** — per-run git worktree on `mc/<runId>`, each finished task commits its own files | Fills the **unbuilt half of git traceability**. `test_run` already carries `commit_sha`, `branch`, `build_number`, `release_version` and nothing produces them but a human typing into `RunCreateDialog`. A run *emits* exactly those four | Slots into an existing hole. `docs/features.md` lists PR linkage as the missing half. |
| **Budget ceiling per run** — $25 default, spend on the board, pause at the next task boundary | The same number `billing`'s `budget` table holds (P7) — but per **task**, per D10. Agent spend is a cost against a project, and where the project has a client, against a client budget | Converges with P7 rather than duplicating it. |
| **Bounded parallelism** — phases ordered, streams within a phase, `MAX_PARALLEL`, early-finishing lane starts immediately, two tasks touching the same file never run concurrently | Ports as-is | Nothing in TCMS competes with it. |
| **Swimlane board** — band per phase, lane per stream, live tool-activity streaming, per-task summary, Pause/Resume/Cancel/Retry/Skip, every idle card says *why*, "Up next" names what starts when a slot frees | **P2's board gets swimlanes from working code**, and the "say why, don't just sit there" rule is a design law worth adopting product-wide | See §12. |
| **Kanban view where the drop is the action** — and **most drops are refused on purpose**: the scheduler owns Backlog/Ready/In progress, and the board *says so* rather than accepting the drag and reverting it | Directly transferable to P2, where `issue_transition` decides which drops are legal | See §12.4. This is the best single idea in the codebase. |
| **Project lessons** — settled tasks write a decisions log; a model pass turns it into lessons injected into every later implementer prompt, plus project context for the planner; lessons visible and individually deletable | A project-scoped knowledge store. `documentation` (`doc_space`/`doc_page`/`doc_block`) is the obvious home, and §4's `doc_link` already ties blocks to issues | Reuses a module rather than adding one. |
| **`HANDOFF.md` export** | A report type in `reporting`, beside the existing HTML/JSON/Markdown renderers | Free — `ReportFormat.MARKDOWN` already exists. |
| **Encrypted per-project credentials**, never visible to agents; subprocess env is an allowlist | `integration.SecretCipher`, `SecretRedaction` and `OutboundUrlGuard` already do this, and `tracker_credential` already models a per-person credential | Existing machinery; drop MC's and use it. |
| **Headless control** — `/api/v1` bearer contract + an MCP server exposing six tools | Merges into `@tcms/mcp` and `@tcms/cli` rather than standing beside them | One agent surface, not two. |
| **Crash-safe persistence** — an interrupted task is requeued; a running run comes back **paused, never silently dispatching** | Postgres instead of SQLite; the *semantic* is the thing to keep | A resume that silently starts spending money is the failure this prevents. |
| **Plan validation before Start** — agent/skill names checked against the indexed catalog with did-you-mean | Stays as-is; the catalog is the external SkillsLibrary and remains external | No TCMS concept needed. |
| **Remote build over SSH**, per project and opt-in | `docs/specs/shanks-runner-details.md` describes the same host and the same practice, by hand | Already how this repo works. |
| **Execution switch per project** · **launchd/container/caffeinate** | Operational; ports with the engine | — |

### 11.3 Shape of the port

| Layer | Files | LOC | Verdict |
|---|---:|---:|---|
| `src/lib` — engine | 88 | 23,323 | **Ports close to as-is** into `packages/orchestrator`, beside `@tcms/client`, `@tcms/cli` and `@tcms/mcp`. The Node workspace already exists. |
| `src/components` | 63 | 9,972 | **Reskin, not rewrite** — already Radix + Tailwind + shadcn + sonner + lucide, which is TCMS's exact kit. Retokenise onto `index.css` custom properties and the nine-component kit. |
| `src/app` — Next routes/pages | 26 | 2,893 | The only Next-coupled part, and the smallest. Route handlers become the orchestrator's HTTP surface; pages become SPA routes. |

**It also brings dnd-kit and a working swimlane board — which P2 needs anyway and TCMS does not have.**
That is a reason to look at `src/components` during P2 rather than waiting for P8.

The engine runs as a **second container in the same compose stack**, sharing the one Postgres, rows
carrying `company_id` under the same RLS. The Agent SDK is Node, the engine spawns processes, manages
worktrees on a filesystem and streams for hours — none of which is a Spring request. Per D9 and the
D4 amendment this is the different-runtime exception D4 allows, not a domain split: compose already
runs Postgres, Keycloak and the backend, so this is a fourth service, not a mesh.

Two things genuinely have to be built rather than ported: **company scope** (there is no `userId`,
`tenant` or `companyId` anywhere in `src/lib` — it is a one-operator loopback tool) and **SQLite →
Postgres**. Both are independent of every PM phase and can start whenever.

### 11.4 What D10 removes from the port — most of it

D10 replaces Mission Control's orchestration model with the tracker's own. What drops out is the
larger half, and none of it is a compromise: each piece is replaced by something the product already
expresses better.

| Mission Control has | D10 replaces it with |
|---|---|
| **Planner — prose in, task graph out** | A person breaking a Story into Tasks. Later, optionally, agent-*proposed* tasks through `ai_proposal`, which already models propose-then-accept. |
| **Plan validation before Start** (agent/skill names, did-you-mean) | Assigning a task to an agent principal that exists. There is no plan to validate. |
| **Open questions block the run** | An `issue_status` of kind `BLOCKED` with the question on the issue, visible on the board where a human already looks. |
| **Run · phase · stream model** | Story → Tasks, ordered, with `issue_link` carrying `BLOCKS`. The dependency graph is the one the human drew. |
| **Bounded-parallelism scheduler, `MAX_PARALLEL`, up-next panel** | A dispatch limit per project. Ordering is the task graph's job. |
| **Swimlane run board, Lanes\|Board toggle** | The project board (§12). An agent's task is a card like any other; live tool activity renders **on the card**. One board, not two. |
| **Run-level Pause/Resume/Cancel/Retry/Skip** | Task-level actions on the card. |
| **Three gates, reviewer sub-agent, re-dispatch twice** | `project_settings.author_may_approve_own_work` + `required_peer_reviews` + the existing `review` module (D10 §3). |
| **`HANDOFF.md` export** | A `reporting` report type. Nice to have, not phase-critical. |

**What still has to be ported, and it is the engineering half:**

- **Worktree isolation** — now per task rather than per run. `git worktree add` on a
  `tcms/<issueKey>` branch, commits attributed to the agent principal.
- **Dispatch and live streaming** of tool activity, rendered onto the card.
- **The verify command** per project — the gate that says the code builds.
- **The budget ceiling** — now per task, rolled up to story, epic and project, converging with P7's
  `budget` table rather than duplicating it.
- **Crash-safe resume**, keeping the semantic that matters: an interrupted task is requeued and a
  running one comes back **paused, never silently dispatching**. Money makes that non-negotiable.
- **File-overlap safety.** Two agents on two tasks in one repository is still real, and the task
  graph does not solve it. MC's declared-target-files check is the mechanism to keep.
- **Credentials** — but using `integration.SecretCipher`, `SecretRedaction` and `OutboundUrlGuard`
  rather than MC's own, with the subprocess env staying an allowlist.

Call it roughly a third of `src/lib` rather than all of it — and the third that has no equivalent
here, rather than the two thirds that do.

**One new piece of configuration** the tracker has no home for yet: a project's **repository, default
branch, verify command and optional build host**. `project_settings` already exists and is the place;
MC's `projects.json` is the field list to copy.

### 11.5 The loop this closes

```
Story  ──assigned to a person──▶  Tasks
                                    │
                    ┌───────────────┴───────────────┐
              assigned to a human            assigned to an agent
                    │                               │
                    │                     worktree · verify · stream
                    └───────────────┬───────────────┘
                                    ▼
                        review gate (author_may_approve_own_work = false)
                                    ▼
                        Story done ──▶ linked test cases (§4) ──▶ executions
                                    ▼
                        release readiness (D6): can 2.4 ship?
```

A story is planned, an agent implements it, the verify gate proves it builds, the linked test cases
prove it works, and release readiness says whether 2.4 can ship. **No other tool owns all four sides
of that loop** — Jira owns the story, a test tool owns the cases, CI owns the build, an agent runner
owns the work. That convergence is the argument for building this rather than buying it.

---

## 12. The board, and what to take from Trello

Trello's structure is **Workspace → Board → List → Card**. Lists are ordered left to right and *are*
the workflow stages; a card lives in exactly one list on exactly one board and moves rightward as it
progresses. Cards carry members, labels, due dates, checklists, attachments and a cover. WIP is
limited per list. Filtering spans keyword, members, card status, due date, labels and activity.
Swimlanes are **not** native — they need a power-up. Table, Calendar and Timeline are alternative
views of the same board.

### 12.1 The one thing not to copy

**In Trello a board is a container; in TCMS a board is a lens.**

Trello's card lives on one board, in one list, and the list is its state. That is the model D2
rejected — and it is rejected precisely because it cannot do what §0 asks for. Two stakeholder boards
in Trello means two copies of the work.

> **A TCMS board is a saved view over the project's issues: a filter, a grouping, and a mapping from
> statuses to columns.** An issue appears on every board whose filter matches it, at the column
> claiming its status. Delete a board and no work is lost, because a board never owned any.

Everything else below is Trello's ergonomics, which are excellent, sitting on that different spine.

### 12.2 What to take

| From Trello | Into P2 |
|---|---|
| **Lists left→right as the progression** | Columns are ordered and the order means direction of travel. `board_column.position`. |
| **Labels — ~10 named colours, many per card, the primary scanning device** | **`issue_label` — this was missing from §6 and is now added.** Project-scoped, named, coloured, many-to-many with issues. Not a classification dimension: cases have `classification_dimension`/`case_tag` for taxonomy, and labels are deliberately the informal, fast-moving axis. |
| **Filter semantics: OR within a category, AND across categories** | Exactly this, and it is worth copying precisely because it is learnable. Filter state lives in the URL via `useSearchParams`, as everywhere else in this SPA. |
| **A dense card face** — labels, members, due date, checklist progress, attachment count, all at a glance | Design the card face deliberately rather than letting it accrete. Add issue key, type icon and estimate; that is a full card. |
| **WIP limits per list** | `board_column.wip_limit`, with the column showing the breach rather than blocking the drop. |
| **One dataset, several views** | Board · **Table** · **Timeline** · Calendar. Nearly free here: TanStack Table is already the table, D6's roadmap is already the timeline, and all four are one query with a different renderer. **D34: one sidebar entry, views toggle in-page.** |

### 12.3 What to do better than Trello

- **Swimlanes are native**, not a power-up: group rows by epic, assignee, label, release or priority.
  Mission Control has working swimlane code (§11.2) and dnd-kit under it.
- **A card can be on many boards**, per 12.1 — the whole point of the multi-stakeholder ask.
- **Columns claim statuses**, so an exec board's one column can cover three engineering states
  without inventing a parallel vocabulary.
- **Hierarchy on the board.** Trello has no epic. Group by epic, collapse an epic to a roll-up card
  with its completion % (D8), and expand it in place.

### 12.4 The drag rule, from Mission Control

Mission Control's kanban refuses most drops **on purpose**, and its board *says why* rather than
accepting the drag and snapping the card back. Adopt that verbatim:

> A drop that `issue_transition` forbids is **refused at the drag, with the reason on screen**. It is
> never accepted optimistically and reverted.

An accept-then-revert reads as a bug every time, and it teaches people to distrust the board. This
also means the legal-transition set has to be known client-side before the drag starts — so the board
payload carries it, and that is a contract decision belonging to P2's `openapi.yaml` work, not an
afterthought.

Sources: [Trello — boards, lists and cards](https://support.atlassian.com/trello/docs/creating-a-new-board/) ·
[Filtering for cards on a board](https://support.atlassian.com/trello/docs/filtering-for-cards-on-a-board/) ·
[What is a kanban board? (Atlassian)](https://www.atlassian.com/agile/kanban/boards) ·
[Swimlanes in Trello](https://community.atlassian.com/forums/Trello-articles/Mastering-Swimlanes-in-Trello-A-Guide-to-Organizing-Lists-in/ba-p/2605767)

---

## 13. Usability review — D27–D34

Taken 2026-08-30 after a flow-level read of D1–D26. Each amends a decision above in place; this
section records the reason.

### D27 — One project overview is the landing page. Supersedes D7.

`/projects/:projectId` renders a single overview: epics with completion % and counts, the next
release and what is left in it, unscheduled backlog, *and* the QA health the dashboard shows today.
The roadmap/timeline is a **view** of that page (D34), not a tab beside a dashboard.

**Why:** §0 says opening a project reveals the roadmap; D7 put it one click behind a screen answering
the same question — *how is this project doing?* — from the QA side only. Two pages for one question
is the smell, not the click. The existing `DashboardPage` regions are reused as the QA half.

### D28 — Test Engineer holds `issue:create`.

D3 gave Test Engineer `issue:edit` only and `issue:*` to Test Lead. D3 also says a developer *is* a
Test Engineer, and D10/D26 say the assignee breaks a Story into Tasks. A developer who cannot create
an issue cannot file a bug or do that breakdown. Test Lead keeps `issue:*` (delete, workflow config).

### D29 — A story-level type named `Task` ships in P1.

§4's depth rule stands: a `TASK`-level issue needs a story-level parent. But the type catalogue
seeds a `STORY`-level type also called `Task` (beside Story, Bug, Spike), and quick-add defaults to
the story level. **D12 amended:** migrated `work_item` rows become this type, so a user who typed
"task" still sees "task".

**Why:** the owner's own case is a one-person company managing its own product; Epic → Story → Task
ceremony for "a thing to do" is the wrong cost there, and §4 already made a new type a row rather
than a migration. Orphan `TASK`-level issues stay refused.

### D30 — `issue.agent_eligible` is in P4, not deferred.

D26 made the LLM's queue identical to the person's queue, including work they mean to do by hand.
A boolean on the issue plus a filter is the signal; the MCP "my queue" tool defaults to it. Not an
assignment target — D26 is unchanged.

### D31 — An Epic's status is derived from its stories.

No stories started → not started; every counted story terminal → done; otherwise in progress. The
epic's status is not editable, and the page shows `done/total` beside the %. **D2 amended:** the
shared vocabulary still applies to Story/Bug/Spike/Task; an Epic's status is a projection of theirs.

**Why:** a manual epic status and a computed completion % can disagree — "In Progress, 100%" — and
this repo has already shipped two contradictory numbers on one screen once.

### D32 — The importer previews before it writes, and asks for a status mapping.

P3 runs a dry run first: counts per type, every field it will drop and how many values, and a
status-mapping step where the user maps each Jira status onto the project vocabulary (D2) rather
than an automatic guess. Commit happens only after confirmation. D15's persisted report is the
record of the run that actually happened.

**Open:** sprint membership. See §10.

### D33 — `review_enabled_issue` defaults to `false`.

With `author_may_approve_own_work = false` and the agent acting *as* its owner (D26), a one-person
company could never close a task its own LLM worked on. The gate is opt-in per project; a lead turns
it on.

### D34 — Board, Backlog, Table, Timeline and Calendar are one navigation entry.

One "Work" entry per project; the view switches in-page, filter state shared in the URL
(`useSearchParams`, as everywhere). "What's left" on the overview (D27) includes unscheduled backlog,
not only the next release's open issues, so unreleased work is never invisible.

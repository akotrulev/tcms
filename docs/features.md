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

- Six built-in roles, from most to least restricted: **Viewer**, **Automation Service**,
  **Test Engineer**, **Test Lead**, **Company Admin**, **Instance Admin**
- Roles are fixed and cannot be edited or cloned — a client needing something between two tiers
  gets the higher one
- 55 distinct permissions, granted per project, per company, or instance-wide
- Grant a role to a person on a project, or to a whole team at once — everyone in the team inherits it
- Test Lead adds exactly seven things over Test Engineer: the three approvals, and permission to
  change a project's settings, members, integrations and audit log
- Nobody can approve their own work, by default
- Buttons you lack permission for are **visible but disabled**, so the product never hides that a
  capability exists

## Projects

- Create projects, edit them, and archive ones that are finished
- Name your own execution statuses per project and set the order they appear in
- Define your own classification dimensions and values — Priority, Component, whatever the client
  calls them — including nested values
- Turn review and approval gating on or off per project, and choose what it applies to
- Manage who is on the project and what role they hold
- A dashboard per project showing what needs attention, how cases break down by lifecycle and
  classification, and how execution is trending

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
- Link a failed result out to a defect in your tracker
- Assign executions to people
- Clone a run to repeat it
- Close a run when it is done
- Runs carry release version, build number, commit SHA and branch, so a run is traceable back to
  what was built
- Runs move through Not Started → In Progress → Completed → Aborted

### Seeing the work

- A run detail view with live pass/fail/blocked counts and progress
- A **What Failed** panel putting the run's failures and their steps on one screen
- A **cross-project execution board** with drag-and-drop
- **My Work** — everything assigned to you, across every project
- **Team board** — everything assigned to a team, across every project
- Configurable handling of retries versus genuinely distinct runs when results are collapsed

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

## Work Items

- Create and track work items — tasks and action items attached to test activity
- Each carries a status and a priority
- They surface on the cross-project and team boards alongside executions

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

`tcms`, driven by `TCMS_URL` and `TCMS_TOKEN`. **27 commands:**

*Reading* — `whoami` · `project list` · `case list` · `case show` · `library list` (the condition
library) · `suite list` · `suite show` · `plan list` · `run list` · `doc list` · `doc show`

*Writing* — `case edit` · `case propose` · `suite create` · `suite edit` · `suite status` ·
`plan create` · `plan edit` · `plan status` · `run create` · `run status` · `result record` ·
`doc create` · `doc edit` · `doc link` · `doc unlink` · `review comment`

## AI Agent Access (MCP Server)

An MCP server exposing **23 tools**, over stdio or HTTP, so an AI assistant can work with TCMS
directly.

*Orientation* — `tcms_whoami` · `tcms_orient` (project summary with vocabularies and capabilities)

*Reading (11)* — `tcms_search_cases` · `tcms_open_case` · `tcms_list_suites` · `tcms_list_library` ·
`tcms_list_artifacts` · `tcms_doc_tree` · `tcms_open_doc_page` · `tcms_list_plans` · `tcms_open_run` ·
`tcms_triage`

*Writing (11)* — `tcms_write_case` · `tcms_write_suite` · `tcms_write_doc_page` · `tcms_write_plan` ·
`tcms_write_run` · `tcms_record_result` · `tcms_review_comment` · `tcms_review_verdict` ·
`tcms_write_work_item` · `tcms_write_artifact` · `tcms_report`

What makes it safe to point at real data:

- The agent's credential is capped at a fixed **Agent** role, and the server tells the model up
  front what that credential can actually reach — so it does not attempt work it will be refused
- Destructive operations prompt for consent, and **refuse outright when the host cannot ask**
- Tools are filtered by permission, so a narrowly-scoped token sees a narrower tool list

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

- Issue tracker integration (Jira, GitHub Issues, GitLab Issues, Azure DevOps) — link cases ↔ issues, one-click defect creation from a failed step, status sync-back, inline status hover-cards
- `.feature` export/import — generate `.feature` files from Gherkin cases, import existing ones, direction-of-truth per project *(§6a)*
- Cucumber step-level result mapping in CI ingestion
- Git traceability, remaining half — **the run half is implemented**: `test_run` carries `commit_sha`, `branch`, `build_number` and `release_version`, `RunCreateDialog` collects them and the run detail returns them. Not built: PR linkage and case↔requirement linking

### Phase 3 — Not started

- Trend dashboards — pass rate over time, MTTR on failing cases, execution time trends, coverage drift
- Bulk import/export — CSV/Excel import, TestRail/Xray migration importers
- Parameterized cases / data-driven execution — Scenario Outline `Examples` and structured-case DataSets
- Step phrase library — catalogue of Gherkin step phrases with autocomplete and near-duplicate detection
- Undefined-step detection — compare authored Gherkin against the automation repo's step-definition inventory
- Milestones & release readiness — gate view per milestone
- SCIM provisioning — push-based user/group sync from IdP
- Generic outbound webhooks — webhook events for custom automation beyond notifications
- Public versioned REST API — first-class, versioned, documented API layer

### Phase 1 — Planned but incomplete

- Gherkin syntax-aware editing — **the format itself is implemented**: a `STRUCTURED`/`GHERKIN` toggle, a seeded `Feature:` template, non-empty validation, and `GherkinView` keyword rendering on the detail view. Not built: keyword autocomplete, server-side parse/validation, Background/Scenario Outline handling, `.feature` preview
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
- Custom roles / clone-and-edit — deliberately rejected; six immutable roles only
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
- **No bulk execution assignment.** Cases have `/projects/{projectId}/cases/bulk`; executions have
  only a per-execution assignee endpoint. Splitting a 300-case run across five testers is 300
  operations.
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
- **No scheduled runs.** Internal jobs are scheduled, but a regression cycle cannot be.
- **No company data export.** Companies are provisioned by script with no offboarding path —
  relevant both to GDPR and to a client leaving.

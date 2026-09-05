# Billing (M8) Implementation Plan

**Date:** 2026-09-05 · **Written by:** `ep-writing-plans`

> **For agentic workers:** REQUIRED SUB-SKILL: Use `ep-executing-plans` to implement this plan
> task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. The plugin skills `CLAUDE.md`
> names are mandatory: `/be-java` + `be-java-database-code` for the backend, `data-postgresql` /
> `data-migrations` for T2, `/fe-react` for every screen, `qa-permission-testing-java` for T4's two
> negatives.

**Goal:** Finance sets a rate card per client and a budget per project, approved hours are priced by
that card, budget burn is visible to a PM **as a percentage and never as a currency**, and a draft
invoice — which is a *query* — is issued into a record whose lines can no longer change. A Test
Engineer who logs those hours cannot see what they cost, and an Account Manager sees the client and
the hours and never a rate.

**Architecture:** A new Spring Modulith module `com.tcms.billing` owns seven tables and every read
that names money. It may import only the five foundation modules, so it reaches `time`'s entries
through `billing.TimeFacts` and the tracker's issue projection through `billing.IssueFacts` — the
sixth and seventh instances of the `*Facts` pattern — and it reaches a client's **name** through
`project.ClientDirectory`, which `crm` already implements, rather than through a `ClientFacts` of its
own. **A draft invoice stores no lines**: it is a header plus a live computation over approved time,
so approving a period recalculates it, and `POST …/issue` is the one transaction that ever writes an
`invoice_line`. Immutability is therefore structural — there is no operation that can change a line —
rather than a guard somebody can forget. The frontend adds one query module behind the existing
barrel, one rail entry that is **absent** rather than disabled, two billing screens, and three
figures on screens other modules own.

**Tech Stack:** Java 21 · Spring Boot 3.5.6 · Flyway (`V12`) · Postgres 17 with `FORCE ROW LEVEL
SECURITY` · React 19 · TanStack Query · Tailwind v4 + the render kit · Vitest + RTL · `@tcms/client`,
`@tcms/cli`, `@tcms/mcp`.

**Module:** M8 of [009-project-management-expansion-modules.md](009-project-management-expansion-modules.md).
**Depends on:** M6 — `done 2026-09-04 @ 3471358c`, plan [016-time.md](016-time.md) — and Mc —
`done 2026-09-05 @ c7e504ff`, plan [018-clients.md](018-clients.md). **Both read `done` in that
plan's status table, verified 2026-09-05 before this plan was written.** No module depends on M8.

**Tasks** — in the order they are done:

| # | Task | Stack | Agent | Deliverable | Depends on |
|---|---|---|---|---|---|
| 1 | T1 — The billing contract, both generated clients, and the CLI/MCP verbs | `ts` | `tooling-engineer` | Twelve path items under a new `billing` tag plus one under `clients`, every schema carrying its `required` array; both generated clients regenerated; a factory per resource in both fixture files; the CLI's `billing` and `rate-card` families and four MCP tools; the surface doc. Every endpoint answers a real `404` until T3/T4 | none |
| 2 | T2 — `V12` — the seven billing tables | `be` | `data-architect` | `V12__billing.sql` applied; seven tables with `FORCE` RLS; `SchemaConventionTest` 102 → 109; the ERD amended | T1 |
| 3 | T3 — Rate cards, pricing, budgets and expenses | `be` | `be-java` | `billing.PricingService` prices one approved hour by the rate card; the rate-card, budget, budget-burn and expense endpoints serve real rows; `GET /projects/{id}/budget-burn` carries no currency and no amount; `BillingReadsAndNeverWritesTest` pins both `*Facts` classes | T2 |
| 4 | T4 — Invoices, the lock on issue, the two negatives, and the demo seed | `be` | `be-java` | `POST /invoices` drafts from approved hours and recomputes on every read; `POST …/issue` writes the lines once and assigns a number; a discard of an issued invoice answers `409` and its lines are unchanged after a rate-card edit; `BillingMoneyBoundaryTest` and the extended `TimeMoneyBoundaryTest` are green; `seed-demo-data` writes rate cards, budgets and two invoices | T3 |
| 5 | T5 — The rail entry, the fault arm, the query module, and the two billing screens | `fe` | `fe-react` | `/billing` and `/billing/invoices/:invoiceId` render on the kit; the Billing rail entry is **absent** for a caller without `billing:read`, asserted by a test; an issued invoice renders with no Issue and no Discard; `tcmsFaults.billing('no-rate-card')` drives the all-excluded draft | T4 |
| 6 | T6 — The client hours-and-invoices table, the two budget figures, and the close-out | `fe` | `fe-react` | `BillingSlot`'s body is the five-column table and the two tiles, with **no** value column for a caller without `billing:read`; the Overview's Budget burn panel and General settings' Budget row exist; `docs/features.md` and plan 009's status table closed | T5 |

**Budget.** Six tasks, one stack and one session each, per `CLAUDE.md`'s task-cut rule. T1 is the
largest read and lands as one commit because plan 009 requires the contract and both generated copies
to be atomic. The backend was cut in two at a seam inside the same stack — **T3 is everything an
invoice needs to exist and T4 is the invoice** — because one task carrying seven tables' services,
two `*Facts` classes, the pricing rule, the invoice lifecycle, two negative permission tests and the
demo seeder was measured over 150k against 016 T3 and 017 T3, both of which landed near it with less.

---

## Prompts — one per task

One task is one session. Each block below is complete on its own — what to read, in what order, the
traps that specific task will hit, what to commit and which gate to run. **A prompt is living text:**
a task that lands something different from what this plan predicted rewrites the prompts below it
before it stops, in place, with no changelog appended.

### Prompt — T1 — The billing contract, both generated clients, and the CLI/MCP verbs

```
You are `tooling-engineer` working in the tcms repo. Read CLAUDE.md first.

Read, in this order: docs/plans/019-billing.md §T1 and its "Decisions" section IN FULL — D-b, D-c,
D-d, D-e, D-f, D-g, D-h, D-i, D-j and D-k each decide a shape you will otherwise get wrong ·
CLAUDE.md · docs/specs/openapi.yaml's `clients` block (added by 018 T1, the newest whole tag) and its
`time` block (016 T1) · packages/client/src/test/fixtures.ts around `client` and `timesheetPeriod` ·
packages/cli/src/reads.ts and writes.ts around the `client` family ·
packages/mcp/src/reads.ts and writes.ts around `tcms_read_client` / `tcms_write_client` ·
docs/specs/2026-08-08-mcp-tool-and-command-surface.md §3.10 (the newest section, and the shape yours
takes) and its tool table rows 38-39 · docs/specs/2026-07-30-tcms-roles-permissions-naming.md's
"Money — COMPANY scope" table.

`git show --stat` the 018 T1 commit for the exact file set a contract commit in this repo touches.
Yours is that same set.

Deliver: twelve path items under a NEW `billing` tag, plus ONE more (`/clients/{clientId}/billing`)
under the EXISTING `clients` tag, each schema carrying its `required` array;
frontend/src/api/schema.d.ts and packages/client/src/schema.ts regenerated; a factory per new
resource in BOTH src/test/fixtures.ts copies; the CLI's `billing` and `rate-card` command families;
four MCP tools; and docs/specs/2026-08-08-mcp-tool-and-command-surface.md §3.11 plus rows 40-43 of
its tool table. Every endpoint answers a real 404 from a path the server does not serve until T3 and
T4 — contract-first, never a stub returning a fake success.

Traps this task will hit:
- `required` does NOT descend. Every inline nested object needs its own array, and a schema without
  one makes every generated property optional — which typechecks and is wrong everywhere.
- **No `default:` keyword on any optional field.** 018 T1's finding, and it costs both generated
  clients: `openapi-typescript` emits a defaulted property as REQUIRED, so every create that omits it
  becomes a compile error. `ClientInput.status` carries no `default:` for exactly this reason;
  `InvoiceDraftInput`, `RateCardEntryInput`, `BudgetInput` and `ExpenseInput` must not gain one.
- **contractBodies.ts needs NO change**, and 016 T1, 017 T1 and 018 T1 all recorded the same finding.
  Both copies are the schema WALKER; the marked region is `violationsOf` and its helpers and holds no
  response bodies. Bodies are factories in frontend/src/test/fixtures.ts and
  packages/client/src/test/fixtures.ts, which are NOT byte-identical to each other.
- **`amountsVisible` is a field, not an omission** (D-i). `ClientBillingSummary` always carries the
  amount fields and they are `nullable: true`; the flag says whether they are null because the caller
  may not see them or null because there is no invoice. Do not model this as two schemas.
- **Every money figure is `{amount, currency}`, never a bare number** (D-l), and the company-level
  totals are ARRAYS of those, one per currency. The billing.html mockup draws one €52.4k figure; that
  is a single-currency render, and a company with a EUR client and a CHF client has no single total.
  Behaviour is this plan's to decide and the mockup's to display — see D-l, which records the
  disagreement rather than resolving it silently.
- **`GET /projects/{projectId}/budget-burn` is gated on `project:read`, not on a money key**, and its
  schema has NO amount and NO currency member at all (D-h). It is the one billing-owned endpoint a
  Test Engineer may call, and the reason it is safe is that there is nothing on it to withhold.
- Nothing on this surface bills a fixed fee, a retainer, a margin or an AI-assistant hour (D24, D-q).
  A reviewer will grep the diff for those five words; there must be no schema, enum member or field
  that would match one except in a description saying it does not exist.

Commit ONE commit, staging only: docs/specs/openapi.yaml, frontend/src/api/schema.d.ts,
packages/client/src/schema.ts, frontend/src/test/fixtures.ts, packages/client/src/test/fixtures.ts,
packages/cli/src/*.ts, packages/mcp/src/*.ts, docs/specs/2026-08-08-mcp-tool-and-command-surface.md,
docs/plans/019-billing.md. Message: `feat(billing): the billing contract, both clients, and the
money verbs`.

Gate, on shanks, per CLAUDE.md's standing directive — read docs/specs/shanks-runner-details.md
first: `~/bin/tcms-remote sync` then `~/bin/tcms-remote packages npm run build`,
`~/bin/tcms-remote packages npm run lint --workspaces`,
`~/bin/tcms-remote packages npm run test:run --workspaces`, and
`~/bin/tcms-remote frontend npm run build`.
```

### Prompt — T2 — `V12` — the seven billing tables

```
You are `data-architect` working in the tcms repo. Read CLAUDE.md first, then load
`data-postgresql` and `data-migrations`.

FIRST COMMAND, BEFORE YOU READ ANYTHING ELSE:
`ls backend/src/main/resources/db/migration/`
That directory is the authority on your version number and this plan is not. Plan 009's column has
now been corrected SIX times in five weeks and V11 is spent by Mc. This plan projects V12. If the
directory disagrees, the directory wins and you correct this plan.

Read, in this order: docs/plans/019-billing.md §T2 and its "Decisions" section — D-b, D-c, D-e, D-h
and D-k each decide a column · CLAUDE.md · backend/src/main/resources/db/migration/V11__crm_clients.sql
IN FULL (the newest migration, and the shape yours takes — header, per-table block comments carrying
the WHY, the RLS DO-block with its two RAISE guards) · V9__time.sql's `time_entry` block (your
pricing input, and its ck_time_entry_subject_shape is why four of the five subject types can never
reach an invoice) · backend/src/test/java/com/tcms/tenancy/SchemaConventionTest.java lines 395-440 ·
docs/specs/2026-07-30-tcms-erd.md §0.1.

Deliver: V12__billing.sql with SEVEN tables — rate_card, rate_card_entry, budget, invoice,
invoice_line, invoice_line_entry, expense — every one carrying `company_id UUID NOT NULL` leading
every composite index, all seven under ENABLE + FORCE ROW LEVEL SECURITY with the company_isolation
policy, and SchemaConventionTest's count moved 102 → 109 with a comment saying which tables and why.

Traps this task will hit:
- **SEVEN tables, not the six plan 009 named.** invoice_line_entry is the seventh and story M2
  requires it: "the time entries show which invoice they are on". billing MAY NOT write a column on
  time_entry, so the link is a billing-owned join with NO foreign key to time_entry — the polymorphic
  crossing scope §3 prescribes, the same shape as work_item_link and doc_link. Say so in the block
  comment; a reader will otherwise read the missing FK as an omission.
- **invoice_line and invoice_line_entry are written exactly once, inside the issue transaction, and
  never updated** (D-c). There is no lock_version on either and that is deliberate: optimistic
  locking guards concurrent UPDATE, and nothing updates these rows. Write that in the comment or the
  next reader adds the column back.
- **invoice_line stores the rate it was priced at, as a number** — not a rate_card_entry_id. The
  execution breakdown's t41 states this as an acceptance criterion and it is the whole point of
  issuing: a card edited next month must not reprice an invoice sent last month.
- **No `key_sequence` change** (D-k). Do not widen its CHECK, do not backfill a row, and above all do
  not `CREATE OR REPLACE` tcms_seed_company_defaults — V11's header names reproducing a function body
  as the way this gets broken, and CREATE OR REPLACE FUNCTION replaces the WHOLE body. invoice.seq is
  billing's own counter with `uq_invoice_seq UNIQUE (company_id, seq)` as the arbiter.
- **budget.currency is NULL unless basis = 'MONEY'**, enforced by a CHECK, for ck_time_entry_subject_shape's
  reason: a shape constraint the database holds is one the application cannot drift from.
- Do NOT restate tcms_seed_project_defaults or tcms_seed_company_defaults. Nothing here is a
  per-project or per-company default row: a new company starts with no rate card, no budget and no
  invoice, and every one of those is the correct empty state.
- Run the computed check, not the careful read: after writing the file, script the comparison that
  every new table has company_id, a FORCE policy and a leading-company_id index. Reading V1 top to
  bottom once missed five join tables without company_id.

Commit ONE commit staging only backend/src/main/resources/db/migration/V12__billing.sql,
backend/src/test/java/com/tcms/tenancy/SchemaConventionTest.java, docs/specs/2026-07-30-tcms-erd.md
and docs/plans/019-billing.md. Message: `feat(billing): V12 — rate cards, budgets, invoices and
expenses`.

Gate on shanks: `~/bin/tcms-remote sync` then `~/bin/tcms-remote backend ./gradlew build`. Read
docs/specs/shanks-runner-details.md first — BUILD FAILED with `Could not write XML test results` and
ZERO assertion failures is an fd-limit problem, not a red suite (`ulimit -n 65535`).
```

### Prompt — T3 — Rate cards, pricing, budgets and expenses

```
You are `be-java` working in the tcms repo. Read CLAUDE.md first, then load `be-java-database-code`.

Read, in this order: docs/plans/019-billing.md §T3 and its "Decisions" section IN FULL · CLAUDE.md ·
backend/src/main/java/com/tcms/crm/ (the newest module, and the shape yours takes — package-info.java
with its allowedDependencies, the sealed Outcome, the three-refusal comment on ClientService) ·
backend/src/main/java/com/tcms/staffing/TimeFacts.java and com/tcms/time/IssueFacts.java (the two
`*Facts` classes you are copying; yours are the sixth and seventh) ·
backend/src/test/java/com/tcms/staffing/StaffingReadsNoTimeEntryTest.java (the test you are copying) ·
com/tcms/project/ClientDirectory.java · com/tcms/project/ProjectService.java's readInCompany,
holdsInCompany and readGranted.

Deliver: the `com.tcms.billing` module serving the rate-card, budget, budget-burn and expense
endpoints from T1's contract against real rows, with billing.PricingService as the one place a rate
is chosen, and BillingReadsAndNeverWritesTest asserting both halves of the crossing rule for both
`*Facts` classes.

Traps this task will hit:
- **billing MAY NOT import crm.** Get a client's NAME through project.ClientDirectory, which crm
  already implements. Do NOT add a billing.ClientFacts reading the `client` table — the prompt that
  started this module says so by name, and the reason is that project → crm is the REVERSE of the
  `*Facts` direction and ArchUnit cannot see it, because SQL is not a Java dependency. If
  ClientDirectory lacks a method you need, add it THERE and implement it in crm; that is the pattern,
  not a workaround. Both of those files are outside your module and each edit is a decision to state.
- **Two `*Facts` classes, each the ONLY file naming its tables** (D-m): billing.TimeFacts names
  time_entry and timesheet_period; billing.IssueFacts names issue_fact. Nothing else in the package
  may name any of the four, and nothing may WRITE any of them. issue_time_rollup is deliberately not
  read — it is per-issue approved hours, and an invoice needs the ENTRIES, not their sum.
- **Only APPROVED, ISSUE-subject hours are priceable** (D-e). V9's ck_time_entry_subject_shape makes
  project_id NULL for MEETING, LEAVE, TRAINING and INTERNAL, so four of the five subject types can
  never reach a rate card. This is not a filter you apply as a courtesy; it is the shape of the data.
- **Pending hours are never priced** (016 D-d). approved and pending are two numbers and nothing in
  this product sums them.
- **The rate ladder is person, then role, then all-roles, each as of the entry's spent_on** (D-d).
  Where a person holds two roles on the project with different rates, the higher wins — deterministic
  by construction, and stated in PricingService's javadoc with the alternative (refusing the entry as
  ambiguous) recorded as rejected.
- **GET /projects/{projectId}/budget-burn is gated on `project:read`** and returns hours and a
  percentage and NOTHING ELSE (D-h). Write the assertion in the same task, not later: a
  serialized-body scan for `rate`, `cost`, `amount`, `currency` and `invoice`, the same shape as
  TimeMoneyBoundaryTest's. That test is a Test Engineer's whole view of this module.
- **A repository interface must carry `@Transactional(readOnly = true)`.** Without it, derived and
  @Query methods run with no company set and every RLS-protected read returns ZERO ROWS, fail-closed
  and silent. This has bitten every module in this repo.
- **`billing:expense` gets endpoints and no screen**, deliberately (D-j). A catalogue key with no
  reachable path is the unreachable-row defect §43 names; the CLI and MCP verbs T1 landed are its
  callers. Say so in the controller header — do not build an expenses panel the mockups do not draw.

Fixture UUID prefixes: grep the WHOLE test tree before taking one. `f8`/`f9` are M6's, `fd`/`ff` are
Mc's, and 018 recorded that `fa` and `fb` LOOKED free and were not — the suite failed with a
project_pkey duplicate naming neither class. This plan suggests `50` for PricingTest and `51` for
BudgetBurnTest; verify both.

Commit ONE commit staging only backend/src/main/java/com/tcms/billing/,
backend/src/test/java/com/tcms/billing/, the two project-module files if ClientDirectory grew, and
docs/plans/019-billing.md. Message: `feat(billing): the billing module, the rate ladder, and budget
burn without a currency`.

Gate on shanks: `~/bin/tcms-remote sync` then `~/bin/tcms-remote backend ./gradlew build`.
```

### Prompt — T4 — Invoices, the lock on issue, the two negatives, and the demo seed

```
You are `be-java` working in the tcms repo. Read CLAUDE.md first, then load `be-java-database-code`,
`be-java-unit-testing` and `qa-permission-testing-java`.

Read, in this order: docs/plans/019-billing.md §T4 and its "Decisions" section IN FULL — D-c, D-f,
D-g, D-k and D-n are this task's whole subject · CLAUDE.md · what T3 landed in
backend/src/main/java/com/tcms/billing/ · backend/src/test/java/com/tcms/time/TimeMoneyBoundaryTest.java
IN FULL · backend/src/test/java/com/tcms/crm/CrmCreatesNoLoginTest.java's
theClientSurfaceCarriesNoMoneyWord · backend/src/main/java/com/tcms/DemoWorkspaceSeeder.java's
seedStaffing (the newest seeder arm, and the shape yours takes) ·
docs/specs/2026-08-30-project-management-expansion-user-stories.md §11 stories M1 and M2 ·
docs/mockups/invoice.html.

Deliver: POST /invoices drafting from approved hours, GET /invoices/{id} recomputing a draft on every
read, POST /invoices/{id}/issue writing the lines once and assigning a number, DELETE refusing an
issued invoice with 409, the client billing summary, the company summary, both negative tests green,
and seed-demo-data writing rate cards, budgets and two invoices.

Traps this task will hit:
- **A DRAFT STORES NO LINES** (D-c). It is a header — client, period, status — and its lines are
  computed on every read from the approved entries that exist NOW. That is what makes invoice.html's
  promise true ("Approve them and this draft recalculates") without a recalculate endpoint, and it is
  what makes the lock structural: `invoice_line` rows exist only for an ISSUED invoice, written once,
  by one transaction, and no operation on this surface updates one. Prove the lock the honest way —
  issue, edit the rate card, re-read, assert the line is byte-identical — not by asserting that an
  endpoint you did not build returns 405.
- **An entry already on an ISSUED invoice is excluded from every later draft.** That is the only
  thing standing between this module and double-billing, and the query is against
  invoice_line_entry, which exists only for issued invoices.
- **Two exclusion buckets, both counted and both named** (D-g): unapproved hours (invoice.html's
  amber note) and approved hours with no rate (billing.html's "Without a card, approved hours are
  still recorded — they just cannot be priced"). Neither is silently dropped. A draft that quietly
  omitted an hour would be the defect this whole module exists to remove.
- **DO NOT CLAIM APPROVAL AUTHORITY YOU CANNOT PROVE** (D-f, 016 D-i). A timesheet period is per
  person per fortnight and SPANS PROJECTS, while `time:approve` is a PROJECT-scope grant — so
  approving a period approves entries against projects the approver cannot see. "These hours were
  approved" is TRUE; "somebody with authority over this project approved these hours" is NOT
  SUPPORTED BY THE DATA. Every label on this surface says the first. Read D-f before you name a
  column, and do not repair this by evaluating grants at invoice time: that computes a claim about
  the past from the permissions of the present, which is worse than not claiming it.
- **EXTEND TimeMoneyBoundaryTest. DO NOT REWRITE IT.** Add the Account Manager arm — an AM holds
  `client:read`, holds not one of the seven money keys, and is refused every billing path — beside
  the four tests already there. Its everyMoneyKeyInTheCatalogueIsCoveredByThisTest arm is what makes
  the boundary grow-proof; leave it exactly as it is.
- **CrmCreatesNoLoginTest.theClientSurfaceCarriesNoMoneyWord is the one test in this module you SHOULD
  change, and only by extending it** (D-n). Its subject is `ClientDetail`, which still carries no money
  word, so the assertion itself does not move: what you add is a sibling naming
  `/clients/{clientId}/billing` as the one path under a `clients` URL where a money word is now
  allowed, and only for a caller holding `billing:read`. Deleting the original is the weakening the
  module prompt forbids.
- **BillingMoneyBoundaryTest asserts the LACK of a permission, over paths derived from the contract**
  — not "the matrix says Finance only, therefore it holds". Enumerate the billing-tagged paths from
  docs/specs/openapi.yaml and assert a Test Engineer is refused every one, so an endpoint added later
  without a gate reddens this test instead of shipping.
- **The demo seeder is how anybody ever LOOKS at this.** 017's close-out records that two tasks
  claimed the staffing seeder wrote nothing and were wrong because neither checked. Write
  seedBilling, and then run `docker compose run --rm backend seed-demo-data` — or say in the
  close-out that you did not.

Fixture UUID prefixes: grep the whole test tree before taking one; see T3's prompt for why. This plan
suggests `4c` for BillingMoneyBoundaryTest, `4e` for InvoiceLifecycleTest and `52` for
ClientBillingSummaryTest; verify all three.

Commit ONE commit staging only backend/src/main/java/com/tcms/billing/,
backend/src/test/java/com/tcms/billing/, backend/src/test/java/com/tcms/time/TimeMoneyBoundaryTest.java,
backend/src/test/java/com/tcms/crm/CrmCreatesNoLoginTest.java,
backend/src/main/java/com/tcms/DemoWorkspaceSeeder.java and docs/plans/019-billing.md.
Message: `feat(billing): the draft as a query, the issue as a record, and the two negatives`.

Gate on shanks: `~/bin/tcms-remote sync` then `~/bin/tcms-remote backend ./gradlew build`.
```

### Prompt — T5 — The rail entry, the fault arm, the query module, and the two billing screens

```
You are `fe-react` working in the tcms repo. Read CLAUDE.md first, then load `fe-react-ui-rules`,
`fe-react-data-fetching` and `fe-react-component-testing`.

Read, in this order: docs/plans/019-billing.md §T5 and its "Decisions" section — D-h, D-i, D-l, D-o
and D-p each decide something you render · CLAUDE.md · docs/mockups/billing.html and
docs/mockups/invoice.html IN FULL, plus docs/mockups/assets/tcms-mock.css for any class you do not
recognise · frontend/src/features/clients/ClientsPage.tsx and ClientPage.tsx (the newest screens, and
the shape yours take) · frontend/src/features/time/TimesheetPage.tsx (the newest screen with a KPI
row and a period picker) · frontend/src/api/queries/clients.ts and keys.ts ·
frontend/src/lib/devFaults.ts's clients arm · frontend/src/app/AppShell.tsx lines 200-320 and 580-620
(TIMESHEETS' placement argument, and the Clients entry's gate argument — yours is the fifth gated
entry and takes the same one) · frontend/src/app/router.tsx around the /clients routes.

Deliver: /billing and /billing/invoices/:invoiceId on the render kit, the Billing rail entry gated on
`billing:read`, one new file in frontend/src/api/queries/ behind the existing barrel with its keys in
the ONE keys.ts factory, and `tcmsFaults.billing(...)`.

Traps this task will hit:
- **Billing is ABSENT from the sidebar for everyone but Finance and Company Admin — absent, not
  greyed out** (D-o). billing.html's own note says so in a callout. This is the FIFTH gated entry in
  a rail whose standing rule is disable-don't-hide, and the argument is the one the Clients entry
  already makes: the read is refused rather than narrowed, so an entry into it is a refusal one click
  earlier. Write a test asserting the entry does not render for a Test Engineer or an Account
  Manager; the acceptance criterion in 009's execution breakdown names exactly that test.
- **NEVER build your own QueryClient in a test** — only createTestQueryClient's has the caches. A
  render that trips an error boundary FAILS unless it calls renderingIntoAnErrorBoundary() and says
  why; likewise drivingAFailedRead() / drivingAFailedWrite().
- **ONE keys factory**, frontend/src/api/queries/keys.ts. Never add a second. Read the comments
  around `clientsRoot` and `staffingRoot` — where a key lives is decided by which invalidation must
  sweep it, and an invoice write must sweep both the billing root and the client it names.
- **Money is `{amount, currency}` and totals are per-currency arrays** (D-l). Do not sum two
  currencies to render one KPI number, however much billing.html's €52.4k tile looks like one figure.
  Render one line per currency; the mockup is a single-currency render and this plan records the
  disagreement rather than resolving it silently.
- **An ISSUED invoice renders read-only** — no Issue button, no Discard, no edit affordance anywhere,
  and its number where the draft says "assigned on issue".
- **"Preview PDF" is not built** and there is no endpoint for it. Do not draw a button that does
  nothing; say why in the page's header comment, the way ApprovalsPage.tsx records the absent
  `Approve all` and `Remind`.
- **New enums go in lib/labels.ts as `satisfies Record<Union, string>`**, so a member added later is
  a compile error until it is labelled. InvoiceStatus, BudgetBasis and RateCardScope are yours.
- Tailwind utilities only. No `sx`, no CSS-in-JS, no second stylesheet.

Commit ONE commit staging only frontend/src/features/billing/, frontend/src/api/queries/billing.ts,
frontend/src/api/queries/{keys.ts,index.ts}, frontend/src/app/{AppShell.tsx,router.tsx},
frontend/src/lib/{devFaults.ts,labels.ts}, their tests, and docs/plans/019-billing.md.
Message: `feat(billing): the billing and invoice screens, and the rail entry that is absent`.

Gate on shanks: `~/bin/tcms-remote sync` then `~/bin/tcms-remote frontend npm run build`,
`~/bin/tcms-remote frontend npm run lint`, `~/bin/tcms-remote frontend npm run test:run`.
Two failures in src/features/cases/CaseGrid.test.tsx PREDATE this module — 016 and 017 both recorded
them, both `Number of calls: 0` after a userEvent.click on the Radix Checkbox. Do not absorb them;
do not report them as yours.
```

### Prompt — T6 — The client hours-and-invoices table, the two budget figures, and the close-out

```
You are `fe-react` working in the tcms repo. Read CLAUDE.md first, then load `fe-react-ui-rules` and
`fe-react-component-testing`.

Read, in this order: docs/plans/019-billing.md §T6 and its "Decisions" section — D-h, D-i and D-p ·
CLAUDE.md · frontend/src/features/clients/BillingSlot.tsx IN FULL (its header states exactly what you
are replacing and why the slot exists) · docs/mockups/client.html's three KPI tiles, its "Hours and
invoices to date" table and its "Rate card" panel · docs/mockups/project-overview.html's "Budget
burn" panel · docs/mockups/settings-general.html's Budget row ·
frontend/src/features/projects/ProjectOverviewPage.tsx and its test (both say in terms why the budget
card is NOT rendered — you are reversing that, and the strikethrough style is the repo's) ·
frontend/src/features/settings/GeneralSettingsPage.tsx's TimePanel and its test ·
docs/plans/009-project-management-expansion-modules.md's "Prompt hand-off protocol".

Deliver: BillingSlot's body replaced by the five-column table and the two tiles; the Overview's
Budget burn panel; General settings' Budget row; and the module closed per the hand-off protocol.

Traps this task will hit:
- **BillingSlot is your WHOLE table, not three of its columns** (018 D-g). Replace its body; the seam
  is the file. `Period · Project · Approved hours · Invoice · Status`.
- **An Account Manager sees the client, the hours and NEVER a rate** — so with `amountsVisible: false`
  the value column is ABSENT, not empty and not dashed (D-i). client.html's own footer says the table
  "gains a value column" when you switch to Finance, which is the same statement from the other side.
  Assert the absence; a test that only checks Finance's view proves nothing about the boundary.
- **The rate card panel on client.html renders "Not visible to you" for an Account Manager.** Now
  that rate cards exist, that panel is real for Finance. Build it as a link into /billing rather than
  a second editor — one place edits a rate card, and it is the Billing screen T5 built.
- **The Overview's Budget burn panel carries hours and a percentage and no currency, ever** (D-h).
  The mockup's own hint says "hours, no rates" and its copy says "What an hour costs is Finance's,
  and is not on this page". For a MONEY-basis budget render the percentage alone — never the amount.
- **ProjectOverviewPage.test.tsx currently asserts the budget card is NOT rendered.** That assertion
  was correct when it was written and is now superseded; rewrite it to assert what is now true, and
  say in the file that it was reversed by 019 rather than deleting the reasoning.
- **A project with no client has no rate card and cannot be invoiced, and nothing marks that as a
  deficiency** (D-p, scope D1 rule 1). The internal project's row on billing.html reads
  "None (internal)" and its burn reads "no budget set". No nag, no warning tone, no call to action.
- **`trackTime` is the one boolean in ProjectSettingsUpdate the server reads as `true` when omitted.**
  If you touch frontend/src/features/settings/settingsUpdate.ts — the ONE place that field list is
  written — do not disturb it, or two screens will disagree.

Then close the module, and this is part of the deliverable rather than a follow-up:
1. docs/features.md — the Billing section, written for a reader deciding what the product does.
   Struck through and superseded where it replaces something, never deleted.
2. docs/plans/009-project-management-expansion-modules.md — M8's row set to
   `done YYYY-MM-DD @ <short sha>`; a `**Built and unverified:**` list under the M6·M7·M8 section
   saying what the gates proved compiles and nobody has clicked through; and the V ACTUALLY taken,
   with the reserved number struck through rather than deleted. **No module depends on M8, so no
   downstream prompt needs rewriting** — say that in the commit message rather than touching one.
3. This plan's own status.

Commit the screens and the close-out as TWO commits: the screens first, then
`docs(plan): M8 done` staging only docs/features.md and
docs/plans/009-project-management-expansion-modules.md.

Gate on shanks before the first commit: `~/bin/tcms-remote sync` then
`~/bin/tcms-remote frontend npm run build`, `npm run lint`, `npm run test:run`. The two
CaseGrid.test.tsx failures predate this module — 016 and 017 both recorded them. Do not absorb them.
```

---
## Decisions

Each states the decision **and the failure it prevents**. Several look arbitrary until you read the
second half.

### D-a — The migration is `V12`, and the directory said so, not this plan

`ls backend/src/main/resources/db/migration/` on 2026-09-05 showed `V1 … V11`, `V11__crm_clients.sql`
being Mc's. Plan 009's column projected `V10`, then `V11`, then `V12`, then gave up on arithmetic and
wrote *"whatever `ls` says on the day"* — **six corrections in five weeks**, from six unrelated
causes: a reorder, a module spending two versions, a module not having run before the one that needed
the number, two modules not having run, and twice more since. `out-of-order` is unset in
`application.yml`, so a lower-numbered migration arriving after a higher one is **never applied**, and
Testcontainers builds every test database from empty, so no suite in this repository can show the
conflict. T2's first command is the `ls`, and if it disagrees with this section, it wins.

### D-b — Seven tables, not the six plan 009 named

`rate_card`, `rate_card_entry`, `budget`, `invoice`, `invoice_line`, `expense` — and
**`invoice_line_entry`**, which plan 009's table did not name and story M2 requires:
*"Given I issue the invoice, then the lines are locked and **the time entries show which invoice they
are on**."*

`billing` may not write a column on `time_entry`, so the link is a billing-owned join carrying
`invoice_line_id` and `time_entry_id` with **no foreign key** to the second — the polymorphic
crossing scope §3 prescribes and `work_item_link`, `doc_link` and `execution_fact` already pay for.
It is not an optional convenience: it is also the only thing standing between this module and
double-billing, because *"which approved hours are already on an issued invoice"* is a question with
no other place to live.

Rejected: a `uuid[]` column on `invoice_line`. It cannot be indexed for the exclusion query the draft
runs on every read, and it makes "is this entry billed" a scan of every array in the company.

### D-c — A draft is a query; an issued invoice is a record

**A `DRAFT` invoice stores no lines.** It is a header — client, period, status — and its lines are
computed on every read from the approved entries that exist *now*. `POST /invoices/{id}/issue` is the
one transaction in this module that ever writes an `invoice_line` or an `invoice_line_entry`, and
nothing on the surface updates one afterwards.

What this buys, in the order it matters:

1. **`invoice.html`'s central promise becomes true for free.** *"Approve them and this draft
   recalculates."* There is no recalculate endpoint, no staleness flag, and no window in which a
   draft disagrees with the timesheets behind it.
2. **The lock is structural rather than guarded.** "Lines are immutable once issued" is usually a
   check somebody can forget on the sixth endpoint. Here there is no operation that can change a
   line, because none was built — and the proof is not that a `PUT` returns `405` but that issuing,
   editing the rate card, and re-reading returns a byte-identical line.
3. **The exclusion query has one clean input.** `invoice_line_entry` rows exist only for issued
   invoices, so "already billed" is a fact about issued work and never about somebody else's open
   draft.

The cost, stated: two drafts open over an overlapping period for one client will both offer the same
hours, and whichever issues first takes them. That is visible — the second draft recomputes on its
next read and the hours are gone from it, with the invoice that took them named. A reservation model
was rejected: it makes a draft a lock on the company's time data, which is a much worse failure than
a recomputation somebody sees.

### D-d — One rate card per client; person beats role beats all-roles, as of the day worked

`uq_rate_card_client UNIQUE (company_id, client_id)`. **The card carries the currency** — Acme is EUR
and Bergstrom is CHF on `billing.html` — which is what makes an invoice's currency determined by its
client rather than chosen on a form, and cross-client invoicing structurally impossible.

`rate_card_entry` carries the effective date, **not the card**: the mockup's Acme rows read *"from
1 Jan 2026"* for two roles and *"from 1 Jul 2026"* for one person. An entry is `(scope, targetId,
hourlyRate, effectiveFrom)` where scope is `PERSON`, `ROLE` or `ALL_ROLES`, and the rate for an hour
is the most specific entry effective on or before that hour's `spent_on`:

1. a `PERSON` entry naming the person who logged it;
2. a `ROLE` entry naming a role that person actually holds **on that project**;
3. the `ALL_ROLES` entry.

**Where two role entries both match, the higher rate wins.** That is deterministic by construction
and it is a decision, not a coin toss: a person granted both Test Lead and Test Engineer on one
project is being paid for as a Test Lead, and the client agreed that number. Rejected: refusing the
entry as ambiguous, which turns a rate-card configuration a Finance user could not have anticipated
into hours that silently fail to bill.

**Without a card, approved hours are still recorded — they just cannot be priced.** The mockup's own
footer, and D-g is where those hours go.

### D-e — Only approved hours with an issue are priceable, and that is the shape of the data

Two independent conditions, and neither is a courtesy filter:

- **Approved only** (016 D-d). `TimeRollup` and `TimesheetPeriod` carry approved and pending apart
  and nothing in this product sums them. Pricing pending hours would bill work nobody has signed for.
- **`subject_type = 'ISSUE'` only.** V9's `ck_time_entry_subject_shape` makes `project_id` **NULL**
  for `MEETING`, `LEAVE`, `TRAINING` and `INTERNAL` — four of the five members. An hour with no
  project has no client, and therefore no rate card and no budget. This is not a rule `billing`
  applies; it is a constraint the database already holds, and the reason the M8 column of plan 009
  says *"an invoice cannot assume every hour has one"*.

Note what follows: a person's fortnight can be entirely meetings and leave and still be a complete,
approved, correct period that produces no invoice line at all. That is not an error state.

### D-f — Billing does not re-derive approval authority, and does not claim it

**This is the sharp edge M6 handed down (016 D-i), and it is settled here rather than discovered from
a client.**

A timesheet period is per person, per semi-monthly range, and it **spans projects**, while
`time:approve` is a **PROJECT**-scope grant. A submitted period reaches an approver when at least one
of its entries names a project they may approve for, and **approving it approves the whole period** —
including entries against projects that approver cannot see. So:

> *"These hours were approved"* is true.
> *"Somebody with authority over this project approved these hours"* is **not supported by the
> data.**

**Every label on this surface makes only the first claim.** `invoice_line_entry` snapshots the
period's `decidedAt` and `decidedBy`, and the invoice screen's entries table is headed **`Period
approved`** with the value read as *"3 Sep · Rae"* — which is exactly what the mockup draws and
exactly what the row is: the decision on that person's period. Nothing anywhere reads "approved by
the project lead".

Rejected, explicitly: evaluating `time:approve` for the deciding approver against each entry's
project at invoice time, and flagging the entries that fail. It computes a claim about the past from
the grants of the present — a person who has since left the project makes last month's approved hours
retroactively suspect — which is a worse statement than the one we decline to make. **If per-project
approval authority is ever wanted, it belongs in `time` as a column written at decision time**, not
in `billing` as a join, and reopening 016 D-i is the way to get it.

### D-g — Two exclusion buckets, both counted, both named

A draft reports three populations and never silently drops an hour:

| Bucket | Why it is out | Where the mockup says so |
|---|---|---|
| Priced lines | — | the Lines table |
| **Unapproved hours** | nobody has signed for them | the amber note: *"38 hours are excluded because they are not approved… They are counted here so nobody wonders where they went"* |
| **Approved hours with no rate** | no rate card, or no entry effective that day | *"Without a card, approved hours are still recorded — they just cannot be priced"* |

The second bucket is the one a naive implementation loses, because it looks like the first from the
inside of a join. Both carry their hours and the reason; neither carries an amount, because neither
has one.

### D-h — The project-scope budget read carries a percentage and hours, and never an amount

`GET /projects/{projectId}/budget-burn` is gated on **`project:read`** and its schema has **no
`amount` and no `currency` member at all**. It serves `basis`, `approvedHours`, `budgetHours`
(null unless the basis is `HOURS`) and `burnPercent`.

This is what the roles spec means by *"a Project Manager… whose budget view is a percentage on the
project overview rather than a number in a currency"*, and it is what `project-overview.html` draws:
the panel's hint reads **"hours, no rates"** and its own copy reads *"A Project Manager sees the
ceiling and the burn. What an hour costs is Finance's, and is not on this page."*

Two things follow, and both are deliberate:

- **It is the one endpoint in this module a Test Engineer may call**, and the reason that is safe is
  that there is nothing on it to withhold. It is not gated on a money key because there is no money
  on it — gating project health behind `billing:read` would hide a project's own ceiling from the
  people delivering against it.
- **A `MONEY`-basis budget renders as a percentage alone** on every project-scope surface. The
  percentage is computed from priced approved hours, so where the project's client has no rate card
  the burn is *unknown* rather than zero, and it renders as unknown.

Rejected: a new `budget:view` project-scope permission. The catalogue is closed at 91 keys and every
role's grants are seeded; a new key is a migration, four documents and a `matrixCopies` change to
express a distinction `project:read` already draws correctly.

### D-i — `amountsVisible` is a field, because a null that means two things is a lie

`client.html` renders the hours-and-invoices table for an **Account Manager** with the amounts absent,
and its footer says *"Switch the top bar to Finance and this table gains a value column."* So one
path serves two readers with different columns.

`ClientBillingSummary` therefore carries **`amountsVisible: boolean`** at the envelope, and every
amount on it is nullable. Without the flag, `amount: null` would mean either *there is no invoice for
this period* or *this is none of your business*, and a renderer cannot tell them apart — so it either
draws an empty column at an Account Manager (which the mockup explicitly does not) or hides the column
whenever a single row happens to be unbilled.

The flag is `billing:read`, evaluated once, server-side. The frontend never decides this from its own
permission cache: a client that computed visibility locally would be one stale `/me/permissions`
away from rendering a column of nulls and calling it money.

### D-j — Expenses are recorded and never invoiced, and they have no screen

D24 is unambiguous: *"An invoice line is approved time entries at a rate-card rate."* There is no
expense line on `invoice.html` and there will not be one. `expense` exists because **`billing:expense`
is in the catalogue** and a permission with no reachable endpoint is the unreachable-row defect §43
names — the same argument that put the company-scope half of staffing in its own table.

So: `POST`/`GET`/`DELETE /expenses` exist, gated on `billing:expense` and `billing:read`, reachable
from the CLI and MCP, and **no mockup draws an expenses panel, so no screen is built.** Plan 009's
rule is that no module ports a screen it has no endpoint for; this is the other direction, which is
allowed and is stated here so the absence is not later read as an unfinished task.

**AI-assistant usage is a cost, never a line** (D24, and `billing.html`'s "Not billed" panel says so
on the screen). Nothing in this module turns an agent's hours into a charge, and the `expense` table
is where such a cost would be recorded if anybody ever recorded one.

### D-k — Invoice numbering is billing's own, and `key_sequence` is not touched

`invoice.seq BIGINT` with `uq_invoice_seq UNIQUE (company_id, seq)`, allocated inside the issue
transaction as `MAX(seq) + 1` for the company, with the unique constraint as the arbiter and **one
retry** on violation. The rendered number is `INV-<issue year>-<seq zero-padded to 3>` — `INV-2026-041`,
as the mockups draw it.

`key_sequence` was the obvious home and is rejected on three counts, in ascending order of weight:

1. Its shape is `prefix + next_value` and it produces `TC-1`. A year-qualified, zero-padded invoice
   number is not that shape.
2. Adding `'INVOICE'` costs a CHECK change **and** a backfill for every existing company.
3. **It also costs a `CREATE OR REPLACE FUNCTION` of `tcms_seed_company_defaults`**, or a company
   provisioned tomorrow cannot issue an invoice at all. `CREATE OR REPLACE` replaces the *whole*
   body, and V11's header names reproducing a function body as precisely how this gets broken. Four
   migrations in a row have declined to touch that function; this one declines too.

Gap-free numbering is not guaranteed and is not required — `key_sequence`'s own table comment says
the same thing for display keys, for the same reason: a rolled-back transaction burns its number. The
contract is uniqueness and immutability.

### D-l — Money is `{amount, currency}`, and a company total is a list of them

Every monetary figure on this surface is a pair. Every **company-level** total — invoiced year to
date, draftable now — is an **array** of pairs, one per currency.

`billing.html` draws `€52.4k` as a single tile while its own rate-card table carries a EUR client and
a CHF client two rows apart. That is a single-currency render of a multi-currency company, and
summing across currencies to fill one tile would be a number that is wrong in every currency.
**Recorded as a disagreement rather than resolved silently**, per plan 009's precedence rule: on what
a screen shows the mockup wins, on what the system does this plan wins, and *"what is the company's
invoiced total"* is a question about the system. The screen renders one line per currency in the tile
where the mockup renders one.

### D-m — Two `*Facts` classes, and each is the only file naming its tables

`billing` may import only the five foundation modules, so:

| Class | Reads | Instance |
|---|---|---|
| `billing.TimeFacts` | `time_entry`, `timesheet_period` | sixth |
| `billing.IssueFacts` | `issue_fact` | seventh |

Read-only native SQL, running inside the caller's transaction and therefore under RLS. Nothing else
in the package may name any of those tables, and nothing may write one — `BillingReadsAndNeverWritesTest`
asserts both halves for both classes, over source text, because ArchUnit cannot see the rule: SQL is
not a Java dependency. `time.TimeWritesNoIssueFactTest` and `staffing.StaffingReadsNoTimeEntryTest`
are the two precedents and are copied rather than re-argued.

**`issue_time_rollup` is deliberately not read.** It carries approved and pending hours *per issue*,
and an invoice needs the entries — who, when, how many, on which period — not their sum. Reading it
would also make `billing` the projection's first outside consumer for a question it cannot answer.

**A client's name comes from `project.ClientDirectory`**, which `crm` implements — not from a
`billing.ClientFacts`. The `*Facts` direction is downstream-to-upstream and always native SQL;
`project` → `crm` is the reverse, held by an interface in the foundation module precisely so ArchUnit
is not the only thing keeping it honest. If a method is missing, it is added to `ClientDirectory` and
implemented in `crm` — two files outside this module, and each edit is a decision to state.

### D-n — The money-boundary tests are extended, never rewritten

Three tests, and each has a different instruction:

| Test | What happens to it |
|---|---|
| `time.TimeMoneyBoundaryTest` | **Extended.** Its four arms stand, `everyMoneyKeyInTheCatalogueIsCoveredByThisTest` above all — that is what makes the boundary grow-proof. It gains an Account Manager arm: holds `client:read`, holds not one money key, refused every billing path. |
| `crm.CrmCreatesNoLoginTest.theClientSurfaceCarriesNoMoneyWord` | **Extended, not moved.** Its subject is `ClientDetail`, which still carries no money word, so the assertion does not change. It gains a sibling naming `/clients/{clientId}/billing` as the one path under a `clients` URL where a money word is now allowed, and only under `billing:read`. |
| `billing.BillingMoneyBoundaryTest` | **New**, and it carries the behavioural half. |

`BillingMoneyBoundaryTest` **derives the paths it asserts over from `docs/specs/openapi.yaml`** — every
path item tagged `billing` — rather than listing them. The working agreement's rule applies literally:
*a catalogue-wide invariant needs a computed check, not a careful reading.* A thirteenth endpoint
added later without a gate reddens this test instead of shipping.

It asserts the **lack** of a permission and the refusal that follows, never the presence of a role.

### D-o — Billing is absent from the rail, and it is the fifth entry that is

`billing.html`'s own callout: *"Switch the top bar to Test Engineer or Account Manager and this entry
disappears from the sidebar entirely — not greyed out, not empty. It is not their screen."*

The rail's standing rule is **disable-don't-hide** (§31), and this is its fifth recorded exception,
after the two audit entries, the three credential entries and Clients. The argument is the one
`ApiTokenService` gives and the Clients entry restates: **the read is refused rather than narrowed**,
so the whole screen behind the entry exists only to say no, and an entry into that refusal is the
same refusal one click earlier. A Test Engineer has no business learning from a sidebar what their
employer bills.

It is gated on `billing:read` — the key every money-holding role holds, and the one every billing read
on this surface requires. Placement: in the ungrouped block outside the project frame, **after
`TIMESHEETS`**, for `TIMESHEETS`' own reason inverted — a timesheet is personal and spans projects, an
invoice is the company's and spans projects, and neither is changed by the project switcher.

### D-p — A project with no client cannot be invoiced, and nothing marks that as a deficiency

`project.client_id` is nullable forever and there is no synthetic "Internal" client row (scope D1
rule 1). So a project may have: no client → no rate card → no priceable hours; and it may have a
budget anyway, in hours, and burn against it perfectly well.

`billing.html` draws exactly this: **Internal Platform · None (internal) · budget — · 126h approved ·
"no budget set"**, in the same table as three billed projects, with no warning tone and no call to
action. Every surface this module builds reads correctly for the owner's own case — a company that
builds its own products, has no clients at all, and is never told it is missing one.

### D-q — What this module does not do, and will not

Named here so a reviewer can grep for each and find only these lines:

- **No fixed-fee invoicing and no retainer** (D24). Both are additive later — a fixed-fee line is
  milestone-triggered rather than hours-derived, a retainer adds a balance — and neither changes the
  T&M path, so deferring costs no rework.
- **No margin, and no cost rate.** `staffing` was scoped to carry a person's cost rate and did not
  build one; margin is revenue minus cost and this module has only revenue.
- **No AI-assistant billing.** An agent's hours are a cost, never a line (D24), and `billing.html`
  says so on the screen.
- **No running balance per client, and no payment tracking.** Issuing does not send anything —
  *"sending is your mail client's job"* — and nothing here records that an invoice was paid.
- **No PDF.** `invoice.html`'s `Preview PDF` button has no endpoint and no screen draws one.

---
## File structure

| File | Responsibility | Task |
|---|---|---|
| `docs/specs/openapi.yaml` | 13 new path items, ~18 new schemas, under a new `billing` tag and the existing `clients` tag | T1 |
| `frontend/src/api/schema.d.ts` · `packages/client/src/schema.ts` | the two generated copies, regenerated in T1's commit | T1 |
| `frontend/src/test/fixtures.ts` · `packages/client/src/test/fixtures.ts` | one factory per new resource; **not** byte-identical to each other | T1 |
| `packages/cli/src/{reads,writes}.ts` | the `billing` and `rate-card` command families | T1 |
| `packages/mcp/src/{reads,writes}.ts` | tools 40–43 | T1 |
| `docs/specs/2026-08-08-mcp-tool-and-command-surface.md` | §3.11 and tool-table rows 40–43 | T1 |
| `backend/src/main/resources/db/migration/V12__billing.sql` | seven tables, seven FORCE policies, the reasoning in block comments | T2 |
| `backend/src/test/java/com/tcms/tenancy/SchemaConventionTest.java` | 102 → 109 | T2 |
| `com/tcms/billing/package-info.java` | `@ApplicationModule(allowedDependencies = {api, tenancy, identity, project})` and the module's two prohibitions | T3 |
| `com/tcms/billing/TimeFacts.java` | the **only** file naming `time_entry` / `timesheet_period` | T3 |
| `com/tcms/billing/IssueFacts.java` | the **only** file naming `issue_fact` | T3 |
| `com/tcms/billing/PricingService.java` | the **one** place a rate is chosen (D-d) | T3 |
| `com/tcms/billing/RateCard*.java` · `RateCardService` · `RateCardController` | the card, its entries, its five endpoints | T3 |
| `com/tcms/billing/Budget*.java` · `BudgetService` · `BudgetController` | the budget, the company table and the project-scope burn (D-h) | T3 |
| `com/tcms/billing/Expense*.java` · `ExpenseService` · `ExpenseController` | recorded, never invoiced, no screen (D-j) | T3 |
| `com/tcms/billing/Invoice*.java` · `InvoiceService` · `InvoiceController` | draft as a query, issue as a record (D-c) | T4 |
| `com/tcms/billing/InvoiceNumbers.java` | `MAX(seq)+1`, unique constraint, one retry (D-k) | T4 |
| `com/tcms/billing/ClientBillingService.java` | `GET /clients/{clientId}/billing`, gated `client:read`, `amountsVisible` (D-i) | T4 |
| `com/tcms/billing/BillingSummaryService.java` | `GET /billing/summary`, per-currency (D-l) | T4 |
| `backend/src/test/java/com/tcms/billing/` | `BillingTestBase`, `PricingTest`, `BudgetBurnTest`, `InvoiceLifecycleTest`, `ClientBillingSummaryTest`, `BillingMoneyBoundaryTest`, `BillingReadsAndNeverWritesTest` | T3 · T4 |
| `com/tcms/DemoWorkspaceSeeder.java` | `seedBilling` — cards, budgets, one issued invoice, one draft | T4 |
| `frontend/src/api/queries/billing.ts` | every billing read/write hook, behind the existing barrel | T5 |
| `frontend/src/api/queries/keys.ts` | `billing` + `billingRoot` in the **one** factory | T5 |
| `frontend/src/features/billing/BillingPage.tsx` · `RateCardPanel` · `BudgetPanel` · `InvoiceListPanel` · `DraftInvoiceDialog` · `RateCardDialog` · `BudgetDialog` | `billing.html` | T5 |
| `frontend/src/features/billing/InvoicePage.tsx` · `InvoiceLines` · `InvoiceEntries` | `invoice.html` | T5 |
| `frontend/src/app/AppShell.tsx` · `router.tsx` | the fifth gated rail entry (D-o) and two routes | T5 |
| `frontend/src/lib/devFaults.ts` · `labels.ts` | `tcmsFaults.billing(…)`; `InvoiceStatus` / `BudgetBasis` / `RateCardScope` labels | T5 |
| `frontend/src/features/clients/BillingSlot.tsx` | body replaced: the five-column table and the two tiles | T6 |
| `frontend/src/features/clients/ClientRateCardPanel.tsx` | `client.html`'s rate-card panel, a link into `/billing` | T6 |
| `frontend/src/features/projects/ProjectOverviewPage.tsx` | the Budget burn panel (D-h) | T6 |
| `frontend/src/features/settings/GeneralSettingsPage.tsx` | the Time panel's Budget row | T6 |
| `docs/features.md` · `docs/plans/009-…-modules.md` | the close-out | T6 |

---

## T1 — The billing contract, both generated clients, and the CLI/MCP verbs

**Deliverable:** Thirteen path items — twelve under a new `billing` tag and one under `clients` —
in `docs/specs/openapi.yaml`, every schema carrying its `required` array; both generated clients
regenerated from it; a factory per new resource in both fixture files; the CLI's `billing` and
`rate-card` families; four MCP tools; and the surface doc listing them. Every endpoint answers a real
`404` until T3 and T4.
**Depends on:** none
**Stack:** `ts` · **Agent:** `tooling-engineer`

**Files:**
- Modify: `docs/specs/openapi.yaml`
- Modify: `frontend/src/api/schema.d.ts` (generated), `packages/client/src/schema.ts` (generated)
- Modify: `frontend/src/test/fixtures.ts`, `packages/client/src/test/fixtures.ts`
- Modify: `packages/cli/src/reads.ts`, `packages/cli/src/writes.ts`
- Modify: `packages/mcp/src/reads.ts`, `packages/mcp/src/writes.ts`
- Modify: `docs/specs/2026-08-08-mcp-tool-and-command-surface.md`

- [ ] **Step 1: The path items**

Thirteen, in this order in the file, after the `clients` block:

| Path | Method | Permission | Serves |
|---|---|---|---|
| `/billing/summary` | `GET` | `billing:read` | `billing.html`'s four KPI tiles |
| `/rate-cards` | `GET` | `ratecard:read` | every active client, card-or-null |
| `/rate-cards` | `POST` | `ratecard:manage` | create a card for a client |
| `/rate-cards/{rateCardId}` | `GET` | `ratecard:read` | one card and its entries |
| `/rate-cards/{rateCardId}` | `PUT` | `ratecard:manage` | full replacement, entries included |
| `/rate-cards/{rateCardId}` | `DELETE` | `ratecard:manage` | remove a card |
| `/budgets` | `GET` | `billing:read` | `billing.html`'s Project budgets table |
| `/projects/{projectId}/budget` | `PUT` | `billing:budget` | set or replace |
| `/projects/{projectId}/budget` | `DELETE` | `billing:budget` | remove |
| `/projects/{projectId}/budget-burn` | `GET` | **`project:read`** | the PM's percentage (D-h) |
| `/invoices` | `GET` | `billing:read` | the invoice list |
| `/invoices` | `POST` | `billing:invoice` | draft one |
| `/invoices/{invoiceId}` | `GET` | `billing:read` | draft (computed) or issued (stored) |
| `/invoices/{invoiceId}` | `DELETE` | `billing:invoice` | discard a draft; `409` on issued |
| `/invoices/{invoiceId}/issue` | `POST` | `billing:issue` | write the lines, assign the number |
| `/expenses` | `GET` | `billing:read` | expenses, by project and date |
| `/expenses` | `POST` | `billing:expense` | record one |
| `/expenses/{expenseId}` | `DELETE` | `billing:expense` | remove one |
| `/clients/{clientId}/billing` | `GET` | **`client:read`** | `client.html`'s tiles and table (D-i) |

(Twelve path *items* under `billing` — `/rate-cards`, `/invoices`, `/expenses` and
`/projects/{projectId}/budget` each carry more than one method — plus one under `clients`.)

- [ ] **Step 2: The schemas, each with its `required` array**

```yaml
    Money:
      type: object
      description: |
        A monetary figure and the currency it is in, always together. There is no bare number
        anywhere on this surface: a company with a EUR client and a CHF client has no single
        total, and a naked `amount` is the field that eventually gets summed across two of them.
      required: [amount, currency]
      properties:
        amount: { type: number, format: double }
        currency:
          type: string
          minLength: 3
          maxLength: 3
          description: ISO 4217, upper case. Determined by the client's rate card, never chosen on a form.

    RateCardScope:
      type: string
      enum: [PERSON, ROLE, ALL_ROLES]
      description: |
        How specific an entry is, and the order the rate ladder walks (019 D-d): a PERSON entry
        naming the person who logged the hour, then a ROLE entry naming a role that person holds
        on that project, then ALL_ROLES. Where two ROLE entries match, the higher rate wins.

    RateCardEntry:
      type: object
      required: [id, scope, hourlyRate, effectiveFrom, label]
      properties:
        id: { type: string, format: uuid }
        scope: { $ref: '#/components/schemas/RateCardScope' }
        personId: { type: string, format: uuid, nullable: true }
        roleId: { type: string, format: uuid, nullable: true }
        hourlyRate: { type: number, format: double }
        effectiveFrom: { type: string, format: date }
        label:
          type: string
          description: |
            What this entry applies to, resolved server-side — `Mira Sund`, `Test Lead`,
            `All roles`. The client renders it; it never joins a person or a role to build it.

    RateCard:
      type: object
      required: [id, client, currency, entries]
      properties:
        id: { type: string, format: uuid }
        client: { $ref: '#/components/schemas/ClientRef' }
        currency: { type: string, minLength: 3, maxLength: 3 }
        entries:
          type: array
          items: { $ref: '#/components/schemas/RateCardEntry' }
      description: |
        One per client — `uq_rate_card_client`. The currency lives here, which is what makes an
        invoice's currency a property of its client rather than a form field, and cross-client
        invoicing structurally impossible.

    RateCardSlot:
      type: object
      required: [client, card]
      properties:
        client: { $ref: '#/components/schemas/ClientRef' }
        card:
          allOf: [{ $ref: '#/components/schemas/RateCard' }]
          nullable: true
      description: |
        A client and its card, or a client and `null` — `billing.html`'s Kessler Health row, which
        reads *no rate card yet* with a `Set` button. Every ACTIVE client has a slot, so the
        absence is a row rather than a missing one.
```

`RateCardInput`, `RateCardEntryInput`, `BudgetInput`, `InvoiceDraftInput` and `ExpenseInput` follow
the same shape with their own `required` arrays. **No `default:` keyword on any of them** — see the
prompt's second trap.

```yaml
    BudgetBasis:
      type: string
      enum: [HOURS, MONEY]

    Budget:
      type: object
      required: [projectId, projectName, projectKey, basis, approvedHours, burnPercent]
      properties:
        projectId: { type: string, format: uuid }
        projectKey: { type: string }
        projectName: { type: string }
        client:
          allOf: [{ $ref: '#/components/schemas/ClientRef' }]
          nullable: true
          description: Null for an internal project, and that is not a deficiency (019 D-p).
        basis: { $ref: '#/components/schemas/BudgetBasis' }
        hours: { type: number, format: double, nullable: true }
        money:
          allOf: [{ $ref: '#/components/schemas/Money' }]
          nullable: true
        approvedHours: { type: number, format: double }
        pricedToDate:
          allOf: [{ $ref: '#/components/schemas/Money' }]
          nullable: true
          description: |
            Null where the project's client has no rate card — the burn is then UNKNOWN rather
            than zero, and every renderer says so.
        burnPercent: { type: number, format: double, nullable: true }

    BudgetBurn:
      type: object
      required: [basis, approvedHours]
      properties:
        basis:
          allOf: [{ $ref: '#/components/schemas/BudgetBasis' }]
          nullable: true
        budgetHours: { type: number, format: double, nullable: true }
        approvedHours: { type: number, format: double }
        burnPercent: { type: number, format: double, nullable: true }
      description: |
        **The project-scope view, and it has no amount and no currency member at all** (019 D-h).
        Gated on `project:read`, because there is nothing on it to withhold —
        `project-overview.html`'s panel reads *"hours, no rates"*. A MONEY-basis budget renders
        as `burnPercent` alone; `budgetHours` is null for it. A `basis` of null means the project
        has no budget, which is a state and not an error.
```

```yaml
    InvoiceStatus:
      type: string
      enum: [DRAFT, ISSUED]

    InvoiceLine:
      type: object
      required: [projectId, projectName, appliesTo, scope, hours, rate, amount]
      properties:
        projectId: { type: string, format: uuid }
        projectName: { type: string }
        appliesTo:
          type: string
          description: '`Mira Sund` or `Test Lead` — what the rate was matched on.'
        scope: { $ref: '#/components/schemas/RateCardScope' }
        hours: { type: number, format: double }
        rate: { type: number, format: double }
        amount: { $ref: '#/components/schemas/Money' }
      description: |
        For an ISSUED invoice these are stored rows carrying the rate AS APPLIED — a card edited
        next month must not reprice an invoice sent last month. For a DRAFT they are computed on
        every read and stored nowhere (019 D-c).

    InvoiceEntry:
      type: object
      required: [timeEntryId, spentOn, personName, hours, subjectKey, subjectTitle, periodApprovedAt, periodApprovedBy]
      properties:
        timeEntryId: { type: string, format: uuid }
        spentOn: { type: string, format: date }
        personName: { type: string }
        hours: { type: number, format: double }
        subjectKey: { type: string }
        subjectTitle: { type: string }
        periodApprovedAt: { type: string, format: date-time }
        periodApprovedBy: { type: string }
      description: |
        **`periodApprovedAt` / `periodApprovedBy` are the decision on that PERSON'S PERIOD, and
        the field names say so deliberately** (019 D-f, 016 D-i). A period spans projects and
        `time:approve` is a PROJECT-scope grant, so approving one approves entries against
        projects the approver cannot see. *These hours were approved* is a claim the data
        supports; *somebody with authority over this project approved them* is not, and no field
        here makes it.

    ExcludedHours:
      type: object
      required: [reason, hours, detail]
      properties:
        reason: { type: string, enum: [NOT_APPROVED, NO_RATE] }
        hours: { type: number, format: double }
        detail:
          type: string
          description: Who and how much — *Tomas Ek 24h, Jun Park 14h*.

    Invoice:
      type: object
      required: [id, client, periodStart, periodEnd, status, currency, total, lineCount, approvedHours]
      properties:
        id: { type: string, format: uuid }
        number: { type: string, nullable: true, description: Null while DRAFT; assigned on issue. }
        client: { $ref: '#/components/schemas/ClientRef' }
        periodStart: { type: string, format: date }
        periodEnd: { type: string, format: date }
        status: { $ref: '#/components/schemas/InvoiceStatus' }
        currency: { type: string, minLength: 3, maxLength: 3 }
        total: { $ref: '#/components/schemas/Money' }
        lineCount: { type: integer }
        approvedHours: { type: number, format: double }
        issuedAt: { type: string, format: date-time, nullable: true }
        issuedBy: { type: string, nullable: true }

    InvoiceDetail:
      type: object
      required: [invoice, lines, entries, excluded, budgetEffect, termsDays]
      properties:
        invoice: { $ref: '#/components/schemas/Invoice' }
        billTo:
          allOf: [{ $ref: '#/components/schemas/ClientContact' }]
          nullable: true
        termsDays: { type: integer }
        lines:
          type: array
          items: { $ref: '#/components/schemas/InvoiceLine' }
        entries:
          type: array
          items: { $ref: '#/components/schemas/InvoiceEntry' }
        excluded:
          type: array
          items: { $ref: '#/components/schemas/ExcludedHours' }
          description: |
            Both buckets, always counted and never dropped (019 D-g) — unapproved hours, and
            approved hours no rate card can price. An empty array means every approved hour in
            the period is on a line.
        budgetEffect:
          type: array
          items: { $ref: '#/components/schemas/Budget' }
```

```yaml
    ClientBillingRow:
      type: object
      required: [periodLabel, periodStart, projectId, projectName, approvedHours, invoiceStatus]
      properties:
        periodLabel: { type: string, description: '`Aug 2026`.' }
        periodStart: { type: string, format: date }
        projectId: { type: string, format: uuid }
        projectName: { type: string }
        approvedHours: { type: number, format: double }
        invoiceId: { type: string, format: uuid, nullable: true }
        invoiceNumber: { type: string, nullable: true }
        invoiceStatus:
          allOf: [{ $ref: '#/components/schemas/InvoiceStatus' }]
          nullable: true
        amount:
          allOf: [{ $ref: '#/components/schemas/Money' }]
          nullable: true

    ClientBillingSummary:
      type: object
      required: [amountsVisible, approvedHoursToDate, approvedHoursThisQuarter, invoiceCount, rows]
      properties:
        amountsVisible:
          type: boolean
          description: |
            **Whether this caller holds `billing:read`** (019 D-i). Without the flag, `amount:
            null` would mean either *no invoice for this period* or *not your business*, and a
            renderer cannot tell them apart — so it would draw an empty column at an Account
            Manager, which `client.html` explicitly does not. When false, every `amount` here is
            null and the value column is ABSENT, not empty.
        approvedHoursToDate: { type: number, format: double }
        approvedHoursThisQuarter: { type: number, format: double }
        invoiceCount: { type: integer }
        lastIssuedAt: { type: string, format: date-time, nullable: true }
        rows:
          type: array
          items: { $ref: '#/components/schemas/ClientBillingRow' }

    BillingSummary:
      type: object
      required: [invoicedYearToDate, draftableNow, unapprovedHours, clientsBilled]
      properties:
        invoicedYearToDate:
          type: array
          items: { $ref: '#/components/schemas/Money' }
          description: |
            **One entry per currency** (019 D-l). `billing.html` draws one €52.4k tile while its
            own rate-card table carries a EUR client and a CHF client two rows apart; that is a
            single-currency render, and one number across two currencies is wrong in both.
        draftableNow:
          type: array
          items: { $ref: '#/components/schemas/Money' }
        unapprovedHours: { type: number, format: double }
        clientsBilled: { type: integer }
```

- [ ] **Step 3: Regenerate both clients**

```bash
cd frontend && npm run gen:api
cd ../packages/client && npm run gen:api
```

Expected: `frontend/src/api/schema.d.ts` and `packages/client/src/schema.ts` both change, and
`PermissionKey` is unchanged — the seven money keys have been in the catalogue since `V2`.

- [ ] **Step 4: A factory per resource in both fixture files**

They are **not** byte-identical: the frontend spells a named type, the package spells
`Schemas['X']`.

```ts
// frontend/src/test/fixtures.ts
export function anInvoice(overrides: Partial<Invoice> = {}): Invoice {
  return {
    id: '00000000-0000-4000-8000-0000000000b1',
    number: null,
    client: { id: '00000000-0000-4000-8000-0000000000c1', name: 'Acme Retail' },
    periodStart: '2026-09-01',
    periodEnd: '2026-09-30',
    status: 'DRAFT',
    currency: 'EUR',
    total: { amount: 6096, currency: 'EUR' },
    lineCount: 4,
    approvedHours: 68,
    issuedAt: null,
    issuedBy: null,
    ...overrides,
  }
}
```

Add `aRateCard`, `aRateCardSlot`, `aBudget`, `aBudgetBurn`, `anInvoiceDetail`, `anInvoiceLine`,
`anInvoiceEntry`, `aClientBillingSummary`, `aBillingSummary`, `anExpense` — and their
`Schemas['…']` twins in `packages/client/src/test/fixtures.ts`.

- [ ] **Step 5: The CLI families**

```
tcms billing summary
tcms billing budgets
tcms billing invoices [--client <id>] [--status DRAFT|ISSUED]
tcms billing invoice <invoiceId>
tcms billing draft --client <id> --from <date> --to <date>
tcms billing issue <invoiceId>
tcms billing discard <invoiceId>
tcms billing budget set <projectId> --basis HOURS|MONEY --amount <n> [--currency EUR]
tcms billing budget clear <projectId>
tcms expense record --project <id> --on <date> --amount <n> --currency EUR --what "<text>"
tcms expense list --project <id>
tcms expense remove <expenseId>
tcms rate-card list
tcms rate-card show <rateCardId>
tcms rate-card set --client <id> --currency EUR --entry <scope>:<target>:<rate>:<from> …
tcms rate-card remove <rateCardId>
```

- [ ] **Step 6: The four MCP tools**

| # | Tool | Gate | Arms / reads |
|---|---|---|---|
| 40 | `tcms_read_billing` | `billing:read` | summary · budgets · invoices · one invoice · expenses |
| 41 | `tcms_write_billing` | **any of** `billing:budget` / `billing:expense` / `billing:invoice` / `billing:issue` | `set-budget` · `clear-budget` · `record-expense` · `remove-expense` · `draft-invoice` · `discard-invoice` · `issue-invoice` |
| 42 | `tcms_read_rate_card` | `ratecard:read` | the slots · one card |
| 43 | `tcms_write_rate_card` | `ratecard:manage` | `set` · `remove` |

§3.11 of the surface doc records four things, and each is a question a reader will ask:

- **`tcms_write_billing` spans FOUR permissions**, where 018's `tcms_write_client` spanned two. §2 is
  satisfied by the arm table, as it was there. Splitting would produce four tools listed to an
  identical audience: §A3 grants the seven money keys as a **block** to Instance Admin, Company Admin
  and Finance and to nobody else, so there is no caller in this product who holds one and not the
  rest. The arm table stays anyway, because a role that splits them later must not silently widen.
- **`issue-invoice` carries `destructiveHint`**, and it is the only arm on this surface that does.
  Issuing is not deletion, but it is the one irreversible act in the module: the lines become rows
  and no operation can change them afterwards.
- **No arm returns a margin, a cost rate or an AI-assistant charge**, and none ever will (D-q).
- **`tcms_read_billing` does not answer `/projects/{id}/budget-burn`.** That endpoint is
  `project:read` and belongs to the project surface, not the money surface; putting it here would
  list a money tool to every engineer in the company.

- [ ] **Step 7: Gate and commit**

```bash
~/bin/tcms-remote sync
~/bin/tcms-remote packages npm run build
~/bin/tcms-remote packages npm run lint --workspaces
~/bin/tcms-remote packages npm run test:run --workspaces
~/bin/tcms-remote frontend npm run build
git add docs/specs/openapi.yaml frontend/src/api/schema.d.ts packages/client/src/schema.ts \
        frontend/src/test/fixtures.ts packages/client/src/test/fixtures.ts \
        packages/cli/src packages/mcp/src \
        docs/specs/2026-08-08-mcp-tool-and-command-surface.md docs/plans/019-billing.md
git commit -m "feat(billing): the billing contract, both clients, and the money verbs"
```

---
## T2 — `V12` — the seven billing tables

**Deliverable:** `V12__billing.sql` applied; `rate_card`, `rate_card_entry`, `budget`, `invoice`,
`invoice_line`, `invoice_line_entry` and `expense` exist with `company_id` leading every composite
index and all seven under `FORCE ROW LEVEL SECURITY`; `SchemaConventionTest`'s count moves 102 → 109;
the ERD carries the seven tables.
**Depends on:** T1 (the contract the columns serve)
**Stack:** `be` · **Agent:** `data-architect`

**Files:**
- Create: `backend/src/main/resources/db/migration/V12__billing.sql`
- Modify: `backend/src/test/java/com/tcms/tenancy/SchemaConventionTest.java:395-440`
- Modify: `docs/specs/2026-07-30-tcms-erd.md`

- [ ] **Step 1: Read the directory, not this plan**

```bash
ls backend/src/main/resources/db/migration/
```

Expected on 2026-09-05: `V1 … V11`, so the file is `V12__billing.sql`. **If it shows anything else,
the directory wins and this plan is corrected** — see D-a.

- [ ] **Step 2: The header**

Same shape as `V11__crm_clients.sql`'s: the version and why the directory said so, then the module's
prohibitions, so a reader who opens only this file learns what it may not do.

```sql
-- =============================================================================
-- V12 — billing: rate cards, budgets, invoices and expenses (plan 009 M8, 019)
--
-- THE VERSION IS V12, AND THE DIRECTORY IS WHAT SAYS SO. `ls` on 2026-09-05:
-- V1..V11, V11 being Mc's. Plan 009's column reserved V10, then V11, then V12
-- for this module, striking each through in turn -- SEVEN entries for one
-- module, and the reason the number is right this time is that it was read, not
-- derived. `out-of-order` is unset in application.yml, so a lower-numbered
-- migration arriving after a higher one is NEVER APPLIED, and Testcontainers
-- builds every test database from empty, so no suite here can show it.
--
-- SEVEN TABLES, NOT THE SIX PLAN 009 NAMED. invoice_line_entry is the seventh
-- and story M2 requires it: "the time entries show which invoice they are on".
-- This module MAY NOT write a column on time_entry, so the link is a join owned
-- here with NO FOREIGN KEY to that table -- the polymorphic crossing scope §3
-- prescribes, already paid for by work_item_link, doc_link and execution_fact.
-- It is also the only thing standing between this module and double-billing.
--
-- A DRAFT STORES NO LINES (019 D-c). invoice_line and invoice_line_entry are
-- written EXACTLY ONCE, inside the issue transaction, and never updated. That is
-- why neither carries lock_version: optimistic locking guards a concurrent
-- UPDATE, and nothing updates these rows. A draft is a header plus a live
-- computation over the approved entries that exist now, which is what makes
-- invoice.html's "approve them and this draft recalculates" true with no
-- recalculate endpoint -- and what makes the lock structural rather than a check
-- somebody can forget on the sixth handler.
--
-- KEY_SEQUENCE IS NOT TOUCHED (019 D-k). invoice.seq is this module's own
-- counter with uq_invoice_seq as its arbiter. Widening key_sequence would cost a
-- CHECK change, a backfill, AND a CREATE OR REPLACE of tcms_seed_company_defaults
-- -- and CREATE OR REPLACE FUNCTION replaces the WHOLE body, which V11's header
-- names as exactly how this gets broken. Four migrations running have declined
-- to touch that function. So does this one.
--
-- NO MARGIN COLUMN, NO COST RATE, NO FIXED FEE, NO RETAINER, NO BALANCE, NO
-- PAYMENT. Time and materials only (scope D24) and every one of those absences
-- is a decision, not a gap -- see 019 D-q. An AI assistant's hours are a COST,
-- never a line: `expense` is where such a cost would be recorded, and no path in
-- this module turns one into an invoice line.
-- =============================================================================
```

- [ ] **Step 3: `rate_card` and `rate_card_entry`**

```sql
CREATE TABLE rate_card (
    id           UUID        PRIMARY KEY,
    company_id   UUID        NOT NULL REFERENCES company(id),
    client_id    UUID        NOT NULL REFERENCES client(id) ON DELETE CASCADE,
    currency     TEXT        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by   UUID,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by   UUID,
    lock_version BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT ck_rate_card_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT uq_rate_card_client   UNIQUE (company_id, client_id)
);
COMMENT ON TABLE rate_card IS
    'ONE PER CLIENT (019 D-d). The currency lives HERE and not on the invoice, which is what makes '
    'an invoice''s currency a property of its client rather than a field on a form -- and what makes '
    'a cross-client invoice structurally impossible rather than merely refused. billing.html carries '
    'a EUR client and a CHF client two rows apart, which is why the company totals on '
    '/billing/summary are ARRAYS of {amount, currency} (019 D-l).';
COMMENT ON COLUMN rate_card.client_id IS
    'NOT NULL, so an internal project -- project.client_id is nullable forever and there is no '
    'synthetic "Internal" client (scope D1 rule 1) -- can never be priced. That is the intended '
    'shape: billing.html draws Internal Platform with no client, no budget and 126 approved hours, '
    'in the same table as three billed projects, with no warning tone (019 D-p).';
CREATE INDEX ix_rate_card_client ON rate_card (company_id, client_id);

CREATE TABLE rate_card_entry (
    id             UUID          PRIMARY KEY,
    company_id     UUID          NOT NULL REFERENCES company(id),
    rate_card_id   UUID          NOT NULL REFERENCES rate_card(id) ON DELETE CASCADE,
    scope          TEXT          NOT NULL,
    person_id      UUID          REFERENCES app_user(id) ON DELETE CASCADE,
    role_id        UUID          REFERENCES role(id)     ON DELETE CASCADE,
    hourly_rate    NUMERIC(12,2) NOT NULL,
    effective_from DATE          NOT NULL,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by     UUID,
    CONSTRAINT ck_rate_card_entry_scope CHECK (scope IN ('PERSON','ROLE','ALL_ROLES')),
    CONSTRAINT ck_rate_card_entry_shape CHECK (
        (scope = 'PERSON'    AND person_id IS NOT NULL AND role_id IS     NULL) OR
        (scope = 'ROLE'      AND person_id IS     NULL AND role_id IS NOT NULL) OR
        (scope = 'ALL_ROLES' AND person_id IS     NULL AND role_id IS     NULL)),
    CONSTRAINT ck_rate_card_entry_rate CHECK (hourly_rate >= 0)
);
COMMENT ON TABLE rate_card_entry IS
    'THE EFFECTIVE DATE IS HERE AND NOT ON THE CARD: billing.html''s Acme rows read "from 1 Jan 2026" '
    'for two roles and "from 1 Jul 2026" for one person. The rate for an hour is the most specific '
    'entry effective on or before that hour''s spent_on -- PERSON, then ROLE, then ALL_ROLES -- and '
    'where two ROLE entries match, the HIGHER wins (019 D-d). ck_rate_card_entry_shape is the same '
    'device as ck_time_entry_subject_shape one migration over: a shape the database holds is one the '
    'application cannot drift from.';
CREATE INDEX ix_rate_card_entry_card
    ON rate_card_entry (company_id, rate_card_id, effective_from DESC);
```

- [ ] **Step 4: `budget` and `expense`**

```sql
CREATE TABLE budget (
    id           UUID          PRIMARY KEY,
    company_id   UUID          NOT NULL REFERENCES company(id),
    project_id   UUID          NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    basis        TEXT          NOT NULL,
    amount       NUMERIC(14,2) NOT NULL,
    currency     TEXT,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by   UUID,
    updated_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_by   UUID,
    lock_version BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT ck_budget_basis  CHECK (basis IN ('HOURS','MONEY')),
    CONSTRAINT ck_budget_amount CHECK (amount > 0),
    CONSTRAINT ck_budget_shape  CHECK (
        (basis = 'HOURS' AND currency IS     NULL) OR
        (basis = 'MONEY' AND currency IS NOT NULL AND currency ~ '^[A-Z]{3}$')),
    CONSTRAINT uq_budget_project UNIQUE (company_id, project_id)
);
COMMENT ON TABLE budget IS
    'A ceiling in HOURS or in MONEY, one per project. What a Project Manager reads off it is a '
    'PERCENTAGE and, for an hours budget, the hours -- never an amount and never a currency (019 '
    'D-h): /projects/{id}/budget-burn is gated on project:read and its schema has no money member at '
    'all. project-overview.html''s panel hint reads "hours, no rates" and its copy reads "What an '
    'hour costs is Finance''s, and is not on this page."';
CREATE INDEX ix_budget_project ON budget (company_id, project_id);

CREATE TABLE expense (
    id          UUID          PRIMARY KEY,
    company_id  UUID          NOT NULL REFERENCES company(id),
    project_id  UUID          NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    incurred_on DATE          NOT NULL,
    description TEXT          NOT NULL,
    amount      NUMERIC(14,2) NOT NULL,
    currency    TEXT          NOT NULL,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by  UUID,
    CONSTRAINT ck_expense_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_expense_amount   CHECK (amount <> 0),
    CONSTRAINT ck_expense_what     CHECK (length(btrim(description)) > 0)
);
COMMENT ON TABLE expense IS
    'RECORDED AND NEVER INVOICED (scope D24, 019 D-j). D24 admits exactly one line shape -- approved '
    'time at a rate-card rate -- and invoice.html draws no expense row. This table exists because '
    'billing:expense is IN THE CATALOGUE, and a permission with no reachable endpoint is the '
    'unreachable-row defect §43 names. Its callers are the CLI and MCP verbs; NO MOCKUP DRAWS AN '
    'EXPENSES PANEL, so no screen is built, and that absence is a decision rather than an unfinished '
    'task. An AI assistant''s usage is a cost and would be recorded here -- never as a line.';
CREATE INDEX ix_expense_project ON expense (company_id, project_id, incurred_on);
```

- [ ] **Step 5: `invoice`, `invoice_line`, `invoice_line_entry`**

```sql
CREATE TABLE invoice (
    id           UUID          PRIMARY KEY,
    company_id   UUID          NOT NULL REFERENCES company(id),
    client_id    UUID          NOT NULL REFERENCES client(id),
    seq          BIGINT,
    number       TEXT,
    period_start DATE          NOT NULL,
    period_end   DATE          NOT NULL,
    status       TEXT          NOT NULL DEFAULT 'DRAFT',
    currency     TEXT          NOT NULL,
    terms_days   INTEGER       NOT NULL DEFAULT 30,
    total_amount NUMERIC(14,2),
    bill_to_id   UUID          REFERENCES client_contact(id) ON DELETE SET NULL,
    issued_at    TIMESTAMPTZ,
    issued_by    UUID          REFERENCES app_user(id),
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by   UUID,
    lock_version BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT ck_invoice_status CHECK (status IN ('DRAFT','ISSUED')),
    CONSTRAINT ck_invoice_period CHECK (period_end >= period_start),
    CONSTRAINT ck_invoice_issued CHECK (
        (status = 'DRAFT'  AND seq IS     NULL AND number IS     NULL
                           AND issued_at IS NULL AND total_amount IS NULL) OR
        (status = 'ISSUED' AND seq IS NOT NULL AND number IS NOT NULL
                           AND issued_at IS NOT NULL AND total_amount IS NOT NULL)),
    CONSTRAINT uq_invoice_seq    UNIQUE (company_id, seq),
    CONSTRAINT uq_invoice_number UNIQUE (company_id, number)
);
COMMENT ON TABLE invoice IS
    'A DRAFT IS A HEADER; AN ISSUED INVOICE IS A RECORD (019 D-c). While DRAFT this row carries the '
    'client, the period and nothing else -- no seq, no number, no total, and NOT ONE invoice_line. '
    'Its lines are computed on every read from the approved entries that exist NOW, which is what '
    'makes invoice.html''s "approve them and this draft recalculates" true with no recalculate '
    'endpoint. ck_invoice_issued is that decision written as a constraint: the two states have '
    'different shapes and the database holds the difference.';
COMMENT ON COLUMN invoice.seq IS
    'THIS MODULE''S OWN COUNTER (019 D-k), allocated as MAX(seq)+1 for the company inside the issue '
    'transaction with uq_invoice_seq as the arbiter and ONE retry. Gap-free numbering is not '
    'guaranteed and is not required -- key_sequence''s own table comment says the same for display '
    'keys, for the same reason: a rolled-back transaction burns its number. The contract is '
    'uniqueness and immutability. key_sequence itself is NOT widened; see the header.';
COMMENT ON COLUMN invoice.currency IS
    'Copied from the client''s rate card at draft time and frozen. An invoice whose client''s card '
    'changed currency mid-period would otherwise carry lines in two.';
CREATE INDEX ix_invoice_client ON invoice (company_id, client_id, period_start DESC);
CREATE INDEX ix_invoice_status ON invoice (company_id, status, period_start DESC);

CREATE TABLE invoice_line (
    id           UUID          PRIMARY KEY,
    company_id   UUID          NOT NULL REFERENCES company(id),
    invoice_id   UUID          NOT NULL REFERENCES invoice(id) ON DELETE CASCADE,
    project_id   UUID          NOT NULL REFERENCES project(id),
    scope        TEXT          NOT NULL,
    person_id    UUID          REFERENCES app_user(id),
    role_id      UUID          REFERENCES role(id),
    applies_to   TEXT          NOT NULL,
    hours        NUMERIC(10,2) NOT NULL,
    rate_applied NUMERIC(12,2) NOT NULL,
    amount       NUMERIC(14,2) NOT NULL,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_invoice_line_scope CHECK (scope IN ('PERSON','ROLE','ALL_ROLES')),
    CONSTRAINT ck_invoice_line_hours CHECK (hours > 0)
);
COMMENT ON TABLE invoice_line IS
    'WRITTEN ONCE, INSIDE THE ISSUE TRANSACTION, AND NEVER UPDATED. No lock_version, deliberately: '
    'optimistic locking guards a concurrent UPDATE and nothing updates these rows. That is what "the '
    'lines lock on issue" means here -- there is no operation that can change one, so the '
    'immutability is structural rather than a guard on the sixth handler somebody forgets.';
COMMENT ON COLUMN invoice_line.rate_applied IS
    'THE RATE AS APPLIED, AS A NUMBER -- not a rate_card_entry_id. A card edited next month must not '
    'reprice an invoice sent last month, and a reference resolved at read time would do exactly '
    'that. applies_to is the RESOLVED label ("Mira Sund", "Test Lead") for the same reason: the row '
    'must still read correctly after the person leaves and the role is renamed.';
CREATE INDEX ix_invoice_line_invoice ON invoice_line (company_id, invoice_id, project_id);

CREATE TABLE invoice_line_entry (
    id              UUID          PRIMARY KEY,
    company_id      UUID          NOT NULL REFERENCES company(id),
    invoice_line_id UUID          NOT NULL REFERENCES invoice_line(id) ON DELETE CASCADE,
    time_entry_id   UUID          NOT NULL,
    spent_on        DATE          NOT NULL,
    hours           NUMERIC(5,2)  NOT NULL,
    person_name     TEXT          NOT NULL,
    subject_key     TEXT,
    subject_title   TEXT,
    approved_at     TIMESTAMPTZ   NOT NULL,
    approved_by     TEXT          NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_invoice_line_entry_time UNIQUE (company_id, time_entry_id)
);
COMMENT ON TABLE invoice_line_entry IS
    'THE SEVENTH TABLE, WHICH PLAN 009 DID NOT NAME, and story M2 requires: "the time entries show '
    'which invoice they are on". NO FOREIGN KEY ON time_entry_id, and that is the module rule rather '
    'than an omission -- billing may not write a column on time_entry and may not import time, so '
    'the reference is polymorphic exactly as work_item_link, doc_link and execution_fact are (scope '
    '§3). The cost is the one that pattern always pays: referential integrity is enforced in '
    'application code, and a deleted entry can orphan a row here. It is bounded by the row being a '
    'SNAPSHOT -- hours, person, subject and approval are copied, not joined -- so an orphan still '
    'renders correctly on an invoice sent last quarter.';
COMMENT ON CONSTRAINT uq_invoice_line_entry_time ON invoice_line_entry IS
    'THE ANTI-DOUBLE-BILLING CONSTRAINT, and the reason this table is not optional. An entry already '
    'on an ISSUED invoice is excluded from every later draft, and this is what makes that a fact the '
    'database holds rather than a query somebody remembered to write.';
COMMENT ON COLUMN invoice_line_entry.approved_by IS
    'WHO DECIDED THAT PERSON''S PERIOD -- not who approved this project''s hours, and the difference '
    'is 019 D-f. A period is per person per fortnight and SPANS PROJECTS (016 D-i) while time:approve '
    'is a PROJECT-scope grant, so approving one approves entries against projects the approver cannot '
    'see. "These hours were approved" is a claim the data supports. "Somebody with authority over '
    'this project approved them" is NOT, and no column here makes it. A NAME rather than a user id, '
    'because it is a snapshot: the invoice must read correctly after the approver leaves.';
CREATE INDEX ix_invoice_line_entry_line ON invoice_line_entry (company_id, invoice_line_id);
```

- [ ] **Step 6: RLS on all seven, with both `RAISE` guards**

```sql
DO $$
DECLARE
    t         TEXT;
    protected TEXT[] := ARRAY['rate_card','rate_card_entry','budget','invoice',
                              'invoice_line','invoice_line_entry','expense'];
BEGIN
    FOREACH t IN ARRAY protected LOOP
        IF NOT EXISTS (SELECT 1 FROM information_schema.tables
                        WHERE table_schema = current_schema() AND table_name = t) THEN
            RAISE EXCEPTION 'the RLS list names a table that does not exist: %', t;
        END IF;
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format(
            'CREATE POLICY company_isolation ON %I USING '
            '(company_id = nullif(current_setting(''app.current_company'', true), '''')::uuid)', t);
    END LOOP;

    IF array_length(protected, 1) <> 7 THEN
        RAISE EXCEPTION 'expected 7 new company-isolated tables, found %', array_length(protected, 1);
    END IF;
END $$;
```

- [ ] **Step 7: The computed check, not the careful read**

```bash
docker compose down -v && docker compose up -d db
# after Flyway runs, as the postgres superuser (RLS makes tcms_owner read zero rows):
psql -U postgres -d tcms -c "
SELECT c.relname,
       EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_name = c.relname AND column_name = 'company_id') AS has_company,
       c.relrowsecurity, c.relforcerowsecurity,
       (SELECT count(*) FROM pg_policies p WHERE p.tablename = c.relname) AS policies
  FROM pg_class c
 WHERE c.relname IN ('rate_card','rate_card_entry','budget','invoice',
                     'invoice_line','invoice_line_entry','expense');"
```

Expected: seven rows, `has_company` `t`, `relrowsecurity` `t`, `relforcerowsecurity` `t`,
`policies` `1`. **Reading the file top to bottom is not this step** — that is the pass that missed
five join tables without `company_id`.

- [ ] **Step 8: `SchemaConventionTest` 102 → 109**

```java
                // 109 since V12 (plan 019, module M8) — rate_card, rate_card_entry, budget,
                // invoice, invoice_line, invoice_line_entry and expense. SEVEN, not the six
                // plan 009 named: invoice_line_entry links a line to the time entries behind it,
                // because story M2 requires an entry to show which invoice it is on and this
                // module may not write a column on `time_entry` (019 D-b).
                .isEqualTo(109);
```

- [ ] **Step 9: Gate and commit**

```bash
~/bin/tcms-remote sync
~/bin/tcms-remote backend ./gradlew build
git add backend/src/main/resources/db/migration/V12__billing.sql \
        backend/src/test/java/com/tcms/tenancy/SchemaConventionTest.java \
        docs/specs/2026-07-30-tcms-erd.md docs/plans/019-billing.md
git commit -m "feat(billing): V12 — rate cards, budgets, invoices and expenses"
```

---
## T3 — Rate cards, pricing, budgets and expenses

**Deliverable:** `billing.PricingService` returns the rate for one approved hour by walking D-d's
ladder; the rate-card, budget, budget-burn and expense endpoints serve real rows;
`GET /projects/{projectId}/budget-burn` serves a percentage and hours to a caller holding
`project:read` and carries no currency and no amount; `BillingReadsAndNeverWritesTest` asserts that
`billing.TimeFacts` and `billing.IssueFacts` are each the only file naming their tables and that
nothing in the package writes one.
**Depends on:** T2 (the tables)
**Stack:** `be` · **Agent:** `be-java`

**Files:**
- Create: `backend/src/main/java/com/tcms/billing/package-info.java`, `TimeFacts.java`,
  `IssueFacts.java`, `PricingService.java`, `RateCard.java`, `RateCardEntry.java`,
  `RateCardRepository.java`, `RateCardEntryRepository.java`, `RateCardService.java`,
  `RateCardController.java`, `RateCardView.java`, `RateCardSlotView.java`, `RateCardWriteRequest.java`,
  `Budget.java`, `BudgetRepository.java`, `BudgetService.java`, `BudgetController.java`,
  `BudgetView.java`, `BudgetBurnView.java`, `BudgetWriteRequest.java`, `BudgetBasis.java`,
  `Money.java`, `Expense.java`, `ExpenseRepository.java`, `ExpenseService.java`,
  `ExpenseController.java`, `ExpenseView.java`, `ExpenseWriteRequest.java`
- Create: `backend/src/test/java/com/tcms/billing/BillingTestBase.java`, `PricingTest.java`,
  `BudgetBurnTest.java`, `BillingReadsAndNeverWritesTest.java`
- Possibly modify: `backend/src/main/java/com/tcms/project/ClientDirectory.java` and
  `backend/src/main/java/com/tcms/crm/CrmClientDirectory.java` — **only** if a method is missing

- [ ] **Step 1: `package-info.java` — the module and its two prohibitions**

```java
/**
 * Billing — what an approved hour is worth, what a project may spend, and the invoice that says so
 * (009 module M8, plan 019).
 *
 * <h2>What this module may not do, stated first because both are failures with names</h2>
 *
 * <p>It <strong>reads {@code time}'s entries and the tracker's issue projection, and writes
 * neither.</strong> The whole of the first crossing is {@link com.tcms.billing.TimeFacts} and the
 * whole of the second is {@link com.tcms.billing.IssueFacts}; nothing else here may name
 * {@code time_entry}, {@code timesheet_period} or {@code issue_fact}, and
 * {@code BillingReadsAndNeverWritesTest} asserts both halves over source text, because ArchUnit
 * cannot see the rule — SQL is not a Java dependency. Sixth and seventh instances of a pattern with
 * a name here: {@code work.BoardFacts}, {@code execution.CaseFacts},
 * {@code tracker.release.ReleaseFacts}, {@code time.IssueFacts}, {@code staffing.TimeFacts}.
 *
 * <p>It <strong>may not import {@code crm}.</strong> A client's name arrives through
 * {@link com.tcms.project.ClientDirectory}, declared in a foundation module and implemented by
 * {@code crm}. There is deliberately no {@code billing.ClientFacts}: the {@code *Facts} direction is
 * downstream-to-upstream, and {@code project} → {@code crm} is the reverse, held by an interface
 * precisely so that a rule ArchUnit cannot check is not the only thing keeping it honest.
 *
 * <h2>{@code issue_time_rollup} is not read, and that is not an oversight</h2>
 *
 * <p>It carries approved and pending hours <em>per issue</em>. An invoice needs the entries — who,
 * when, how many, in which period — not their sum. 016 D-c made {@code time.RollupService} that
 * projection's only writer and named M8 as its first outside consumer; this module declines the
 * inheritance, because the question it asks is one that table cannot answer.
 *
 * <h2>Only approved hours against an issue can be priced</h2>
 *
 * <p>Two conditions, and neither is a courtesy filter. <strong>Approved</strong>, because 016 D-d
 * keeps approved and pending apart and nothing in this product sums them — pricing pending hours
 * would bill work nobody has signed for. And <strong>{@code subject_type = 'ISSUE'}</strong>,
 * because {@code ck_time_entry_subject_shape} makes {@code project_id} null for the other four
 * members: an hour with no project has no client, no rate card and no budget. A person's fortnight
 * can be entirely meetings and leave, and be a complete and correct period that produces no invoice
 * line at all.
 *
 * <h2>Allowed dependencies</h2>
 *
 * <p>Four of the five foundation modules; {@code storage} is not among them, because nothing here
 * has an attachment — {@code invoice.html}'s <em>Preview PDF</em> is not built and has no endpoint,
 * and {@code client.html}'s Contracts panel belongs to no module at all (018 D-h). The day either
 * arrives, this list grows by one entry and the growth is the record that it did.
 *
 * <p>{@code identity} is asked <em>what is this person called</em> and never <em>may they</em>. The
 * permission question goes through {@code project.ProjectService}, exactly as in {@code time},
 * {@code staffing} and {@code crm}.
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"api", "tenancy", "identity", "project"})
package com.tcms.billing;
```

- [ ] **Step 2: `TimeFacts` — the only file naming `time_entry` and `timesheet_period`**

```java
@Component
class TimeFacts {

    /**
     * One approved hour, with everything an invoice line needs about it — <strong>as ids, not as
     * names.</strong>
     *
     * <p>{@code personId} and {@code periodDecidedBy} are UUIDs and the caller resolves both
     * through {@link com.tcms.project.PeopleDirectory}, exactly as {@code crm.ClientService}
     * resolves an account manager. V1 line 99 is the reason and it is easy to get wrong:
     * <em>user-facing names come from {@code company_membership.display_name}, NOT from
     * {@code app_user}</em>. Joining {@code app_user} here would serve the wrong name AND would
     * make this class name a third module's table, weakening the one claim it exists to hold.
     */
    record ApprovedEntry(
            UUID timeEntryId,
            UUID personId,
            UUID projectId,
            UUID subjectId,
            LocalDate spentOn,
            BigDecimal hours,
            Instant periodDecidedAt,
            UUID periodDecidedBy) {
    }

    /**
     * Every APPROVED, issue-subject hour on this client's projects inside the window, minus the
     * ones already on an issued invoice.
     *
     * <p>The exclusion is a {@code NOT EXISTS} against {@code invoice_line_entry} — this module's
     * own table, so the anti-double-billing rule reads as one query rather than as two round trips
     * a caller has to remember to make in order.
     *
     * <p>{@code periodDecidedBy} is the decision on that PERSON'S PERIOD (019 D-f). It is not, and
     * must never be relabelled as, an approval of this project's hours.
     */
    List<ApprovedEntry> approvedFor(Collection<UUID> projectIds, LocalDate from, LocalDate to) { … }

    /** Unapproved hours in the same window, per person — the amber note's two names and figures. */
    List<PendingHours> unapprovedFor(Collection<UUID> projectIds, LocalDate from, LocalDate to) { … }

    /** Approved hours on one project, all time — the budget-burn numerator. */
    BigDecimal approvedHoursOn(UUID projectId) { … }
}
```

The SQL shape for `approvedFor`, so the join is written once and read once:

```sql
SELECT e.id, e.person_id, e.project_id, e.subject_id, e.spent_on, e.hours,
       p.decided_at, p.decided_by
  FROM time_entry e
  JOIN timesheet_period p ON p.id = e.period_id
 WHERE e.subject_type = 'ISSUE'
   AND e.project_id IN (:projectIds)
   AND e.spent_on BETWEEN :from AND :to
   AND p.state = 'APPROVED'
   AND NOT EXISTS (SELECT 1 FROM invoice_line_entry x WHERE x.time_entry_id = e.id)
 ORDER BY e.spent_on DESC, e.person_id
```

**Two tables and no third**, which is what lets this class claim to be the only file naming a
`time`-owned table. It resolves no names: `personId` and `decided_by` come back as UUIDs and
`project.PeopleDirectory` turns them into names, because **V1 line 99 says user-facing names come
from `company_membership.display_name`, not from `app_user`** — a join to `app_user` here would be
both the wrong name and a third module's table.

Read `V9__time.sql`'s `timesheet_period` block before writing this — the state column's spelling and
the decision columns' names are that table's, not this plan's, and the plan is not the authority.

- [ ] **Step 3: `PricingService` — the one place a rate is chosen**

```java
/**
 * The rate ladder (019 D-d), and the only place in this product that answers <em>what is this hour
 * worth</em>.
 *
 * <p>Most specific first, each as of the hour's own {@code spentOn}:
 *
 * <ol>
 *   <li>a {@code PERSON} entry naming the person who logged it;
 *   <li>a {@code ROLE} entry naming a role that person actually holds <em>on that project</em>;
 *   <li>the {@code ALL_ROLES} entry.
 * </ol>
 *
 * <p><strong>Where two {@code ROLE} entries both match, the higher rate wins.</strong> That is
 * deterministic by construction and it is a decision rather than a coin toss: a person granted both
 * Test Lead and Test Engineer on one project is being billed as a Test Lead, and that is the number
 * the client agreed. <em>Rejected:</em> refusing the hour as ambiguous, which turns a rate-card
 * configuration a Finance user could not have anticipated into hours that silently fail to bill.
 *
 * <p><strong>No card, or no entry effective that day, is not an error.</strong> It returns
 * {@link Optional#empty()} and the hour lands in the {@code NO_RATE} exclusion bucket, counted and
 * named (019 D-g). {@code billing.html} says so on the screen: <em>"Without a card, approved hours
 * are still recorded — they just cannot be priced."</em>
 */
@Service
class PricingService {

    record Priced(BigDecimal rate, RateCardScope scope, String appliesTo, UUID personId, UUID roleId) {
    }

    Optional<Priced> rateFor(RateCard card, UUID personId, UUID projectId, LocalDate spentOn) { … }
}
```

- [ ] **Step 4: The budget-burn endpoint, and the test that proves it carries no money**

```java
    /**
     * {@code GET /projects/{projectId}/budget-burn} — gated on {@code project:read} (019 D-h).
     *
     * <p><strong>The one endpoint in this module a Test Engineer may call, and it is safe because
     * there is nothing on it to withhold.</strong> {@link BudgetBurnView} has no amount member and
     * no currency member: a {@code MONEY}-basis budget renders as {@code burnPercent} alone, and
     * where the project's client has no rate card that percentage is {@code null} — the burn is
     * <em>unknown</em>, never zero.
     *
     * <p>It is deliberately not gated on {@code billing:read}. That would hide a project's own
     * ceiling from the people delivering against it, which is the opposite of what the roles spec
     * means by <em>"a Project Manager… whose budget view is a percentage on the project overview
     * rather than a number in a currency"</em>.
     */
    @GetMapping("/projects/{projectId}/budget-burn")
    ResponseEntity<BudgetBurnView> burn(@PathVariable UUID projectId, …) { … }
```

```java
// BudgetBurnTest
@Test
@DisplayName("019 D-h — the project-scope budget read carries no money word, on either basis")
void theProjectScopeBudgetReadCarriesNoMoneyWord() throws JsonProcessingException {
    setBudget(MONEY_PROJECT, "MONEY", new BigDecimal("40000.00"), "EUR");
    String body = json.writeValueAsString(
            allowed(budgets.burn(DANA_TEST_ENGINEER, MONEY_PROJECT))).toLowerCase(Locale.ROOT);

    // Over the SERIALIZED body rather than the record's components, for TimeMoneyBoundaryTest's
    // reason: the failure this guards against is a field reaching the wire — a member renamed on
    // the way out, or a nested view added below the one this test names.
    assertThat(body)
            .as("a MONEY-basis budget must reach a project:read caller as a percentage and "
                    + "nothing else — project-overview.html's own hint reads \"hours, no rates\"")
            .doesNotContain("rate").doesNotContain("cost").doesNotContain("amount")
            .doesNotContain("currency").doesNotContain("eur").doesNotContain("40000");
    assertThat(body).contains("\"burnpercent\"");
}
```

- [ ] **Step 5: `BillingReadsAndNeverWritesTest`**

Copied from `staffing.StaffingReadsNoTimeEntryTest` and widened to two classes and four tables.

```java
@Test
@DisplayName("exactly one file in com.tcms.billing names each foreign table, and none writes one")
void exactlyOneFileNamesEachForeignTable() throws IOException {
    Map<String, String> owner = Map.of(
            "time_entry", "TimeFacts.java",
            "timesheet_period", "TimeFacts.java",
            "issue_fact", "IssueFacts.java",
            "issue_time_rollup", "<nothing — 019 D-m: it answers a question an invoice does not ask>");
    // …walk backend/src/main/java/com/tcms/billing, assert the file set naming each table is
    // exactly {owner}, and that no file in the package contains INSERT INTO / UPDATE / DELETE FROM
    // against any of the four.
}
```

- [ ] **Step 6: Gate and commit**

```bash
~/bin/tcms-remote sync
~/bin/tcms-remote backend ./gradlew build
git add backend/src/main/java/com/tcms/billing backend/src/test/java/com/tcms/billing \
        docs/plans/019-billing.md
# plus backend/src/main/java/com/tcms/project/ClientDirectory.java and
# backend/src/main/java/com/tcms/crm/CrmClientDirectory.java IF a method was added
git commit -m "feat(billing): the billing module, the rate ladder, and budget burn without a currency"
```

---

## T4 — Invoices, the lock on issue, the two negatives, and the demo seed

**Deliverable:** `POST /invoices` creates a `DRAFT` header; `GET /invoices/{id}` computes that
draft's lines from the approved entries that exist now, and serves an issued invoice's stored lines;
`POST /invoices/{id}/issue` writes `invoice_line` and `invoice_line_entry` once and assigns the
number; `DELETE` on an issued invoice answers `409` and its lines are byte-identical after a
rate-card edit; `BillingMoneyBoundaryTest` and the extended `TimeMoneyBoundaryTest` are green; and
`seed-demo-data` writes rate cards, budgets, one issued invoice and one draft.
**Depends on:** T3 (pricing and the rate card)
**Stack:** `be` · **Agent:** `be-java`

**Files:**
- Create: `com/tcms/billing/Invoice.java`, `InvoiceLine.java`, `InvoiceLineEntry.java`, their three
  repositories, `InvoiceService.java`, `InvoiceController.java`, `InvoiceNumbers.java`,
  `InvoiceView.java`, `InvoiceDetailView.java`, `InvoiceLineView.java`, `InvoiceEntryView.java`,
  `ExcludedHoursView.java`, `ClientBillingService.java`, `ClientBillingController.java`,
  `ClientBillingSummaryView.java`, `BillingSummaryService.java`, `BillingSummaryController.java`
- Create: `backend/src/test/java/com/tcms/billing/InvoiceLifecycleTest.java`,
  `ClientBillingSummaryTest.java`, `BillingMoneyBoundaryTest.java`
- Modify: `backend/src/test/java/com/tcms/time/TimeMoneyBoundaryTest.java` — **extend only**
- Modify: `backend/src/test/java/com/tcms/crm/CrmCreatesNoLoginTest.java` — **extend only**
- Modify: `backend/src/main/java/com/tcms/DemoWorkspaceSeeder.java`

- [ ] **Step 1: The draft is a query**

```java
/**
 * The invoice lifecycle, and the decision the whole module rests on (019 D-c).
 *
 * <h2>A draft stores no lines</h2>
 *
 * <p>{@code POST /invoices} writes one row: client, period, {@code DRAFT}. Its lines are computed
 * by {@link #compose} on every read, from the approved entries that exist <em>now</em>. That is what
 * makes {@code invoice.html}'s central promise — <em>"Approve them and this draft recalculates"</em>
 * — true with no recalculate endpoint, no staleness flag, and no window in which a draft disagrees
 * with the timesheets behind it.
 *
 * <h2>Issuing is the one write, and the lock is therefore structural</h2>
 *
 * <p>{@link #issue} runs {@link #compose} once, persists the result as {@code invoice_line} and
 * {@code invoice_line_entry} rows, allocates the number, and flips the status. <strong>No operation
 * on this surface updates an {@code invoice_line}</strong> — not because a guard refuses one, but
 * because none was built. The proof is not that a {@code PUT} returns 405; it is that issuing,
 * editing the rate card, and re-reading returns a byte-identical line.
 *
 * <h2>The cost, stated rather than discovered</h2>
 *
 * <p>Two drafts open over an overlapping period for one client both offer the same hours, and
 * whichever issues first takes them: the second recomputes on its next read and the hours are gone
 * from it, with the invoice that took them named. <em>Rejected:</em> reserving entries at draft
 * time, which makes an open draft a lock on the company's time data — a much worse failure than a
 * recomputation somebody can see.
 */
@Service
public class InvoiceService {

    /** The lines, the entries behind them, and BOTH exclusion buckets. Never a partial answer. */
    record Composition(
            List<InvoiceLineView> lines,
            List<InvoiceEntryView> entries,
            List<ExcludedHoursView> excluded,
            BigDecimal total) {
    }

    private Composition compose(Invoice invoice, RateCard card) { … }
}
```

- [ ] **Step 2: Both exclusion buckets, always**

```java
    /*
      019 D-g. Three populations, and an hour is in exactly one of them:

        priced     — approved, issue-subject, on a project of this client, with a rate
        NOT_APPROVED — logged in the window and its period is not APPROVED
        NO_RATE      — approved, but PricingService found no entry effective that day

      The second bucket is the one a naive implementation loses, because from inside a join it
      looks exactly like the first. Both carry their hours and their reason; neither carries an
      amount, because neither has one.
    */
```

- [ ] **Step 3: `InvoiceNumbers`**

```java
/**
 * {@code INV-2026-041} (019 D-k). {@code MAX(seq) + 1} for the company inside the issue
 * transaction, with {@code uq_invoice_seq} as the arbiter and <strong>one</strong> retry.
 *
 * <p>{@code key_sequence} was the obvious home and is refused on three counts: its shape is
 * {@code prefix + next_value} and produces {@code TC-1}; widening it costs a CHECK change and a
 * backfill; and it also costs a {@code CREATE OR REPLACE} of {@code tcms_seed_company_defaults}, or
 * a company provisioned tomorrow cannot issue an invoice at all — and {@code CREATE OR REPLACE
 * FUNCTION} replaces the <em>whole</em> body, which V11's header names as precisely how this gets
 * broken.
 *
 * <p>Gap-free numbering is not guaranteed and is not required. {@code key_sequence}'s own table
 * comment says the same thing about display keys for the same reason — a rolled-back transaction
 * burns its number — and the contract is uniqueness and immutability.
 */
@Component
class InvoiceNumbers { … }
```

- [ ] **Step 4: The immutability proof**

```java
@Test
@DisplayName("an issued invoice's lines do not move when the rate card does")
void anIssuedInvoicesLinesDoNotMoveWhenTheRateCardDoes() {
    UUID invoice = scoped(() -> allowed(invoices.draft(FINANCE, draftInput())));
    InvoiceDetailView issued = scoped(() -> allowed(invoices.issue(FINANCE, invoice)));
    assertThat(issued.invoice().number()).isEqualTo("INV-2026-001");
    assertThat(issued.lines()).singleElement()
            .extracting(InvoiceLineView::rate).isEqualTo(new BigDecimal("78.00"));

    // The card doubles. An invoice already sent must not reprice.
    scoped(() -> allowed(rateCards.replace(FINANCE, CARD, cardAt(new BigDecimal("156.00")))));

    InvoiceDetailView reread = scoped(() -> allowed(invoices.read(FINANCE, invoice)));
    assertThat(reread.lines())
            .as("019 D-c: an issued line is a stored row carrying the rate AS APPLIED. Nothing on "
                    + "this surface updates one — the immutability is structural, not guarded.")
            .isEqualTo(issued.lines());

    // And a DRAFT does move, which is the same decision seen from the other side.
    UUID second = scoped(() -> allowed(invoices.draft(FINANCE, nextPeriod())));
    assertThat(scoped(() -> allowed(invoices.read(FINANCE, second))).lines())
            .singleElement().extracting(InvoiceLineView::rate).isEqualTo(new BigDecimal("156.00"));
}

@Test
@DisplayName("an issued invoice cannot be discarded, and the refusal is a 409 rather than a 403")
void anIssuedInvoiceCannotBeDiscarded() {
    UUID invoice = scoped(() -> allowed(invoices.draft(FINANCE, draftInput())));
    scoped(() -> allowed(invoices.issue(FINANCE, invoice)));
    assertThat(scoped(() -> allowed(invoices.discard(FINANCE, invoice))))
            .as("the caller may delete, the invoice exists, and it is not a field they can fix — "
                    + "so it is neither Denied nor Unreachable, exactly as 018 D-k reasoned for a "
                    + "client a project still names")
            .isInstanceOf(InvoiceService.Outcome.Conflict.class);
}

@Test
@DisplayName("an hour on an issued invoice is never offered to a second draft")
void anHourOnAnIssuedInvoiceIsNeverOfferedToASecondDraft() { … }
```

- [ ] **Step 5: `BillingMoneyBoundaryTest` — derived from the contract, asserting the lack**

```java
/**
 * Scope §7's sharpest boundary, from the money side. {@code time.TimeMoneyBoundaryTest} asserts that
 * a person who logs an hour cannot see its price; this asserts that nobody outside Finance, Company
 * Admin and Instance Admin can reach the surface that knows.
 *
 * <p><strong>The paths are derived from {@code docs/specs/openapi.yaml}, not listed.</strong> The
 * working agreement's rule applies literally: a catalogue-wide invariant needs a computed check, not
 * a careful reading. A thirteenth endpoint added later without a gate reddens this test instead of
 * shipping. {@code backend/build.gradle.kts} already declares {@code docs/specs/} as a test input,
 * so this does not go green by cache.
 *
 * <p>UUID prefix {@code 4c} — grep the tree before trusting that. 018 recorded that {@code fa} and
 * {@code fb} looked free and were not, and the suite failed with a {@code project_pkey} duplicate
 * naming neither class.
 */
@Test
@DisplayName("every billing-tagged path in the contract refuses a Test Engineer")
void everyBillingTaggedPathRefusesATestEngineer() {
    List<String> billingPaths = pathsTaggedBilling(Path.of("../docs/specs/openapi.yaml"));
    assertThat(billingPaths).hasSizeGreaterThanOrEqualTo(12);
    for (String path : billingPaths) {
        assertThat(callAs(DANA_TEST_ENGINEER, path).getStatus())
                .as("%s must refuse a Test Engineer: they hold time:log and not one money key", path)
                .isEqualTo(403);
    }
}

@Test
@DisplayName("story M2 — an Account Manager opens Billing and it is not there for them")
void anAccountManagerHoldsClientReadAndNotOneMoneyKey() {
    Set<String> held = heldInCompany(ANNA_ACCOUNT_MANAGER);
    assertThat(held).contains("client:read");
    assertThat(held)
            .as("the Account Manager owns the client relationship and never sees its price — "
                    + "roles spec §A3's Money table, asserted rather than read")
            .doesNotContainAnyElementsOf(MONEY_KEYS);
}

@Test
@DisplayName("019 D-i — the client billing summary hides its amounts from an Account Manager")
void theClientBillingSummaryHidesItsAmountsFromAnAccountManager() throws JsonProcessingException {
    ClientBillingSummaryView asFinance =
            scoped(() -> allowed(clientBilling.read(FINANCE, ACME_CLIENT)));
    assertThat(asFinance.amountsVisible()).isTrue();
    assertThat(asFinance.rows()).anySatisfy(row -> assertThat(row.amount()).isNotNull());

    ClientBillingSummaryView asManager =
            scoped(() -> allowed(clientBilling.read(ANNA_ACCOUNT_MANAGER, ACME_CLIENT)));
    assertThat(asManager.amountsVisible()).isFalse();
    assertThat(asManager.rows()).allSatisfy(row -> assertThat(row.amount()).isNull());
    assertThat(asManager.approvedHoursToDate())
            .as("they see the client and the HOURS — client.html gives them both — and never a rate")
            .isEqualByComparingTo(asFinance.approvedHoursToDate());

    String body = json.writeValueAsString(asManager).toLowerCase(Locale.ROOT);
    assertThat(body).doesNotContain("\"currency\":\"").doesNotContain("rate").doesNotContain("cost");
}
```

- [ ] **Step 6: Extend `TimeMoneyBoundaryTest` — add an arm, move nothing**

```java
    /**
     * The Account Manager arm, added by 019 T4.
     *
     * <p>This class's four existing arms are untouched, and
     * {@link #everyMoneyKeyInTheCatalogueIsCoveredByThisTest} above all: it is what makes the
     * boundary grow-proof, and M8 inheriting a standing test it cannot quietly widen was the whole
     * reason 016 wrote this file before billing existed.
     */
    @Test
    @DisplayName("story M2 — an Account Manager knows the client and the hours and never a rate")
    void anAccountManagerKnowsTheClientAndTheHoursAndNeverARate() {
        Set<String> held = heldInCompanyBy(ANNA);
        assertThat(held).contains("client:read");
        assertThat(held).doesNotContainAnyElementsOf(MONEY_KEYS);
        assertThat(held)
                .as("and they do not hold time:approve either — a period is approved by a Project "
                        + "Manager, not by the person who owns the client relationship")
                .doesNotContain("time:approve");
    }
```

- [ ] **Step 7: Extend `CrmCreatesNoLoginTest` — the sibling, not a replacement**

```java
    /**
     * {@link #theClientSurfaceCarriesNoMoneyWord} above is unchanged by M8 and must stay that way:
     * its subject is {@code ClientDetail}, which carries no money word before or after this module.
     *
     * <p>What M8 adds is a <em>separate schema on a separate path</em>. This records where, so the
     * next reader of the assertion above does not conclude that the boundary moved.
     */
    @Test
    @DisplayName("019 — the one path under a clients URL where a money word is now allowed")
    void theOnlyClientPathThatMayCarryMoneyIsTheBillingSummary() {
        assertThat(clientTaggedPaths())
                .as("GET /clients/{clientId}/billing is billing's, gated on client:read, and it is "
                        + "the only clients-tagged path that may serve an amount — and only to a "
                        + "caller holding billing:read (019 D-i). Every other one is crm's and "
                        + "carries no money word at all.")
                .filteredOn(path -> path.endsWith("/billing"))
                .singleElement()
                .isEqualTo("/clients/{clientId}/billing");
    }
```

- [ ] **Step 8: `DemoWorkspaceSeeder.seedBilling`, and then RUN it**

Per company: a EUR rate card for one client and a CHF card for another, each with an `ALL_ROLES`
entry, a `ROLE` entry and a `PERSON` entry at three different effective dates; an `HOURS` budget on
one project and a `MONEY` budget on another; **one issued invoice and one draft**; two expenses; and
**one client left deliberately without a card**, so `billing.html`'s *no rate card yet* row and the
`NO_RATE` exclusion bucket both have real data behind them.

```bash
docker compose run --rm backend seed-demo-data
```

017's close-out records that two tasks stated the staffing seeder wrote nothing and were both wrong
because neither checked. **Run it, open `/billing`, and say in T6's close-out what you saw — or say
that you did not run it.** Do not write the sentence you expect to be true.

- [ ] **Step 9: Gate and commit**

```bash
~/bin/tcms-remote sync
~/bin/tcms-remote backend ./gradlew build
git add backend/src/main/java/com/tcms/billing backend/src/test/java/com/tcms/billing \
        backend/src/test/java/com/tcms/time/TimeMoneyBoundaryTest.java \
        backend/src/test/java/com/tcms/crm/CrmCreatesNoLoginTest.java \
        backend/src/main/java/com/tcms/DemoWorkspaceSeeder.java docs/plans/019-billing.md
git commit -m "feat(billing): the draft as a query, the issue as a record, and the two negatives"
```

---
## T5 — The rail entry, the fault arm, the query module, and the two billing screens

**Deliverable:** `/billing` and `/billing/invoices/:invoiceId` render on the render kit from real
reads; the Billing rail entry does not render at all for a caller without `billing:read`, asserted by
a test; an issued invoice renders with no Issue and no Discard control; `tcmsFaults.billing(…)`
drives the two states a seeded instance will not show.
**Depends on:** T4 (every endpoint these screens read)
**Stack:** `fe` · **Agent:** `fe-react`

**Files:**
- Create: `frontend/src/api/queries/billing.ts`
- Modify: `frontend/src/api/queries/keys.ts`, `frontend/src/api/queries/index.ts`
- Create: `frontend/src/features/billing/BillingPage.tsx`, `RateCardPanel.tsx`, `RateCardDialog.tsx`,
  `BudgetPanel.tsx`, `BudgetDialog.tsx`, `InvoiceListPanel.tsx`, `DraftInvoiceDialog.tsx`,
  `InvoicePage.tsx`, `InvoiceLines.tsx`, `InvoiceEntries.tsx`, `money.ts`, and their tests
- Modify: `frontend/src/app/AppShell.tsx`, `frontend/src/app/router.tsx`,
  `frontend/src/lib/devFaults.ts`, `frontend/src/lib/labels.ts`

- [ ] **Step 1: The keys, in the one factory**

```ts
  /**
   * Billing (019 M8). `billingRoot` is what an invoice write sweeps — a draft's lines are computed
   * on every read (019 D-c), so issuing changes the summary, the budget table, the invoice list and
   * the client page's rollup all at once, and none of them can be individually invalidated with
   * any confidence about which.
   *
   * `clientBilling` sits under `clientsRoot` rather than here, for the reason the comment above
   * `clientsRoot` gives: where a key lives is decided by which invalidation must sweep it, and that
   * table is on the client page. An invoice write therefore sweeps BOTH roots, which is stated in
   * `useIssueInvoice` rather than left to be discovered from a stale table.
   */
  billingRoot: ['billing'] as const,
  billing: {
    summary: ['billing', 'summary'] as const,
    rateCards: ['billing', 'rate-cards'] as const,
    rateCard: (rateCardId: string) => ['billing', 'rate-cards', rateCardId] as const,
    budgets: ['billing', 'budgets'] as const,
    invoices: (clientId: string, status: string) =>
      ['billing', 'invoices', clientId, status] as const,
    invoice: (invoiceId: string) => ['billing', 'invoices', invoiceId] as const,
    expenses: (projectId: string) => ['billing', 'expenses', projectId] as const,
  },
```

`budgetBurn` goes under `['projects', projectId, 'budget-burn']`, **not** under `billingRoot` — it is
a project-scope read on a project screen, and it must be swept by the project invalidation the
overview already runs, exactly as `projectStaffing` is (017's note on `staffingRoot`).

- [ ] **Step 2: The rail entry — the fifth gated one**

```tsx
/**
 * Billing (019 M8), and **the fifth gated entry in a rail whose standing rule is
 * disable-don't-hide** (§31) — after the two audit entries, the three credential entries and
 * Clients.
 *
 * `billing.html` says it in a callout on the screen itself: *"Switch the top bar to Test Engineer
 * or Account Manager and this entry disappears from the sidebar entirely — not greyed out, not
 * empty. It is not their screen."*
 *
 * The argument is `ApiTokenService`'s, restated by the Clients entry above and sharpest here: the
 * read is **refused rather than narrowed**, so the whole screen behind this entry exists only to
 * say no, and an entry into that refusal is the same refusal one click earlier. A Test Engineer has
 * no business learning from a sidebar what their employer bills — which is the same sentence the
 * Clients entry makes about how many customers they have, one step less sensitive.
 *
 * Placement: the ungrouped block outside the project frame, **after `TIMESHEETS`**, for that
 * item's own reason inverted. A timesheet period is personal and spans projects; an invoice is the
 * company's and spans projects; neither is changed by the switcher, so neither may sit under it.
 *
 * Gated on `billing:read` — the key every money-holding role holds and every billing read requires.
 * It fails CLOSED while pending, like the other gates here: the item appears once the answer
 * arrives, where a wrongly-shown one would flash a link that then vanishes.
 */
const BILLING: NavItem = {
  label: 'Billing',
  to: '/billing',
  icon: <Receipt className="size-4" />,
  end: false,
}
```

Rendered from the same warm `useCanInCompany()` the Clients entry reads, so it costs no extra
request:

```tsx
        {companyGate.can('billing:read') && (
          <li>
            <RailLink item={BILLING} onNavigate={onNavigate} collapsed={collapsed} />
          </li>
        )}
```

- [ ] **Step 3: The test the execution breakdown names as an acceptance criterion**

```tsx
it('does not render the Billing entry for a Test Engineer or an Account Manager', async () => {
  /*
    019 D-o. ABSENT, not disabled — which is the opposite of what this rail does everywhere except
    its five recorded exceptions, so the test says which rule it is asserting.
  */
  renderRail({ companyPermissions: ['client:read', 'time:log'] })
  expect(await screen.findByRole('link', { name: 'My work' })).toBeInTheDocument()
  expect(screen.queryByRole('link', { name: 'Billing' })).toBeNull()
})

it('renders the Billing entry for a caller holding billing:read', async () => {
  renderRail({ companyPermissions: ['billing:read'] })
  expect(await screen.findByRole('link', { name: 'Billing' })).toHaveAttribute('href', '/billing')
})
```

- [ ] **Step 4: `money.ts` — one formatter, and no cross-currency sum**

```ts
/**
 * Every monetary figure in this feature goes through here, and there is deliberately **no
 * `sum(Money[])`** (019 D-l).
 *
 * `billing.html` draws `€52.4k` as one tile while its own rate-card table carries a EUR client and
 * a CHF client two rows apart. That is a single-currency render of a multi-currency company, and a
 * function that added two currencies would be the one place this feature could produce a number
 * that is wrong in both. The tiles render one line per currency instead — recorded in plan 019 D-l
 * as a disagreement with the mockup rather than resolved silently, per plan 009's precedence rule.
 */
export function formatMoney(money: Money): string { … }
export function formatMoneyList(figures: Money[]): string[] { … }
```

- [ ] **Step 5: `BillingPage` — the four tiles, four panels, in the mockup's order**

The mockup's layout, translated: a `g4` KPI row, then a `g-main` two-column grid with **Rate cards**
and **Project budgets** stacked on the left and **Invoices** and **Not billed** on the right. Every
region is a `DataRegion`, and each panel gets its **own** — 017 D-m's rule: a summary outage must not
empty the invoice list.

The **Not billed** panel is static copy, straight from the mockup, and it is the screen saying what
the module refuses:

```tsx
      {/*
        `billing.html`'s "Not billed" panel, rendered verbatim because it is a product statement
        rather than a data region. D24 admits one line shape — approved time at a rate-card rate —
        and this panel is where a Finance user reads that the two obvious extensions are absent on
        purpose rather than missing. Do not turn it into a feature flag or a settings link.
      */}
      <Panel title="Not billed" testId="Billing.NotBilled">
        <Refusal>Fixed-fee and retainer invoicing</Refusal>
        <Refusal>AI-assistant usage — recorded as a cost, never charged to a client</Refusal>
      </Panel>
```

- [ ] **Step 6: `InvoicePage` — a draft and an issued invoice are one screen in two states**

```tsx
/**
 * `invoice.html`, both states.
 *
 * <h2>An issued invoice renders read-only, and "read-only" means the controls are gone</h2>
 *
 * No Issue, no Discard, no edit affordance anywhere, and the number where the draft says *assigned
 * on issue*. The lines are stored rows carrying the rate as applied, so this screen shows the same
 * figures a month later after the rate card has moved — which is the point of issuing.
 *
 * <h2>Preview PDF is not built</h2>
 *
 * The mockup draws the button. There is no endpoint behind it and none is planned (019 D-q), so the
 * button is absent rather than drawn-and-inert — `ApprovalsPage.tsx` records the same decision for
 * `Approve all` and `Remind`, and a control that does nothing is worse than one that is not there.
 *
 * <h2>Both exclusion buckets are rendered, and neither is a footnote</h2>
 *
 * The amber note carries the unapproved hours with their names, exactly as the mockup does, and
 * links to `/timesheets/approvals`. The second bucket — approved hours no rate card can price — is
 * a second note in the same place, which the mockup does not draw because its example company has a
 * card for every client. It is not optional: an hour that vanished from a draft with no explanation
 * is the defect this module exists to remove (019 D-g).
 */
export function InvoicePage() { … }
```

The entries table's approval column is headed **`Period approved`**, and its `title` says why:

```tsx
        <TableHead title="A timesheet period is per person and spans projects, so this is the decision on that person’s period — not an approval of this project’s hours (019 D-f).">
          Period approved
        </TableHead>
```

- [ ] **Step 7: `tcmsFaults.billing(…)`**

```ts
/**
 * The billing arm (019 T5), and like the clients arm it fabricates a state rather than a failure.
 *
 *   - `billing('no-rate-card')` — every rate-card read answers with `card: null` and every draft
 *     comes back with all of its hours in the `NO_RATE` bucket. This is `billing.html`'s
 *     *Kessler Health* row and the second exclusion note on an invoice, neither of which a seeded
 *     instance shows once the demo cards exist.
 *   - `billing('nothing-priceable')` — every approved-hours read answers zero, so the four tiles,
 *     the budget burns and the draft all render their empty states at once. This is a company that
 *     has just started, and it is the state most likely to be mistaken for a broken screen.
 */
```

- [ ] **Step 8: Gate and commit**

```bash
~/bin/tcms-remote sync
~/bin/tcms-remote frontend npm run build
~/bin/tcms-remote frontend npm run lint
~/bin/tcms-remote frontend npm run test:run
```

Expected: build clean, lint 0 warnings, **two failures and both in
`src/features/cases/CaseGrid.test.tsx`** — *survives paging* and *select-all reaches the page*, both
`Number of calls: 0` after a `userEvent.click` on the Radix `Checkbox`. **They predate this module**;
016 and 017 each recorded them and neither absorbed them. Do not report them as yours.

```bash
git add frontend/src/features/billing frontend/src/api/queries/billing.ts \
        frontend/src/api/queries/keys.ts frontend/src/api/queries/index.ts \
        frontend/src/app/AppShell.tsx frontend/src/app/AppShell.test.tsx \
        frontend/src/app/router.tsx frontend/src/lib/devFaults.ts frontend/src/lib/labels.ts \
        docs/plans/019-billing.md
git commit -m "feat(billing): the billing and invoice screens, and the rail entry that is absent"
```

---

## T6 — The client hours-and-invoices table, the two budget figures, and the close-out

**Deliverable:** `BillingSlot`'s body is `client.html`'s five-column table plus the two KPI tiles
above it, with the value column **absent** for a caller whose summary says `amountsVisible: false`;
the project overview draws its Budget burn panel and General settings its Budget row, neither
carrying a currency; and M8 is closed per plan 009's hand-off protocol.
**Depends on:** T5 (the query module and the invoice route these link into)
**Stack:** `fe` · **Agent:** `fe-react`

**Files:**
- Modify: `frontend/src/features/clients/BillingSlot.tsx` (body replaced), `ClientPage.tsx`,
  `ClientPage.test.tsx`
- Create: `frontend/src/features/clients/ClientRateCardPanel.tsx`
- Modify: `frontend/src/features/projects/ProjectOverviewPage.tsx` and its test
- Modify: `frontend/src/features/settings/GeneralSettingsPage.tsx` and its test
- Modify: `docs/features.md`, `docs/plans/009-project-management-expansion-modules.md`

- [ ] **Step 1: `BillingSlot` — the whole table, not three of its columns**

The file's own header states what it is reserving and why; replace the body, keep the seam, and
rewrite the header to say what landed. `Period · Project · Approved hours · Invoice · Status`, plus
**`Value`** — and that sixth column is rendered **only** when `amountsVisible` is true.

```tsx
      {/*
        019 D-i. The value column is ABSENT for an Account Manager, not empty and not dashed.
        `client.html`'s own footer states it from the other side: *"Amounts are absent for an
        Account Manager. Switch the top bar to Finance and this table gains a value column."*

        The flag comes from the server, evaluated once against `billing:read`. It is deliberately
        NOT computed from the local permission cache: a client that decided this itself would be one
        stale `/me/permissions` away from rendering a column of nulls and calling it money.
      */}
      {summary.amountsVisible && <TableHead className="text-right">Value</TableHead>}
```

```tsx
it('renders no value column for an Account Manager', async () => {
  renderClientPage({ summary: aClientBillingSummary({ amountsVisible: false }) })
  expect(await screen.findByText('Hours and invoices to date')).toBeInTheDocument()
  expect(screen.getByText('168h')).toBeInTheDocument()
  expect(screen.queryByRole('columnheader', { name: 'Value' })).toBeNull()
  // and the boundary, not just the column: no currency anywhere in the rendered table
  expect(screen.queryByText(/€|CHF|\$/)).toBeNull()
})
```

- [ ] **Step 2: `ClientRateCardPanel` — a link, not a second editor**

`client.html` renders this panel for an Account Manager as an empty state reading *"Not visible to
you — Rates are Finance's. An Account Manager knows the client and the hours; never the money."*
That copy is now true rather than aspirational, and it stays. For a caller holding `ratecard:read`
the panel shows the card's currency and entry count and **links into `/billing`**: one place edits a
rate card, and T5 built it.

- [ ] **Step 3: The Overview's Budget burn panel — and the reversal of the assertion that it is absent**

`ProjectOverviewPage.tsx:86` and its test both currently state, in terms, that the budget card is not
rendered *because a budget is a module that does not exist*. That was correct when written. Rewrite
both in the repo's superseded style — struck through, with the reversal beside it — rather than
deleting the reasoning:

```tsx
 * - ~~**Budget burn** (M6) … **not rendered at all**~~ → **superseded 2026-09-05 (019 T6):** the
 *   module exists. The panel reads `GET /projects/{projectId}/budget-burn`, which is gated on
 *   `project:read` and carries a percentage and hours and **no currency and no amount** (019 D-h) —
 *   the mockup's own hint is "hours, no rates" and its copy is *"What an hour costs is Finance's,
 *   and is not on this page."* A MONEY-basis budget therefore renders as a percentage alone, and
 *   where the project's client has no rate card the burn is **unknown**, not zero. A project with
 *   no budget renders no panel, which is the state `billing.html`'s internal project is in.
```

- [ ] **Step 4: General settings' Budget row**

`settings-general.html`'s Time panel row: `Budget · Hours ceiling — the PM sees the burn, never the
rate · 600h · [Edit in Billing]`. It reads the same `budget-burn` endpoint the overview does, so a
project administrator sees the ceiling without holding a money key, and the button links to
`/billing` — which is where the ceiling is set, and which that administrator may not be able to open.
Render the link **disabled with its reason** for a caller without `billing:read`: this is a screen
control, not a rail entry, so §31's disable-don't-hide applies here in full.

`GeneralSettingsPage.tsx:238` and its test currently state that this row is deliberately not built.
Reverse both in the superseded style, as step 3 does.

**Do not disturb `frontend/src/features/settings/settingsUpdate.ts`.** It is the one place
`ProjectSettingsUpdate`'s field list is written, and `trackTime` is the single boolean the server
reads as `true` when omitted (016 T5) — the budget is not part of that document and must not join it.

- [ ] **Step 5: `docs/features.md`**

Write the Billing section for a reader deciding what the product does: rate cards per client,
budgets per project, approved hours priced, a draft that recalculates, an issue that locks, and the
five things it does not do (D-q). Strike through and supersede what it replaces; never delete.

- [ ] **Step 6: Close plan 009 per the hand-off protocol**

1. **Status table** — M8's row to `done 2026-09-0N @ <short sha>` of the last commit.
2. **Built and unverified** — under the `M6 · M7 · M8` section, a `**Built and unverified:**` list.
   The gates prove it compiles, not that it works. At minimum it must say: which screens nobody has
   opened against a running instance; whether `seed-demo-data` was actually run and what was seen;
   that `Preview PDF` and an expenses screen do not exist and why; that the concurrent-draft race in
   D-c has never been executed by two callers; and the two `CaseGrid.test.tsx` failures that predate
   this module and were not absorbed.
3. **The migration actually taken** — correct the table and the `**db**` line, striking the reserved
   number through rather than deleting it.
4. **Dependent prompts** — **no module depends on M8.** Say so in the commit message; touch no
   prompt.
5. **Review table** — record any `R15+` number this module took.
6. **Commit it alone**, `docs(plan): M8 done`.

- [ ] **Step 7: Gate and commit — two commits**

```bash
~/bin/tcms-remote sync
~/bin/tcms-remote frontend npm run build
~/bin/tcms-remote frontend npm run lint
~/bin/tcms-remote frontend npm run test:run
git add frontend/src/features/clients frontend/src/features/projects frontend/src/features/settings \
        docs/plans/019-billing.md
git commit -m "feat(billing): the client's hours and invoices, and the two budget figures"

git add docs/features.md docs/plans/009-project-management-expansion-modules.md
git commit -m "docs(plan): M8 done — V12, the built-and-unverified list, and no dependent prompt"
```

---

## Definition of done

From the module prompt, and each is checkable by someone who did not do the work:

- [ ] **Approved hours are priced by the rate card** — `PricingTest` walks D-d's ladder on all three
      scopes and on the two-role tie.
- [ ] **Budget burn is visible** — on `/billing` in money and hours to Finance, and on the project
      overview as a percentage with no currency to anybody holding `project:read`.
- [ ] **A draft invoice is issued and its lines are then immutable** — proven by issuing, doubling
      the rate card, re-reading, and asserting the line is unchanged (T4 step 4), not by a rejected
      `PUT` to an endpoint nobody built.
- [ ] **The two negative permission tests are green** — `BillingMoneyBoundaryTest` derives its paths
      from the contract and asserts a Test Engineer is refused every one; the extended
      `TimeMoneyBoundaryTest` asserts an Account Manager holds `client:read` and not one money key.
- [ ] **Billing is absent from the sidebar** for a Test Engineer and an Account Manager, asserted by
      a test, and absent means not rendered.
- [ ] All three gates green on `shanks`, with the two pre-existing `CaseGrid.test.tsx` failures
      named and not absorbed.

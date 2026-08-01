# §53. The `work` module + the `case_health` writer — the board runs on a server, and the check that would have caught it

**Date:** 2026-08-01 · **Branch:** `master` · **Sequential**, the eighth slice since §45.

`GET`/`POST`/`PATCH /work-items` are live, `case_health` has its first writer, and the
operation-level contract check §52 asked for is built — and it catches the defect this same slice
opened with.

## What the orientation pass found, before any code

§28's two questions (*which of these has a consumer*, *is this still blocked*) turned up three
things on a screen nobody had looked at since Phase 1:

1. **`POST /work-items` and `PATCH /work-items/{id}` were called by the SPA and declared nowhere
   in `openapi.yaml`.** §52 recorded this as its own *Not done* and named the missing check. It had
   been true for sixteen slices.
2. **`useCreateWorkItem` had zero call sites** — the oldest dead hook after `useCaseVersions` (§35).
   The board could display work and never accept any, which for a task board is the feature missing.
3. **`WorkItem.status` was an unconstrained `string`**, and the SPA compared it against display text.

The third is the one worth the reader's time, because it is §52's lesson one level down.

```ts
// BoardPage.tsx, since Phase 1              // openapi.yaml, since the first draft
const COLUMNS = ['To do', 'In progress', …]  status: { type: string }
items.filter(i => i.status === column)
```

The MSW handler served `'To do'` because it had been written from the page. **Mock mode agreed with
itself perfectly.** A server answering `TODO` would have filed every card into no column at all —
an empty board, no error, nothing in the console.

§52's defect was a field the two sides *spelled* differently (`runs` vs `entries`). This is a field
whose **vocabulary the contract never fixed**, so neither side could be wrong. That is the more
dangerous shape: a rename is a disagreement waiting to be found, while an unconstrained type is an
agreement nobody ever made. `WorkItemStatus` (`TODO · IN_PROGRESS · IN_REVIEW · DONE`) puts it on
the contract's side of the comparison, and regenerating turned it into **15 compile errors in
exactly the three files that held the defect**.

## The `work` module

The eighth Modulith module, and the first whose whole design is a refusal.

§6l's central decision is that **derived items are never materialised**. An assigned execution and a
flagged case appear as cards, but their status is computed on every read, because the moment a
card's status is stored separately from its execution's, a tester marks the case Passed in the run
and the card sits in "In Progress" forever. Within a week nobody trusts the board — and a board
nobody trusts is worse than no board, because it is still on everyone's screen. `V1`'s comment on
`work_item` says the same thing from the schema side: *"there is deliberately no column here that
could let a card status diverge from its execution."*

Everything awkward in this module follows from that one decision:

- A derived card's id is **synthetic** (`execution:{uuid}`), because it has no row to have an id.
  The contract types `id` as `string` rather than `uuid` for that reason — declaring a format the
  derived half cannot satisfy would make every generated client reject two thirds of the board.
- `PATCH` refuses a derived id with **409, not 404**. The card exists and the caller is looking at
  it; what is impossible is *storing* a status for it. 404 reads as "that card is gone" and invites
  a reload that brings it straight back.
- `allowedTransitions` is a property of the **card**, not of the column, and empty for a derived one.

**Two of §6l's five generators are implemented, and three are absent rather than empty.** `REVIEW`,
`DOC_UPDATE` and `DEFECT` need modules that do not exist. A generator that always yields nothing is
indistinguishable from a broken one, so they are not wired at all — while the contract's `kind` enum
keeps all six, so `lib/labels.ts` stays complete for the day they land.

### The seam, in two directions for the first time

`work` may depend on **neither `execution` nor `repository`** (plan §3), while the derived half of
the board is made entirely of their rows. `work.BoardFacts` is the whole of both crossings — the
third instance of the `CaseFacts` pattern and the first that crosses two ways at once. The
codebase's running count of forced wire-shape copies is now recorded in `work.UserRefView`:
**`ApiProblem` ×2, `UserRefView` ×3.** The next one should probably arrive with the `shared` module
rather than with another comment explaining why it could not.

Three rules that would each be a plausible mistake:

**A recorded execution has left the board.** §6l: "execution completed → card leaves." Otherwise the
board is an append-only log of everything ever run, growing forever while looking like a to-do list.

**An unrecorded execution in a *closed* run is not resurrected.** Those are the cases that were
skipped. They are a reporting fact — §52's suite timeline counts them as `notRun` for coverage — and
on a board they would be a backlog nobody can ever clear, because the run they belong to is over.
The same row is work outstanding *or* not a task depending on which question is being asked.

**An assignee must hold a grant on the project, not merely membership in the company.** The id is
trivially available from any other tab, the card renders their name perfectly, and the only person
who ever sees the problem is the assignee — who gets a 404 when they click it.

## `case_health` gets its first writer

`case_health` has had an entity, a repository, an enum, four partial indexes and a dashboard panel
reading it **since §47, and no writer**. `DashboardService` already drew the consequence in its own
javadoc: an empty "Needs attention" panel is a *claim* that nothing needs attention, and it was
false in every deployment because nobody had looked.

Two of the nine signals are now real. Three decisions worth carrying:

**The `ON CONFLICT` clause names three columns, not all of them.** Seven signals have no writer. A
job that wrote every column would author claims it has no evidence for — and would blank a future
approval computation's work on every tick. The row is shared; the columns are owned individually.

**Scheduled, not event-driven** — against `HealthSignal`'s own "read model fed by events" note.
`LONG_UNEXECUTED` becomes true through the **passage of time**, not through anything happening. No
event fires on the day a result turns ninety days old, so an event-only design is correct right up
until the moment the signal should first fire.

**The threshold is instance-wide, and §6o says it should be per project.** Open question #30 is
still open and there is no project settings table to hold it (`settings JSONB` is on `company`, not
`project`). `CaseHealthProperties` is the honest middle: a named default in one place, which becomes
the *fallback* a project setting overrides rather than a `90` inlined in SQL to be found later.

## The operation-level contract check

§52 named this rung precisely and it was pointing straight at defect 1 above. Every existing check
**starts from the spec** — `ContractConventionTest` asks whether declared operations carry 4xx and
`required`; `contractParameters.test.ts` asks whether sent keys are declared;
`ContractParameterBindingTest` asks whether declared parameters are bound. **An operation that was
never declared is invisible to all three.** You have to start from the caller.

The method matters as much as the path: `/work-items` *was* declared, for `GET`, so a path-only
check passes on both defects. `contractOperations.test.ts` asserts every `api.*` URL resolves to a
declared operation **with that method**, and it was verified by re-injecting the defect — deleting
the `POST` operation fails it with *"workItems.ts:38 calls POST /work-items, and the contract
declares only GET for that path."*

The URL-shape plumbing moved to `contractIntrospection.ts` rather than being copied. Two
implementations of "what URL does this line build" would agree until somebody taught one of them
about a new helper — and **a checker that quietly stops understanding a call site does not fail, it
reports nothing.** Writing the guard against exactly that caught a gap immediately: a URL that is
*entirely* a helper call (`api.get(base(projectId!))`) could not be reconstructed, and the vacuity
guard turned it into a failure rather than an unchecked call site.

## Four things this slice got wrong first

Recorded because each looked right and was caught by something other than review.

**`@Positive` on a `Duration` fails context startup, not validation.** Bean Validation has no
validator for `Duration` (`HV000030`). It typechecked, read correctly, and took the whole Spring
context down.

**A mutation that killed no test.** Deleting the `status_id IS NOT NULL` predicate from the health
recompute killed nothing — `executed_at` is null exactly when `status_id` is, so `MAX` already skips
unrecorded rows. The predicate is kept as *intent* (M3 ingestion is a second writer being built
against that table) and the javadoc now says it is redundant **today** rather than implying it is
load-bearing. A comment that overstates a guard is how the next reader deletes the wrong one.

**`WorkItemStatusUpdateRequest` bound `status` to the enum**, so Jackson rejected unknown values
before any of the module's code ran — leaving `unknownStatusProblem()`, which names the four valid
columns, as **unreachable code that reads as if it runs**. Two files each looked right alone while
contradicting each other.

**§19's defect, verbatim.** `CaseHealthRecomputeTest` took `ch-*` company slugs already owned by
`CaseHistoryEndpointTest`. `uq_company_slug` failed all eleven tests — in the full suite only, with
a stack trace pointing at an `INSERT INTO company` in the base class. **Every naturally-keyed
fixture value needs the class prefix, not just the primary keys.**

## The defect only the live pass found

Both generators worked on the first live load — and **both were mute**.

A `CASE_MAINTENANCE` card rendered as its case title and a "Maintenance" chip, sitting directly
above an `EXECUTION` card carrying the **same case title**. Two cards, the same words, different
meanings, neither explaining itself.

The cause is the shape no gate can see. The server has always sent `description` — health signals
for a maintenance card, the run name for an execution card — and `BoardEndpointTest` asserts it
contains `NEVER_EXECUTED`. **That test passed.** `WorkCard` simply never rendered the field. The
data was correct, transmitted, tested, and invisible.

`BoardService.cardFor` warns about precisely this in its own javadoc — *"a card whose reason is
hidden behind a click gets ignored in bulk"* — and it shipped anyway, because the sentence was
written about the server's behaviour while the failure was on the screen. **A correct server and a
silent screen look identical from every gate in the build.**

Labelled through `healthLabels`, the same map the dashboard's "Needs attention" panel uses, so one
signal cannot read two ways across two screens.

## What the live pass proved

Driven in **LIVE mode** against a freshly provisioned company (`board53`), a project created through
the UI, three cases and a run seeded by SQL:

- **Both derived generators render.** TC-1 and TC-3 (unrecorded) appear as Execution cards; TC-2
  (recorded) is **absent** — "execution completed → card leaves", live.
- **The scheduled health recompute ran against real data** and got the discrimination right:
  `never_executed` true for TC-1 and TC-3, **false for TC-2**, whose execution is recorded. The
  planned-vs-recorded predicate is the one this job would most plausibly get wrong, and it was
  checked by a job on a timer rather than by a test.
- **The dashboard's "Needs attention" panel is no longer structurally empty** — "2 · Never executed"
  — and the tile opens exactly its two cases (`?healthSignal=NEVER_EXECUTED`), with the facet count
  agreeing. §47's dashboard working end to end for the first time, because the table finally has a
  writer.
- **A task was created and moved live** (`POST` then `PATCH`), landed overdue from a past due date,
  and **sorted above both execution cards** — the overdue-first ordering, visible.
- **A derived card's move buttons are disabled and explain themselves**: *"This card follows its
  source, so it can't be moved to Done."* The tooltip reaches the disabled control, which is the
  span-anchor trick `PermissionGate` documents and the half that is silent when forgotten.
- Console clean, zero client errors, no page-level horizontal scroll.

## The board had zero tests

Worth stating on its own: rewriting the board's entire status vocabulary — every column, every
comparison, every `data-testid` — left **all 327 existing tests green**, because not one of them
touched that screen. Seven now, and the label-vs-code mutation (the actual defect) kills five of
them.

Writing them corrected a premise too: the rejection banner for a derived card is **unreachable by
clicking**, because the move buttons are `disabled`. Only dragging reaches it. A `user.click` test
there would have passed against a button that cannot be pressed.

## Gate evidence

```
backend:   ./gradlew build → BUILD SUCCESSFUL
           954 tests, 0 failures, 0 skipped     (931 + 23)
frontend:  npm run gen:api → WorkItemStatus, WorkItemInput, WorkItemStatusUpdate generated
           npm run build   → ✓ built
           npm run lint    → oxlint, exit 0
           npm run test:run→ 21 files, 383 tests passed   (327 + 56)
browser:   LIVE mode against a freshly provisioned company — see "What the live pass proved".
```

**Mutations, each killed:**

- `isDerived` always false (derived cards become movable) — 1 test, the 409 refusal.
- unrecorded-only dropped from `assignedExecutions` — 2 tests.
- `r.status = 'IN_PROGRESS'` dropped (closed runs resurrected) — 2 tests.
- assignee project check dropped, leaving membership only — 1 test.
- `mine` filter removed — 1 test.
- `min` for `max` in the health recompute — 1 test, and only that one.
- `ON CONFLICT` clobbering all nine signal columns — 1 test.
- soft-deleted cases included in the recompute — 1 test.
- board filing cards by label instead of code (the §53 defect, re-injected) — **5 of 11** board tests.
- the card's reason not rendered (the live-pass defect, re-injected) — 4 tests; dropping the
  labelling instead — 2.
- `POST /work-items` deleted from the contract — the new operation check, naming file and line.

## Not done

> **→ The first, second and fourth items below are done in §54**, together, and the *together* is
> the point: the header arrived with the column that honours it (`V17`), and `BLOCKED` arrived with
> its label and its grid column. The third — the symmetric backend check — is still not built.
> The reasoning below is kept because §54's design follows from it rather than replacing it.

- **`POST /work-items` honours no `Idempotency-Key`, and the contract no longer declares one.**
  `work_item` has no `created_idempotency_key` column or partial index (`V14` added both for plans
  and cases), so honouring the header needs a migration. Declaring it without the column would be
  §50's defect in its worse direction — in the reviewed spec, in `schema.d.ts` and in autocomplete,
  while the server ignored it, so a client would believe its retries were deduplicated when every
  one created another card. **The repair is contract-first, together with the column.**
- **No assignee picker.** The server accepts and validates `assigneeId`; the dialog cannot send one
  because **`GET /projects/{id}/members` does not exist**. Tasks are created unassigned, which is
  §6l's unclaimed pool, so the gap is a missing capability rather than a wrong default.
- **The symmetric backend check — a declared operation no handler implements — is not built.**
  It is the true mirror of this slice's check, and today it would be mostly a list of deliberately
  unimplemented endpoints (`documentation`, `reporting`, `/reports`, `cases/bulk`). That list would
  be useful documentation of what is still MSW-only; it is also large enough to be its own slice.
- **`BLOCKED` is not a column**, though §6l's default set includes it. The board renders exactly the
  columns the enum names, so a status with no column is a card that exists, is returned by the API,
  and appears **nowhere on screen** — invisible rather than misplaced. Adding it is a contract
  change, a label and a fifth grid column.
- **A case that moves between suites still takes its whole execution history with it** (§52,
  unchanged): `test_execution` pins the case *version*, not the suite.
- **`documentation` and `reporting` writes still run on MSW** — `/doc-pages`, `/artifacts`,
  `/reports`, plus `cases/bulk`. `work` has left that list. Teams and memberships remain the next
  write surface with no endpoints (§39, §45–§52).

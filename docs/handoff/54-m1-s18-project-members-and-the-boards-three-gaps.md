# §54. The board's three gaps — the members endpoint, V17 idempotency, and the fifth column

**Date:** 2026-08-01 · **Branch:** `master` · **Sequential**, the ninth slice since §45.

`GET /projects/{projectId}/members` exists, `POST /work-items` honours `Idempotency-Key` with a
column behind it (`V17`), and `BLOCKED` is a real column. All three were §53's *Not done*, and
taking them together is what made the first one interesting.

## What the orientation pass found

§28's two questions over the whole SPA, before any code:

1. **One dead hook, down from two.** `useSharedSteps` has no call site; everything §53 named now
   has one. That is the shortest this list has been.
2. **The MSW-only surfaces are `documentation`, `reporting`, `/reports` and `cases/bulk`** —
   confirmed by listing every `@*Mapping` against the contract's paths rather than by reading the
   last handoff, which is §46's rule.
3. **The `documentation` module needs no DDL** (`doc_space`, `doc_page`, `doc_block`, `doc_link`,
   `doc_page_version` are all in `V1`) — but it has **no writer anywhere**, so a read-only slice
   would ship §53's `case_health` defect verbatim: an empty tree that *claims* there is no
   documentation, and a coverage marker reading "Not tested" for everything. Recorded here because
   the temptation is to treat "the tables exist" as "the slice is small".

## The members endpoint, and the thing it is actually for

§53 recorded the missing capability as *"the dialog cannot send an `assigneeId` because
`GET /projects/{id}/members` does not exist"*. That framing makes it sound like plumbing. It is
not, and the reason is the second half:

> The server already validated `assigneeId` against *active company members holding a grant on the
> project*. The picker has to offer **that same set** — not a set that agrees with it.

Two expressions of one predicate drift, and the direction they drift is the harmful one: a picker
offering somebody the create refuses is a form the user fills in correctly and cannot submit, with
a field error naming a person the product just showed them. That is §31's disable-don't-hide rule
applied to a **set** rather than to a button.

So the predicate moved into one place. `identity.ProjectMembershipService.isAssignableTo` is asked
by the endpoint that builds the list and by `work.BoardService` when it validates a create.
`work.BoardPeople` used to answer half of it (*is this an active member of the company*) while
`BoardService` ANDed the other half (*do they hold a grant*) — both halves right, the arrangement
still wrong the moment a second caller appeared. `ProjectMemberEndpointTest.Agreement` asserts the
two agree by *driving both*: every person on the list is POSTed as an assignee and must be
accepted, and two people absent from it must be refused.

### Why it cannot be a query over `role_assignment`

The obvious implementation — `WHERE project_id = ?` — is wrong in a way that produces a 200 and a
plausible list. It finds only **direct** grants, so it silently omits:

- **everybody staffed through a team**, which is the arrangement the org chart exists to encourage;
- **every Company Admin**, who reaches every project through a COMPANY-scope grant and holds no
  project row at all — the person most likely to be assigned anything, missing from every project's
  picker.

Nothing fails. The colleague is simply un-assignable. Resolving through `PermissionEvaluator` is
the fix and it is also plan §6b's rule: one place computes effective permissions, because a second
one diverges quietly and the quiet one decides what a client can do. The live pass proved both
misses — see below.

### Two smaller decisions

**The gate is "can you see the project", not `project:members`.** That permission is the right to
*change* staffing (Test Lead and up). Gating the read on it would leave a Test Engineer able to
create a task and unable to name anybody on it.

**404 for an unreachable project, not `200 []`.** The admin reads (`/users`, `/teams`) answer an
empty array to a caller who fails their gate, because their contract declares only 200. This is a
project-scoped path, every one of which answers 404, and §6t requires an unreachable project and a
non-existent one to be indistinguishable — an empty member list would confirm the project exists.

## `V17`, and why the header waited a slice

§53 shipped `POST /work-items` **without declaring `Idempotency-Key`** and wrote the reason into
`openapi.yaml`: declaring it with no column behind it is §50's defect in its worse direction. The
header would sit in the reviewed spec, in `schema.d.ts` and in autocomplete while the server
ignored it, so a client would believe its retries were deduplicated when every one created another
card — the exact failure the header exists to prevent, arrived at by trusting the contract.

The column and the declaration land together. That ordering is the whole point of how it was left,
and it is the fourth instance of `V11`'s shape. The generalisation is **still** not taken and the
reason has stopped being "wait for evidence": a general `idempotent_request` table would have to
store the *rendered response*, which is a second copy of client content with different retention
that goes stale the moment the row it describes is edited. The column form re-renders from the
live row through the same assembler the GET uses, so a replay and a reload cannot disagree.

**What a duplicate costs here** sits between a case (one extra row) and a run (a duplicated
checklist): two identical cards in To do are two people picking up the same work, and the second
one finds it already done. A board is a coordination surface, and one that misstates how much work
there is gets abandoned — which is §6l's whole thesis about trust.

### The mounting trap the key created

`useCreateWorkItem` now holds `useRef(newIntentKey())` — one key per **mount**. `TaskCreateDialog`
was mounted unconditionally behind `open={false}`, which was harmless while the form was stateless
and stops being harmless immediately: one key would serve every task typed on that page, and the
**second create would come back as a replay of the first** — a save the user watched succeed that
stored nothing. It is now `{creating && <TaskCreateDialog … />}`, which is what
`RunCreateDialog` and `PlanCreateDialog` already do and say so in their own comments. The idiom is
only correct together with the mounting.

## `BLOCKED` is a column, not a value

§53 left it out on purpose and stated the cost precisely: the board renders exactly the columns the
enum names, so a status with no column is a card that exists, is returned by the API, and appears
**nowhere on screen** — invisible rather than misplaced, which is the worse of the two.

That made it a contract change *plus* a label *plus* a grid column, and shipping only the first is
how the invisible card arrives. What was missing was the check:

```ts
const COLUMNS = [...] as const satisfies readonly WorkItemStatus[]   // rejects a non-status
                                                                    // and ACCEPTS A SUBSET
```

Only one direction was ever checked. The columns now derive from `workItemStatusLabels`' keys —
that map is `satisfies Record<WorkItemStatus, string>`, so a new status is a compile error until it
is labelled, and taking the columns from its keys means it then has a column automatically. The
derivation ends in an assertion, so `BoardPage` asserts the result anyway:

```ts
const _everyStatusHasAColumn: Record<Exclude<WorkItemStatus, Column>, never> = {}
```

Written as a `Record` rather than the shorter `extends never ? true : never` **because of the error
message**: the short form says *"Type 'true' is not assignable to type 'never'"*, which identifies
nothing, while this one says *"Property 'BLOCKED' is missing"*. Verified by deleting the column and
reading the failure. A guard whose message does not name the cause gets silenced rather than fixed.

The list moved to `lib/labels.ts` because the board stopped being its only consumer: the mock's
`POST /work-items` computes `allowedTransitions` from the same set and held its own literal — one
column behind the server the moment a fifth arrived.

## Three mock defects, all of one class

Found while wiring the picker, all in the family §52 and §53 were each spent on — **the mock
agreeing with itself about something no server was ever asked to do**:

1. **`POST /work-items` resolved *any* `assigneeId` to `userRef(0)`.** The picker would have shown
   the colleague you chose and rendered the first person in the directory on the card. A wrong
   answer that looks like a real one, in mock mode only.
2. **Derived cards carried non-empty `allowedTransitions`**, so mock mode offered a move that live
   mode disables and refuses with a 409 — the mock proving a capability the product does not have.
3. **The `Idempotency-Key` was accepted and ignored.** It genuinely dedupes now, so the property is
   provable in mock mode rather than merely asserted about the server. That is the project's
   standing rule and the third time it has had to be applied.

## Two things this slice got wrong first

**§19's defect a third time — and the class prefix is not enough.** `ProjectMemberEndpointTest`
took `c0001000-…-000000000001` for its project, which `ConditionLibraryEndpointTest` already owns
in a different company: the same **primary key**, so `project_pkey` failed 30 tests in the full
suite while the class passed alone. §53's lesson was *"every naturally-keyed fixture value needs
the class prefix"*; this one is a level lower — the **prefix space itself is hand-managed**, has no
registry, and `c0` was already taken. The one-liner that lists every prefix in use is in this
section's commit; run it before choosing one.

**The wrong timeout, raised.** See below — it made things worse and the failure message said so.

## The flake that predated the slice

The frontend suite was failing **1–6 tests per full run at random** — `GrantRoleDialog`,
`SuiteTree`, `RunCreateDialog`, `PermissionGate` — while every one of them passed alone.
Reproduced on a working tree with this slice's new tests removed, so it predates them; the six new
tests only made it frequent enough to notice.

The cause is duration, not correctness. Every failure read **"Test timed out in 5000ms"**, never an
assertion: a `userEvent` test that types a sentence and then drives a MUI popover is dozens of
real-timer round trips through React's scheduler, and with files running in parallel on a busy
machine the slowest crosses five seconds. `testTimeout` is now 20 s.

**The first attempt raised RTL's `asyncUtilTimeout` to the same 5 s and made it worse**, which is
the part worth carrying: that is the budget for *one* `waitFor`, so setting the two equal let a
single wait consume the entire test. Two timeouts, and the failing message named which one. Five
clean consecutive full runs after the correct fix.

A budget for waiting is not an assertion — a broken expectation still fails, just later. What it
stops is a green program being reported red because the machine was busy, and the usual response to
an intermittent red is to re-run until it passes. That habit is what hides a real intermittent
defect when one finally arrives.

## What the live pass proved

Driven in **LIVE mode** against `docker compose`, on the company §53 provisioned, with three extra
memberships seeded by SQL so the predicate had something to discriminate:

| Person | Membership | Grant on the project | On the list? |
|---|---|---|---|
| B. Admin | ACTIVE | **none** — Company Admin, COMPANY scope | **yes** |
| Dana Granted | ACTIVE | Test Engineer, direct | **yes** |
| Erik Ungranted | ACTIVE | none | no |
| Ivy Invitee | **INVITED** | Test Engineer, direct | no |

The picker showed exactly B. Admin and Dana. **Both halves of the predicate and both misses of the
`role_assignment` query are in that one table**: B. Admin holds zero project rows and must appear,
Ivy holds one and must not.

- **`V17` migrated a database already at v16, in place** — `Migrating schema "public" to version
  "17"`. Testcontainers cannot see this; it migrates a fresh container every time, which is exactly
  the blind spot the parallel-execution plan's Flyway rule is about.
- **A task was created assigned to Dana and moved into `BLOCKED`.** The card shows her *membership*
  display name (§6u), the row carries her id — not the author's, not `userRef(0)` — and
  `status = 'BLOCKED'` in Postgres.
- **"Assigned to me" excludes it for B. Admin**, which is the same fact from the other side: the
  Blocked column reads 1 on *Everyone* and 0 on *Assigned to me*.
- **`work_item.created_idempotency_key` is populated** for the new card and **NULL** for the one
  §53 created, side by side — which is precisely what the partial index exists to permit.
- **`GET /projects/{id}/members` answers 404 for an unknown project**, 200 for a real one, and its
  body carries exactly `id · displayName · email · avatarUrl` — the contract's `UserRef`, no more.
- All four move buttons on a derived card are `disabled`, including the new `BLOCKED` one.
- Console clean, `window.__tcmsErrors` empty, no page-level horizontal scroll at the five-column
  breakpoint.

**No defect was found by the live pass this time**, which is worth stating plainly because §47,
§49, §52 and §53 each found one and the record matters more than the streak. The nearest thing to a
gap: the browser window would not resize below the desktop breakpoint, so the five-column layout
was verified at `lg` (its own boundary, ~950 px of content) and **not** at 390 px. Below `lg` the
grid is 1 or 2 columns regardless of how many exist, so the count is not what changes there — but
that is an argument, not an observation.

## Gate evidence

```
backend:   ./gradlew build → BUILD SUCCESSFUL
           976 tests, 0 failures, 0 skipped      (954 + 22)
frontend:  npm run gen:api → WorkItemStatus gains BLOCKED; /projects/{id}/members generated
           npm run build   → ✓ built
           npm run lint    → oxlint, exit 0
           npm run test:run→ 22 files, 391 tests passed   (385 + 6)
live:      docker compose up → V17 applied v16 → v17 on an existing database
           LIVE mode browser pass — see above
```

**Mutations, each killed:**

- the members list without the grant filter — 4 tests.
- the members list without the ACTIVE filter — 2.
- `isAssignableTo` without `canView` (membership only) — 2, one on each side of the seam.
- the replay lookup skipped — 3.
- `''` instead of `null` for an unassigned task — 2.
- the picker hook pointed at `/users` — 1, and it is the one that fails if somebody "simplifies"
  the hook to the directory it looks like.
- the `Idempotency-Key` dropped from the create — 1.
- `BLOCKED` removed from `COLUMNS` — the compile-time guard, naming the status.

## Not done

- **A negative test whose input becomes valid does not fail.** `unknownColumnsAreRefused` sent
  `"BLOCKED"` as its unknown column; it would have gone green asserting a 400 that had become a
  successful move. Caught here by hand. **There is no check for this class**, and the general shape
  — a test whose *expectation* silently stops describing the code — is worth one.
- **No fixture-id registry.** See §19's third recurrence above. A computed check over the test tree
  (every `UUID.fromString` prefix declared by exactly one class) would end this family; it is small
  and it is not built.
- **`documentation` and `reporting` are still MSW-only** — `/doc-pages`, `/artifacts`, `/reports`,
  `cases/bulk`. `documentation` needs a **writer** to be honest, not only the read endpoints its
  existing tables would make cheap; see the orientation note above.
- **The symmetric backend check — a declared operation no handler implements — is still not
  built** (§53). It would currently list exactly the surfaces above, which is useful documentation
  and is why the handoff keeps having to correct the prose version of that list by hand.
- **Teams and memberships remain the next write surface with no endpoints** (§39, §45–§53). This
  slice made it sharper rather than smaller: `GET /projects/{id}/members` can now *show* who is on a
  project, and there is still no way to add a person to the company through the product — the
  fixture above needed three `INSERT`s.
- **The five-column layout is unverified below `lg`** — see the live-pass note.
- **A case that moves between suites still takes its whole execution history with it** (§52,
  unchanged).

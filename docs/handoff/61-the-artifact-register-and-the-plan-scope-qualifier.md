# §61. The artifact register — the dead link in the nav rail, and the qualifier the spec always had

**Date:** 2026-08-02 · **Migration:** `V19` (`V20` is next free) · Commits: `8759b42`, `30155fc`

## Why this slice, and how it was chosen

The orientation pass (§28's rule, §46's second question) started from the four contract paths with no
controller — `/artifacts`, `/cases/bulk`, `/reports`, `/shared-steps` — and asked which had a
**consumer**. Two did not: `/reports` has no hook at all, and `useSharedSteps` has zero call sites,
which is §35's dead-hook shape a second time. Two did:

- **`/artifacts`** — `ArtifactsPage`, lazily routed **and in the nav rail** (`AppShell.tsx:84`).
- **`/cases/bulk`** — `BulkActionDialog`, opened from the case grid.

`/artifacts` won because the link is in the **primary navigation of a deployed product**: on the
Hetzner box, "Artifacts" was clickable and the data never arrived. `cases/bulk` is eight actions
behind a preview-then-commit contract over mass case mutation and deserves its own slice rather than
being tacked onto this one.

## The decision that made this bigger than a read endpoint

Shipping the read alone would have repeated the note §54, §55, §56 and §57 each recorded — a table
nothing can fill, and §55's sharper version: the only way to get a row in is an operator at a psql
prompt. So the writer came with it. That is where the slice met the authorization core.

**The roles spec has said `artifact:edit` is "plan scope only" for a Test Engineer since M0**, and
V12's `role_permission.qualifier` CHECK accepted `ALL` and `OWN` only. The reason it had to be fixed
*before* the endpoint rather than after:

> **Test Engineer's grant was `ALL`** — V12's backfill default, correct for every row that existed
> then. So this did **not** fail closed. An `artifact:edit` gate written against the old data lets a
> Test Engineer rewrite the test strategy that every plan inherits read-only (plan §6n) — a silent
> widening, not an outage.

Project-scope artifacts are inherited by every cycle, so editing one edits them all; curating your
own cycle's sign-off is not the same act. That is the whole rule, and `V19` is what let it be said.

## `OWN` and `PLAN_SCOPE` do not compare, and the merge had been true by accident

`PermissionQualifier.strongest` was `Enum#compareTo` over a weakest-first declaration
(`NONE < OWN < ALL`). That was **exactly right** for a lattice with one restrictive value, and it
stopped being right the moment a second one arrived on a different axis:

| | restricts by | contains the other? |
|---|---|---|
| `OWN` | who wrote the row (`created_by`) | no |
| `PLAN_SCOPE` | what scope the row sits at | no |

A caller holding both would be covered for *"rows I wrote **or** rows at plan scope"*, and no single
value says that. An ordinal comparison answers that pair **by declaration order** — an authorization
decision taken in a line nobody would review as authorization code.

So `strongest` is written out and **throws** on that pair rather than picking. What keeps the throw
unreachable is a property of the *data*, and that property is now computed rather than trusted:

> **`SeedDataTest`: no permission key is granted with two different restrictive qualifiers.**
> Before V19 this held **vacuously** — one restrictive value cannot disagree with itself — which is
> why nobody ever had to think about it. §50's shape exactly: a property true by accident, and the
> accident invisible at the place the rule is written.

Adding the enum value made `canDoOnAuthored`'s switch non-exhaustive under `-Werror`, so **the
compiler forced every consumer to decide** what it meant. Both mismatched arms throw with a sentence
naming the right method, for `ofStored`'s reason: defaulting either way is a silent authorization
decision.

`RowScopeGate` mirrors `AuthorshipGate` — the row's fact crosses into `identity` and the verdict
comes back, so no module grows a second reading of what the qualifier means (plan §6b).

## The rule this slice could not follow, and what it did instead

§31's **disable-don't-hide** says an action the server will refuse renders disabled with a reason.
**That is structurally unavailable here.** A Test Engineer *holds* `artifact:edit`; what they lack is
the scope, and `GET /me/permissions` returns **keys only** — there is no qualifier on the wire for
`PermissionGate` to read. So the button enables for them and a project-scope submit is refused.

Rather than let that be a bare 403 on an otherwise valid form, the server answers **that one refusal
with a body** naming `scope`, and the dialog treats it exactly like a 400 — the sentence lands on the
selector the user is one choice away from getting right. A Viewer's refusal stays **bodiless**, and a
test asserts that: advice about changing the scope would be actively misleading to someone who holds
nothing at all.

**Putting the qualifier on `/me/permissions` is the real fix and is a slice of its own.** It is
recorded here rather than smuggled in, because it changes a shape eleven write affordances read.

## Module boundaries

`documentation` gained `identity`. Its `package-info` justified excluding it because *"nothing here
renders a person"* — true of the documentation space, **never true of the register beside it**, where
the contract puts `updatedBy` in `Artifact`'s `required` array. The claim was accurate about the part
of the module that existed and was stated about the module; both the package comment and
`ModuleStructureTest`'s hard-coded copy of the same sentence now record the reversal rather than
quietly dropping it (the working agreement's "write superseded decisions *and* their reversal").

`execution` is **still not** a dependency. A plan-scope artifact must know its plan is *this
project's* — `V1`'s foreign key proves only that the plan exists, and a row on a sibling project's
plan is findable by **neither** register, so it would be data no screen can show. `PlanFacts` reads
`test_plan` as native SQL through one file: the fourth instance of the pattern `work.BoardFacts`
names.

## Contract

- `POST` accepts the two **reference** kinds only. `FILE` needs the storage port (open question #28,
  resolved as a filesystem adapter and never built); `GENERATED_REPORT` needs the absent `reporting`
  module. `ArtifactInput.kind` is therefore a *narrower enum* than `Artifact.kind`, so a client
  generating types cannot construct the request that would be refused.
- `Artifact` gained **`docPageId`**. Without it the kind plan §6n steers people toward is the one
  kind the reader cannot open, while an `EXTERNAL_URL` beside it is clickable — an asymmetry that
  pushes people back to pasting links, which is the failure the register exists to prevent.
- `GET` declares its **400**: `scope=PLAN` with no plan, and a plan with `scope=PROJECT`, are refused
  rather than falling back to the project's register. Falling back would answer one question under a
  heading promising another — §47's dashboard tile in a different costume.
- The `javascript:`/`data:` refusal is a **security control**, not tidiness: the register renders
  `EXTERNAL_URL` rows inside an anchor, so a stored one is XSS in the product's own markup, on the
  screen where a colleague's link is clicked without being read. Allow-list, because the dangerous
  set is open-ended.

## Gate evidence

- **Backend** `./gradlew build` — **1111 tests, 0 failures, 0 skipped** (from 1078).
- **Mutation-checked rather than watched to pass.** Deleting the qualifier call left **85 tests
  running and exactly one red** — the Test Engineer refused `PROJECT` scope, which is what
  `ArtifactEndpointTest`'s header claims. Loosening `SeedDataTest`'s `HAVING` made the qualifier
  sweep report rows, so it is not vacuous.
- **Frontend** `npm run build` clean · `oxlint` 0 warnings · `npm run test:run` **426/426** (+8: six
  new, and **two generated by the contract-hygiene checks** for the new operation — so §48's and
  §50's sweeps cover it automatically).

## The live pass — LIVE mode against the real backend

**`V19` applied to an already-migrated database, v18 → v19, `installed_by=tcms_owner`** — the one
thing Testcontainers structurally cannot check, because it migrates a fresh container every time.
The CHECK constraint reads `ALL, OWN, PLAN_SCOPE` on the real box and Test Engineer's grant is
`PLAN_SCOPE`.

Driven end to end:

| What | Result |
|---|---|
| The nav-rail link | **Renders** — and an empty register shows its *empty state*, not §57's permanent loading skeleton |
| `javascript:alert(document.cookie)` | Refused on the field, before the server |
| Register an external link (Company Admin) | 201, row renders with `↗`, `by B. Admin` — the **membership** display name (§6u), not the account's |
| Register a doc-page reference | 201, renders as an internal link that **navigates to DOC-4** |
| Plan register | Shows the project's document tagged **"from project"** — plan §6n's inheritance |
| Empty documentation picker | "This project has no documentation pages yet." |
| **Test Engineer → PROJECT scope** | **403, message on the "Belongs to" selector, form stays open with everything typed** |
| **Same Test Engineer → PLAN scope** | **201** |
| psql | Ivy's row exists **once, at PLAN scope**; her PROJECT attempt wrote nothing; all rows `ACTIVE` (§6g); three audit rows each naming their scope |

The Test Engineer run closed the one seam the unit tests could only mock: **the real 403
`ProblemDetail` body parses into `ApiError.fieldErrors` and lands on the control.** Reaching it
exercised §59's `issue-invitation`, §18's accept flow and §39's grants on the way.

**No defect on the primary surface** — the second slice in eight where the live pass found none
(§54 was the first). Console clean throughout.

## Found on the way, not fixed — deliberately

1. **The plan filter is component state, not the URL.** `ArtifactsPage` keeps `planId` in
   `useState`, while CLAUDE.md's convention is *"filter state lives in the URL (`useSearchParams`)"*.
   Pre-existing (Phase 1/2 code), not introduced here, and the consequence is real: **you cannot
   send anyone a link to a plan's register**, and a reload loses it. Every other filtered screen in
   the app is shareable. One-file fix; left out because it changes behaviour beyond this slice.
2. **`issue-invitation` will re-invite an ACTIVE user.** Run against an already-active account it
   prints a fresh link and the account can set a new password — an effective administrator password
   reset with nothing marking it as one. It may be intended (§59 built `reissue` for the operator who
   *explicitly asked*), but the ACTIVE case is not what that section describes. `identity`'s call.
3. **The dialog defaults `kind` to `EXTERNAL_URL`** while the option order and copy claim to steer
   toward documentation. Defaulting to the doc-page kind on a project with no pages would open onto
   an empty required picker, so the default is defensible — but the two intentions disagree and the
   ordering is doing all the steering.

## What is deliberately not built

`FILE` and `GENERATED_REPORT` (above) · **edit, supersede and delete** — the register is
append-and-read, so `write_once`, `superseded_by_id` and `ArtifactStatus.SUPERSEDED`/`ARCHIVED` are
mapped and honoured on read but nothing writes them · artifact **versions and tags** (plan §6n's
§6j treatment) · the **evidence-bundle export**, which §6n calls the strongest argument for the
feature and which needs `reporting` · project-configurable **categories** (the eight offered are
§6n's defaults, and the server takes free text) · staleness flagging and per-project storage usage.

## Where this leaves the MSW-only surfaces

`reporting` and `cases/bulk` — plus the two dead ends (`/reports` has no hook, `useSharedSteps` has
no call site, and both should be **deleted or built** rather than left as contract that looks
implemented). `cases/bulk` is the one with a live consumer and is the obvious next rung.

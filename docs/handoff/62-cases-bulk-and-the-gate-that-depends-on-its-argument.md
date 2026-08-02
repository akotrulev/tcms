# §62. `cases/bulk` — the gate that depends on its argument, and the option that could not be chosen

**Date:** 2026-08-02 · **Migration:** none (`V20` still free) · Commits: `b0298d6`, `4a88558`, `cb3216a`
**Plan:** [`docs/plans/2026-08-02-cases-bulk.md`](../plans/2026-08-02-cases-bulk.md)

## Why this slice

§61 named it: of the four contract paths with no controller, `/artifacts` and `/cases/bulk` were the
two with a live consumer. `BulkActionDialog` has been wired into `CaseListPage` since Phase 1 and
called an endpoint **no controller has ever served**. All eight declared actions are implemented.

## The finding: `SET_STATUS` cannot be gated on `case:edit`

Every sibling write in `repository` names one permission at the top of the class. This one cannot.

> The roles spec's Review table grants **`case:approve` to Lead and up and explicitly not to Test
> Engineer**, while `case:edit` reaches Test Engineer. Plan §6g: *"Only Admin and Test Lead can
> perform the final transition into APPROVED"*, and *"the state machine has exactly one gate, held by
> `*:approve`"*.

So a bulk endpoint gated on `case:edit` — the obvious reading, and the one every neighbouring write
uses — lets a Test Engineer **approve two hundred cases in one click**. Silently, because they hold
the permission the gate names. §61's shape exactly: not a fail-closed outage but a quiet widening,
invisible at the line that grants it. **`case:approve` had zero consumers in Java** before this
class; it has been seeded by `V3` and granted correctly per role since M0, and nothing ever asked it.

The permission is therefore resolved *from the parsed command* before the gate is called
(`BulkOperations.permission`). **Mutation-verified**: reverting it to a constant leaves 35 of 37
green and turns exactly the two approve tests red.

Two consequences worth stating:

- **`lifecycle_status` had no writer at all** before this slice. `CaseUpdateRequest` omits it
  deliberately — *"a status an ordinary edit can set is an approval anyone grants themselves by
  saving"* — so this endpoint is the whole product's approval path, not a convenience beside one.
- **`APPROVED` is reachable only from `IN_REVIEW`.** Without that the gate would guard a door
  standing beside an open one: approval could be reached from `DRAFT` and review would be advisory
  in a workflow whose entire purpose is that it is not.

The gate asks **one** key rather than both, which is sound only while approvers are a subset of
editors — a property of the seeded catalogue, not a law. `SeedDataTest` now derives it, so a future
role granted approve without edit fails the build here rather than getting authority it should not.

## The other load-bearing decision: two of the eight actions are inside the version hash

`CaseVersions.hash` digests `CaseEdit`, whose members include `suiteId` and `automationStatus`. So
`MOVE_TO_SUITE` and `SET_AUTOMATION_STATUS` change **authored content** and must write a
`test_case_version` row. Skipping it is silent and it surfaces in the wrong place:

> The next single-case save would compute a hash that no longer matches the newest stored version,
> and **that** save would write a version **crediting whoever saved next** with the bulk change — in
> the one table whose job is answering who changed what.

Writing the version needs a `CaseEdit` describing a case the caller never sent one for.
`CaseEdit.of` reconstructs it by **round-tripping through `CaseEdit.validate`** rather than reading
the row's getters, because a second normalizer is the failure mode: if it trimmed where `validate`
does not, every hash it produced would disagree with every hash the edit path produces, and the next
save of every touched case would write a version with an empty diff. One normalizer, so they cannot
disagree by construction. Pinned by a **round trip** — bulk-move a case, `PATCH` it unchanged, assert
no second version — because the failure is a hash equality no reading finds. Mutating the
reconstructor to differ by a `changeComment` turns it red.

`changeComment` is deliberately null on a bulk-written version: it is hashed, so "changed in a bulk
edit" would make the next ordinary save differ by the comment alone and write an empty-diff version.
What happened in bulk is in the audit event, which is where it belongs.

The other five actions touch `lifecycle_status`, `owner_id`, `case_tag` and `case_condition`, none of
which `CaseEdit` carries, so they correctly write none. That asymmetry is a fact about what
`CaseEdit` hashes, not a policy, which is why it is asked of the command
(`BulkCommand.changesAuthoredContent`) rather than configured.

## The accounting invariant, and the mock defect it exposed

`changed + unchanged + skipped == total`, for every request. **The MSW mock violated it**: its loop
did `if (!row) continue`, so an id that was deleted, in a sibling project, or fictional vanished from
the report while still counting toward `total` — a user who selected 44 was shown an accounting of
42 and told nothing about the other two. An id the server cannot act on is `Skipped` **with a
reason**, never dropped. The mock also answered "not supported yet" for six of the eight declared
actions. Both are the "mock agrees with itself" class §52 names, invisible while it was the only
implementation.

## The live pass — and it found the defect every gate missed

Driven in LIVE mode against the real backend, as Olive Ops (Company Admin, holds `case:approve`).

**`PermissionGate` cannot be used inside a MUI `Select`, and the way it fails is silent.**

`Select` clones its **direct** children to inject `role="option"`, `data-value`, `aria-selected` and
the handler that actually selects the value. The APPROVED `MenuItem` was wrapped in the gate's render
prop, so MUI never saw it — it rendered as an inert `role="menuitem"` with no `data-value`: present,
clickable-looking, and **impossible to choose**. A Company Admin who holds `case:approve` could not
approve anything through the UI. **The headline feature of the slice was unreachable**, with
`npm run build` clean, `oxlint` at 0 and 452 tests green.

The component test passed **for the wrong reason**, and that is the transferable part:

> It found the item by `data-testid` and asserted `aria-disabled`. An element MUI never processed
> carries no `aria-disabled` either — so *"not disabled"* and *"not an option at all"* read
> identically. The enabled-case test passed the same way, on an option nobody could select.

Fixed by reading the verdict with `useCan` in the component body and passing a plain `disabled` prop
to a bare `MenuItem`, with the reason rendered *inside* the item. `PermissionGate` stays right
everywhere it wraps a button or a bare `Menu` item; **a `Select`'s children are the one place a
wrapper changes what the child *is*.** The tests now assert through `role="option"`, check that
selecting APPROVED lands in the payload, and count options against `lifecycleLabels`. Mutating the
item back into a wrapper turns 3 of 13 red where the old suite went fully green.

What the live pass then proved, end to end:

| What | Result |
|---|---|
| Four options render with `data-value`, APPROVED enabled for a Company Admin | ✅ |
| Preview over 4 cases | **1 will change · 1 already matches · 2 skipped** — and 1+1+2 = 4 |
| The two DRAFTs | Skipped, *"A draft must be reviewed before it can be approved."* |
| The preview | Wrote **nothing** — statuses unchanged, no audit row |
| Commit | TC-3 `IN_REVIEW → APPROVED`, `updated_by` stamped; the DRAFTs' `updated_by` still null |
| Audit | **One** row, action `case.bulk.approved`, 1 target, `{"changed":1,"lifecycleStatus":"APPROVED"}` |
| Versions after a status change | **0** — `lifecycle_status` is not authored content |
| `MOVE_TO_SUITE` | TC-1 → Settlement, `current_version_no` 1→2, **one** version row, attributed, no change comment |
| Console | Clean |

## Contract

`payload` was `additionalProperties: true` — an untyped bag neither end checked, so a misspelled
`lifecycleStatis` was an argument the server silently never received. It is now `BulkPayload`, typed.
It is deliberately **not** a `oneOf` discriminated on `action`: the server must validate the
combination anyway, being the only side that knows the action, so the union would cost every consumer
a narrow to buy a check the client cannot use. It is on `ContractConventionTest`'s no-`required`
exception list with that reasoning.

`mode` and `dedupe` are **deleted rather than implemented** (§50): nothing sent them, no handler
would have bound them. What they reached for is now stated as behaviour — `caseIds` is always
de-duplicated, `ADD_TAGS` is additive and set-valued, an attachment always appends.

`conditionKind` carries **no `default:`** — openapi-typescript renders a defaulted property as
*required*, which would force every caller to send a value the server is happy to choose.

`SUBMIT_FOR_REVIEW` stays on the wire because §6g uses the vocabulary, and **aliases to
`SetStatus(IN_REVIEW)` at the parse boundary**, so one state change has one implementation. A
contradicting `lifecycleStatus` is refused rather than ignored: the request would otherwise apply as
something other than what it says, and an `APPROVED` slipped in there would bypass the permission
`SET_STATUS` would have asked.

## Along the way

- **`CaseRenderer`** extracts the render method `CaseCreateService` and `CaseUpdateService` each
  carried a byte-identical copy of, each arguing in its own javadoc against a third existing. This
  slice would have been the third.
- **§19's fixture-collision defect, a fifth time.** `CaseBulkEndpointTest` took the `b0` prefix space
  and its `BANKING` constant *was* the board test's project id; `project_pkey` is global, so 30 board
  tests went red while the new class passed alone. The prefix space is still hand-managed.

## Gate evidence

- **Backend** `./gradlew build` — **1156 tests, 0 failures, 0 skipped** (from 1111).
- **Frontend** `npm run build` clean · `oxlint` 0 warnings · `npm run test:run` **453/453** (from 426).
- **Live** — the table above, verified in psql.

## Found and not fixed

1. **The Test Engineer refusal was not driven in a browser.** It is covered by
   `CaseBulkEndpointTest.engineerMayNotApprove` (real Postgres, mutation-verified) and by the RTL
   disabled-option test (mutation-verified), so the gate itself is proven at both ends. What is
   unproven live is the real 403 body reaching the dialog's alert. Reaching it needs a second signed-in
   persona, which needs an account and a password to be set.
2. **`useSharedSteps` still has zero call sites and `/reports` still has no hook** — §61 listed both
   as dead contract, to delete or build. Untouched here.

## What is deliberately not built

The **review workflow's opt-in project setting** (§6g: optional, off by default, per project) and
**author-cannot-approve-own-work** (§6g's *"single most valuable setting"*) both need a
project-settings mechanism that does not exist; `AuthorshipGate` is the seam the second would use.
The four lifecycle states exist on every project regardless — the column is `NOT NULL` and the
dashboard tiles them — so what is deferred is the *reviewer apparatus* (assignment, LGTM, comments),
not the states. **`REJECTED`** is in §6g's diagram and not in `LifecycleStatus`; reject is
`IN_REVIEW → DRAFT`. Whether **deprecating** an APPROVED case should need more than `case:edit` is a
real question the catalogue has no key for, so §6g's "exactly one gate" is followed literally rather
than inventing a permission.

## Where this leaves the MSW-only surfaces

**`reporting` is the last one** — `/reports`, which has no SPA hook at all. Every other contract path
with a live consumer now reaches a real server.

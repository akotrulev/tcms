# §75. The suite lifecycle — the last status nothing ever wrote, and the rescind §73 left on cases

**Date:** 2026-08-02 · **Branch:** `master` · **No migration; `V22` is still free.**

## What this slice is

`suite.lifecycle_status` had **exactly one writer** — `Suite.create`, writing `DRAFT` — and nothing
else has ever touched that column. So three of `LifecycleStatus`'s four values were unreachable on a
suite, and **every suite on every real instance has been a draft for its entire life**, including the
ones a team agreed on a year ago. `suite:approve` had zero consumers in Java, seeded by `V3` and
correctly granted since M0.

This is `TestRun`'s finding in §72 and `TestPlan`'s in §73, one aggregate over, found the same way —
and `Suite.applyEdit`'s own javadoc named the reason its mutator was absent, which is the same
sentence that names this slice: *"a status an ordinary edit can set is an approval anyone can grant
themselves by saving"*.

**§74 named the reviewer apparatus as the next slice and this is not that.** Re-deriving from the
code rather than from the last section's list (§28's standing rule) found something smaller sitting
underneath it: §6g's ladder has *three* subject types, cases got their gate in §62 and plans in §73,
and the suite's had never been built at all. The reviewer half — `review`, `review_participation`,
`review_comment`, plus a per-project opt-in setting with **no storage anywhere** (`project` has no
`settings` column; only `company` does) — is still the largest coherent gap, and it is now the
*only* remaining part of §6g's item-side machinery, which is a better place to start it from.

## The ladder is ONE ladder, and building the second copy is what exposed the first one's gate

`LifecycleStatus` is the status of **a suite or a case** — its own javadoc says so, `V1` spells the
identical four-value check constraint on both tables, and §6g draws one diagram and then lists what
review *means* per artifact rather than a different set of moves. So `LifecycleLadder` is one pure
class read by both writers, rather than a second table maintained beside the first under an
instruction to keep them in step. That instruction is §58's E1 and §70's verb list, and in both of
those the copy had already drifted by the time anybody looked.

**The plan's ladder is deliberately not folded in.** `PlanStatus` carries `ACTIVE` and `CLOSED`, an
execution phase these four do not have, so unifying them needs a supertype that does not exist and
would give the table two arms no case can reach. The shape is shared; the ranks are not.

Writing the second consumer is what made the first one's defect visible, and that is the slice's
real content.

## The defect: the case gate was keyed on the target, and the rescind was live

`PlanLifecycle`'s header, written in §73, names this class by name — *"Reading that as 'the target
decides' — the shape `BulkOperations.permission` uses for a case — is not enough here"* — and then
§73 fixed only plans. On a case it was still live:

- `BulkOperations.permission` answered `case:approve` **only when the target was `APPROVED`**, and
  `case:edit` for everything else, under a comment stating *"Only the target status can raise it …
  editing is producing"*.
- `CaseBulkService`'s ladder had exactly one rule: `APPROVED` only from `IN_REVIEW`.

So a Test Engineer could select two hundred approved cases, ask for `DRAFT`, and **rescind a lead's
sign-off with the permission they use every day** — silently, over a filtered selection, through a
dialog built for exactly that volume. §61's shape: not a fail-closed outage but a quiet widening,
invisible at the line that grants it.

**Leaving an approval is an approval act too.** `LifecycleLadder.permissionFor` takes both ends, and
three acts cost the approve key: granting the sign-off (`APPROVED`), retiring the artifact
(`DEPRECATED`), and taking either back — any move down out of a state at or above `APPROVED`. Roles
spec §A4 principle 1, unchanged: a Test Engineer is never blocked from producing, only from unmaking.

**Why the fix is per row and not a higher floor.** One end of every edge is the row's stored status,
and a bulk selection is a filter's output that routinely mixes statuses. Raising the request-level
gate to `case:approve` for all status changes would refuse a Test Engineer submitting their own
drafts — which §6g says *"needs only edit rights"* — and would refuse the whole operation over rows
the caller may in fact move. So the request gate stays the **floor**, and the edge rule is applied
per row as a `BulkVerdict.Skipped` with a reason, which is this endpoint's standing answer to every
other partial failure.

## Three transitions changed on cases, and each is a correction rather than a tightening for its own sake

Sharing the table made the case ladder stricter in ways worth stating, because they reversed a
shipped behaviour and one of them reversed a *test's own recorded reasoning*:

- **`APPROVED → IN_REVIEW` no longer exists.** §6g draws one submit edge and it starts at `DRAFT`.
  The test asserting the old behaviour justified it in a comment — *"sending an APPROVED case BACK
  to review is an edit and not an un-approval a lead must sign off"* — and both halves of that were
  wrong. An approved artifact that needs another look loses its approval first, or the trail shows a
  thing under review while it was also signed off. `PlanLifecycle` made the identical call in §73.
- **`DEPRECATED → APPROVED` / `→ IN_REVIEW` are refused**, with their own sentence rather than the
  generic one: a retired artifact comes back as a `DRAFT`. §6g's clone rule makes the same argument —
  approval attaches to reviewed content by a named person, and carrying it to something nobody read
  is the laundered sign-off an audit exists to catch. Telling that caller to "submit it for review
  first" names a step they cannot take from where they are.
- **Deprecating now costs the approve key.** Retiring is the soft form of a delete, and the delete is
  Lead-only (`suite:delete` outright, `case:delete` `OWN`-qualified), so leaving the retire at the
  edit key routes around both. This does narrow what a Test Engineer could do to a *draft* case
  through the bulk endpoint, deliberately — deprecating a colleague's draft is retiring their work,
  not producing. **The `OWN`-qualified refinement is the obvious next rung and is not built**: an
  author retiring their own draft is producing, and the term `AuthorshipGate` already holds would say
  so.

`DEPRECATED` stays reachable **from anywhere**, including from a draft nobody reviewed. The
alternative is a dead end reachable by ordinary use: a folder holding cases cannot be deleted (the
delete refuses a non-empty suite), so if retiring required an approval first there would be no way
out of it at all.

## What the suite endpoint reuses rather than re-derives

`PUT /projects/{projectId}/suites/{suiteId}/status`, gated through
`ProjectService.writeGrantedOnTarget` — §69's seam, its fourth consumer — because the permission is
a property of a status only the database holds. Three decisions are §72's and §73's, re-checked
rather than copied:

- **No `If-Match`.** The body names an absolute target state, so there is no lost update to protect;
  the `PATCH` beside it keeps its header, because a name typed over a stale copy really does lose the
  other author's words.
- **The parse runs before the gate, and an unreadable status asks the LOWER key.** The permission
  cannot be computed without the target. Falling back to `suite:manage` refuses a Viewer before they
  learn what the ladder accepts, while a caller who may edit gets the field error they can act on.
- **Already-there is a 200 with the suite and nothing written** — no stamp, no audit row — so a
  repeated click cannot bury the change that actually happened.

**A status change writes no `suite_version` row and does not bump `current_version_no`.** A suite
version is its *composition* (`V1`'s column comment, and `SuiteVersions`' whole argument), and
approving a folder does not change what is in it. `TestPlan.applyStatus` declined the same bump in
§73.

The read-back helper moved: `SuiteWriteService`'s private `view` is now `SuiteService.viewOf`, so the
new service reuses it instead of becoming a second copy. That is `CaseRenderer`'s lesson applied
*before* the fourth copy rather than after it — §68 found the read path had been a fourth copy on a
case, invisible while the copies agreed and visible the moment a field was added.

## The fixture finding, from both directions at once

**The backend fixture seeded every suite `APPROVED`** — hard-coded in `SuiteWriteEndpointTest`'s
`suite()` helper — while no suite on any real instance has ever been anything but `DRAFT`. §72's
rule, stated there as *the mock's fixtures never sit in the state a fresh install produces*, applies
to a test fixture just as directly. The main tree is drafted now, with four dedicated suites carrying
one status each so every edge has a start row.

**And the mock's fixtures have carried `APPROVED` and `IN_REVIEW` folders since Phase 1** — so mock
mode has been showing a signed-off suite tree the product could not produce. That is §74's exit
criteria one aggregate over: the mock was right about the *feature* and the server had no writer.
Both directions of the same class, in one slice.

The mock gained a `DEPRECATED` folder for §68's reason: the one edge that leaves that state is
otherwise undrivable without a backend.

## What shipped

**Backend** — `LifecycleLadder` (the table, `Keys`, `Decision`, `permissionFor`, `rank`),
`SuiteStatusService`, `SuiteStatusRequest`, `Suite.applyStatus`, `SuiteService.viewOf`,
`PUT /suites/{suiteId}/status`, six audit actions (`suite.submitted` / `approved` / `deprecated` /
`withdrawn` / `unapproved` / `revived` — distinct verbs, because `DRAFT` is reached by three
different acts). `BulkOperations.CASE_KEYS` and a rewritten `permission` javadoc;
`CaseBulkService.evaluateStatus` reading the shared table plus the per-row edge rule, `leadOnly`,
and `holds` generalised from `holdsEdit`.

**Contract** — the operation and `SuiteStatusInput`.

**Frontend** — `useSetSuiteStatus` (the suite is a *mutation variable*, because the caller is a menu
over many folders), `suiteMoves` in `SuiteTree` with a per-edge `PermissionGate`, the status badge on
non-draft nodes, the refusal rendered in the rail naming its folder, `mocks/lifecycleLadder.ts` as
the mock's single copy of the table **including the per-row permission half**, and the mock's suite
status handler ordered exactly as the server orders it (resolve → permission → transition), so a mock
cannot teach a 409 where live gives a 403.

## Gates

`./gradlew build` — **1397 tests, 0 failures, 0 skipped** (1378 → 1397: 19 written). `npm run build`
clean, `npm run lint` 0 warnings, `npm run test:run` **594/594** (584 → 594: **8 written + 2
generated**, the 2 being 1 new `api.*` call site × the 2 per-call-site contract checks — §70's
arithmetic checked rather than noted, on its fifth opportunity).

**Six mutations run, three queued.** Four backend and two frontend:

| Mutation | Red |
|---|---|
| `permissionFor` loses the unmaking arm — the target-keyed rule | **exactly 2**, one per aggregate |
| the suite gate becomes the constant `suite:manage` | **exactly 3** |
| `IN_REVIEW` reachable from anywhere | **exactly 1** |
| the per-row approve check on cases is deleted | **exactly 2** |
| `suiteMoves` puts `APPROVED → DRAFT` on `suite:manage` (the client's copy of the defect) | exactly 1 |
| the status badge renders on every node | exactly 1 |

**The first one is the one to read.** It is the defect this slice exists to close, and it reddens on
*both* aggregates at once out of 112 tests — which is the evidence that the two ladders are genuinely
one and that the case half was not a hypothetical. The frontend pair was not optional: all 17 of that
file's tests passed on the first run, which §66 established is the moment to distrust them.

## Not browser-verified

The tenth slice in the deferred queue. Rows are in
`docs/verification/frontend-browser-checks.md`. **The live-only rows lead**, because the state this
slice fixes is one live mode has always been in and mock mode has essentially never been in — §72's
inversion, exactly.

## What this leaves for the next slice, named rather than implied

**The review workflow's reviewer half is now the only unbuilt part of §6g**, and after this slice
every item-side ladder exists: a case's, a suite's and a plan's. What is missing is what §6g puts
*inside* `IN_REVIEW` — reviewer assignment, threaded anchored comments, LGTM and request-changes —
which is what `case:review`, `suite:review`, `plan:review` and `case:comment` are for, and why those
four stay unconsumed correctly rather than by omission. It needs three tables (`review`,
`review_participation`, `review_comment`), the per-project opt-in setting §6g requires, and therefore
**a `V22`**: `project` has no `settings` column, only `company` does. `PlanLifecycle`'s header and
`LifecycleLadder`'s both lean on that setting's absence to justify a ladder that does not require
approval, so building it is a change to two ladders as well as three tables.

Two smaller things this slice deliberately did not take:

- **The `OWN`-qualified deprecate**, above — an author retiring their own draft, using the authorship
  term `AuthorshipGate` already holds.
- **`@Version` on `TestRun` and `work.WorkItem`**, still latent, still carrying §74's reason that the
  run one is not a free repair.

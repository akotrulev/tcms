# §228 — the status a case could not be given, and the folder nobody could see reviewed

**A1 + A7 of `docs/audits/2026-08-15-flow-review-and-improvements.md`**, whose §0 ledger is the
tracker for the authorised A1–A8 scope. The second slice of that run; the first is
**§227** (A2 + A3 + A5, the reports slice), `docs/handoff/227-the-reports-that-stopped-disappearing.md`.

**A section of its own rather than a second phase of §227**, because a § number is what everything
in this repo cites and the two slices share nothing but the audit that asked for them — different
module, different contract paths, different gates. The audit's ledger and the queue rows written
here say `§227 LC-*` / `§227 PM-9…` and are left that way deliberately: **an id is never
renumbered**, and those prefixes were minted before this section was.

Touches contract, `backend/repository`, `frontend/features/cases`, and both `schema` copies. **No
migration**, no permission moved, no new permission key, **no change to `POST /cases/bulk`**.

---

## A1 — a case had no status action of its own

### What was broken

`CaseBulkService` was the only writer of `test_case.lifecycle_status` outside a create. So
submitting *one* case for review meant: leave the case, find its row in the grid, select it, open
the bulk dialog. Four steps and a different screen to do the thing the case's own page is about —
and §6g calls the case the common artifact. Suites and plans have had `PUT …/status` since §73/§74.

`ReviewPanel` was **already mounted** on `CaseDetailPage`, which is worth recording because the
audit's A1 asks for it: that half was true, and what was missing was the control that moves the
case the panel is about.

### What shipped

- **`PUT /projects/{projectId}/cases/{caseId}/status`** → `CaseStatusService`, which is
  `SuiteStatusService`'s twin over the same `LifecycleLadder`, with the four decisions in the same
  order: parse, gate on the **edge** (`writeGrantedOnTarget`, because `permissionFor` needs both
  ends and one of them is stored), ladder, then §6g's review policy, then write.
- **`KEYS = BulkOperations.CASE_KEYS`**, read rather than re-spelled. A second
  `new LifecycleLadder.Keys("case:edit", "case:approve")` would be a hand-maintained copy of a
  neighbour, and the failure it produces is the worst kind: two endpoints gating the same edge on
  different keys, both looking correct.
- **Per-act audit verbs** — `case.submitted`, `case.approved`, `case.unapproved`, `case.withdrawn`,
  `case.revived`, `case.deprecated` — the vocabulary the suite and plan endpoints already use. The
  bulk path keeps `case.bulk.*`: a batch is a different act, and an auditor reading four hundred
  rows under one event wants to know that is what happened.
- **A `Status` menu on `CaseDetailPage`**, driven by a local `caseMoves` table that mirrors
  `LifecycleLadder.permissionFor` as a *disabling hint, never the gate* — `suiteMoves` and
  `planMoves` have the identical relationship to the identical class.

### The decision worth carrying: this is not a one-row bulk operation

The bulk endpoint **skips a row with a reason** when the caller's key does not buy the edge, and
that is right there — a selection is a filter's output and may hold rows of several statuses, so
refusing the whole operation would refuse the rows the caller may in fact move. **One case is not a
selection.** Here the same situation is a **403**, which is the answer somebody looking at one case
can act on; a "skipped: 1" report about the case on the screen in front of them is a refusal wearing
a batch's clothes.

`SingleCaseStatus.engineerIsForbiddenWhereBulkWouldSkip` drives the same edge, the same caller, both
endpoints, and asserts **both shapes** — it is the test that would go green if somebody later
"unified" the two by delegating this one to the other.

### What the menu deliberately does not predict

Two things can refuse after the click. The ladder is one, and the menu avoids it by offering only
the edges the current status has. The other is **§6g's review policy** —
author-cannot-approve-own-work and the minimum peer LGTM — which depends on the review record, the
project's settings and who is asking. Predicting that here would be a third implementation of
`ReviewService.refuses`, and it would be wrong on exactly the cases where it mattered. It arrives as
a 409 and is read beside `ReviewPanel`, which is the panel that explains it.

---

## A7 — a suite's review record was written, gated, and rendered nowhere

`suite:review`, `suite:approve` and `reviewEnabledSuite` have existed since §74/§81, and the rail's
kebab has moved a folder along the ladder for as long. What had no surface **at all** was the
result: who was asked, who signed off, what they said. `ReviewPanel` mounted on cases and plans
only, and the one route a suite has (`/suites/:suiteId/history`) is about executions.

`SuitePanel` renders above the case grid whenever a suite is selected: key, name, lifecycle chip,
membership count in words, an execution-history link, and `ReviewPanel` for `SUITE`.

**A panel rather than a suite detail page**, of the audit's two options. A suite is flat since §98 —
a named collection whose members are three arms — and every write it has hangs off the case-list
rail, so a page of its own would be a screen whose only content is a header and this panel, reached
by navigating *away* from the list you use to decide which suite you mean. It is not in the rail
either: 260 pixels renders assignees, verdicts and a comment thread as a stack of ellipses.

**No `currentVersionNo` is passed**, and that is a decision. A suite's version is its composition,
and `suite_version` has been written once and never again since §98 — so passing it would make the
panel compare the reviewed version against a number that cannot move, and print "the content has
changed since this review" *never*, on a folder whose membership changes daily. `ReviewPanel`'s prop
is optional for exactly this. **This is the sharpest edge the audit's C1 names** and it is untouched
here: the panel declines to make the comparison rather than making it wrongly.

---

## Two changes made along the way

- **`TestCaseDetail` was a contract name for a schema that does not exist.** The first draft of the
  200 response cited it; the case detail's schema is `TestCase`, and every existing read serves that.
  Caught by the SPA's §197 harness as `Cannot read properties of undefined (reading '$ref')` — worth
  knowing, because that message names nothing and the cause is always a `$ref` to a schema the
  contract does not have.
- **`CaseBulkEndpointTest` now covers both writers**, and its `@DisplayName` and header say so. The
  filename is unchanged so citations to it resolve. The fixture it already had — a case in each
  ladder state, the three personas the gate turns on — is exactly what the new endpoint needed, and
  a second class would have been a copy of that seeding under an instruction to keep it in step,
  which is §58's E1 in a test.

---

## Gates

All on `shanks`.

| Stack | Result |
|---|---|
| `backend` `./gradlew build` | **BUILD SUCCESSFUL** (+13 in `SingleCaseStatus`) |
| `frontend` `npm run build` / `lint` / `test:run` | clean, 0 warnings, **1452 tests / 108 files** (+10) |
| `packages` build / lint / tests | clean, **78 / 128 / 123** |

Queue rows written and **not run** (§65): `§227 LC-1…LC-6` in
`docs/verification/frontend-browser-checks.md` (lanes **L1/L2**) and `§227 PM-9…PM-15` in
`docs/verification/backend-mutation-checks.md`. Three of the mutation rows **predict zero reds and
say so** — `PM-11` (the review-policy arm: this fixture has review off, so nothing here can see it),
`PM-12` (`reviews.moved` on the single-case path has no reader in this class) and, by the same
reasoning, any assertion about a review record after a single-case move. Those are the gaps a second
writer introduces, named rather than assumed away.

---

## What is NOT done

- **A4, A6, A8** — the rest of the authorised scope. The audit's §0 ledger is the tracker.
- **The single-case path's `reviews.moved` call has no test that reads the row it writes.** Recorded
  as `§227 PM-12` with a predicted zero. It is the same shape §218 phase 1 named for the outbox: a
  producer pinned on one side and nothing crossing between.
- **§6g's review policy is not driven over HTTP on this endpoint.** It needs review enabled, an open
  review and two accounts — `§207 AQ-*`'s requirement plus a settings change — so it is a browser
  queue row rather than a claim.
- **C1's versioning story is untouched** and `SuitePanel` is written to survive it: the panel
  declines the version comparison instead of making it against a number that never moves.

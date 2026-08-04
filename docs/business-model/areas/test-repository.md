# AREA-003 — Test Repository

**Created:** 2026-08-03 · **Owned by:** `be-shared-business-documentation`
**Derived from:** code by `be-documenter` @ `93444b2` on 2026-08-03

Covers the `repository` module: the suite tree, test cases, bulk case actions, the shared
condition and shared-step libraries, and the project dashboard. This is where test content is
authored, organised, and signed off; running it is AREA-004, discussing it is AREA-005.

## What this area is for

The authored, shared record of what a project tests: a suite tree of folders, the test cases
inside them, and two reusable libraries (conditions, shared steps) that keep repeated content
consistent instead of copy-pasted.

## Actors

- **Test Engineer** — the primary author: creates/edits everything, cannot approve or delete
  others' cases, cannot delete a suite subtree. `#observed`, matches
  `docs/specs/2026-07-30-tcms-roles-permissions-naming.md` A2/A4.
- **Test Lead / Company Admin / Instance Admin** — full control including approval and
  destructive deletes.
- **Automation Service** — may create cases only in a quarantine folder (per the roles spec;
  not independently re-verified in this trace).
- **Viewer** — read-only.

## Capabilities

1. **View the suite tree.** `GET /projects/{id}/suites` returns a flat list with `parentId`;
   the client builds the tree. `[]` is the normal state of a freshly provisioned project —
   nothing is auto-seeded. `SuiteController.java:65-80` `#observed`.
2. **Create/rename a suite.** `POST/PATCH /suites` — `SuiteController.java:82-120`. Suite keys
   (`TS-n`) and folder paths are server-assigned; a rename never rewrites the path.
   `SuiteController.java:90-93` `#observed`.
3. **Change a suite's lifecycle status** (draft → in review → approved → deprecated) —
   `SuiteStatusService` (per orientation cache, §75).
4. **Search, filter and page test cases.** `GET /projects/{id}/cases` — rich filter set (suite,
   feature, type, level, owner, lifecycle status, automation status, priority, health signal,
   release, full text) with facet counts. `CaseController.java:91-130` `#observed`. An
   unparseable filter value is a 400, never a silently-dropped filter.
   `CaseController.java:46-52` `#observed`.
5. **View one case in full, including authored version history.** `GET /cases/{id}`,
   `GET /cases/{id}/versions`. `CaseController.java:132-187` `#observed`. `200 []` on the
   versions endpoint is normal for any case authored before this feature existed — not a
   missing-resource signal. `CaseController.java:166-176` `#observed`.
6. **Create a case, safely retriable.** `POST /cases` with an `Idempotency-Key` header — a
   retried submit (e.g. after a timeout) returns the *same* case (200) instead of creating a
   second one (201 only on the genuine first attempt). `CaseController.java:189-241` `#observed`.
7. **Edit a case with lost-update protection.** `PATCH /cases/{id}` requires the case's
   `lockVersion` via `If-Match`; a stale value is refused (409), not silently overwritten.
   `CaseController.java:243-298` `#observed`.
8. **Move a case through its lifecycle** (draft → in review → approved → deprecated), shared by
   suites and cases via one rule table — see ENT-001 below.
9. **Delete a case (soft).** `DELETE /cases/{id}` — Test Engineer only for **their own** cases;
   anyone with lead-level `case:delete` may delete any. Two different 403 messages depending on
   which is missing. `CaseController.java:343-385` `#observed`.
10. **Bulk-act on a selection of cases**, both preview and commit modes: move to suite, set
    lifecycle status, set automation status, set owner, tag, retag, condition, delete —
    `POST /cases/bulk`. `CaseController.java:300-341` `#observed`.
11. **Classify a case** against the project's vocabulary (Feature, Test Type, Test Level, etc.)
    — bounded to values the case's own detail view was actually served, so a client can never
    silently un-assign a retired value it was never shown. `#inferred` from orientation cache
    (§68), not independently re-read this session.
12. **Maintain the shared condition and shared-step libraries.** `GET/POST
    /projects/{id}/conditions`, `/shared-steps` — `ConditionController.java`,
    `SharedStepController.java`. Referencing a shared step from a case is validated against
    "live in this project, or already referenced by this case" so a retired entry doesn't 500 a
    bulk edit. `#inferred` from orientation cache (§64).
13. **View the project dashboard** — `DashboardController.java` (not independently traced this
    session; parked for a follow-up pass if needed).

## Business rules

- **A case or suite's lifecycle status follows one shared four-state ladder**
  (`DRAFT → IN_REVIEW → APPROVED`, plus `DEPRECATED` reachable from anywhere) — see ENT-001.
  `LifecycleLadder.java:1-245` `#observed`.
- **Submitting for review costs only edit rights; granting approval, retiring an artifact, or
  taking either back down costs approval rights** — so a contributor with everyday edit
  permission can never rescind a lead's sign-off by bulk-moving approved items back to draft.
  `LifecycleLadder.java:170-203` `#observed`. This closed a real defect: before this rule existed,
  a Test Engineer with ordinary edit rights could bulk-demote two hundred approved cases.
  `LifecycleLadder.java:34-46` `#observed`.
- **A retired (deprecated) case or suite can only come back via draft — never straight back into
  review or approval.** Business reason stated directly in the code: approval attaches to
  content a named person actually reviewed, and skipping straight back would launder an old
  sign-off onto unreviewed content. `LifecycleLadder.java:122-127` `#observed`.
- **A folder (suite) cannot be deleted while non-empty**, and **deleting a subtree is a
  lead-only action** (`suite:delete`), separate from the everyday ability to create/reorganize
  suites (`suite:manage`), so contributors can keep organizing freely without being able to
  destroy structure. `#inferred` from the roles spec, consistent with `LifecycleLadder.java:112-119`'s
  reasoning about dead ends.
- **Case deletion is `OWN`-qualified for Test Engineer**: full authorship-based control over
  their own cases in any state, but no ability to delete someone else's.
  `CaseController.java:349-359` `#observed`.
- **Creating and editing a case are two different permissions, and editing is *not*
  authorship-restricted** — any contributor can fix a wrong expected result in a colleague's
  case, since the whole point of the product is a *shared* repository. `#inferred`, matches
  `docs/specs/2026-07-30-tcms-roles-permissions-naming.md` A5 exactly and is consistent with
  `case:edit` never appearing OWN-qualified in the code paths traced.
- **Every project-scoped read on this module treats "no such project," "someone else's
  project," and "your company's project you're not staffed on" identically — 404, no body** —
  same rule as every other area. `CaseController.java:38-44`, `SuiteController.java:41-47`
  `#observed`.
- **A stale bulk request is not fatal for the whole batch** — a row that fails a rule is skipped
  with a per-row reason; the rest of the selection still applies. `LifecycleLadder.java:84-90`
  (`Decision.Refused`'s stated use in bulk) `#observed`.

## Failure behavior

| Situation | Response | Evidence |
|---|---|---|
| Case edit with a stale `If-Match` | 409 | `CaseController.java:257-263,294-295` `#observed` |
| Two saves interleaving past the `If-Match` check | 409 (an untested, acknowledged-rare race) | `CaseController.java:388-409` `#observed` |
| Case create retried with the same Idempotency-Key | 200 with the original case, no second create | `CaseController.java:192-199` `#observed` |
| Delete someone else's case as a Test Engineer | 403, "ask a test lead" message | `CaseController.java:379-382` `#observed` |
| Bulk action naming a status the caller can't reach (e.g., approve without the key) | 403 with a sentence naming the actual missing permission, not a generic "cannot edit" | `CaseController.java:300-322` `#observed` |
| Submitting an approved case for review again | 409, "return it to draft first" | `LifecycleLadder.java:141-151` `#observed` |
| Retiring a value/suite that still has children | 409 with a count | (Test Repository parallel of `ProjectController.java:312-329`; suite equivalent not independently re-read this session) `#inferred` |

## Boundaries / not built

- An author-can-retire-their-own-draft refinement (`OWN`-qualified deprecate) is explicitly
  named in the code as a natural next step and **not implemented**.
  `LifecycleLadder.java:188-191` `#observed`.
- The dashboard (`DashboardController`) was not traced in depth this session.

## Open questions for the business

1. Should a Test Engineer be able to retire (deprecate) their *own* draft case without lead
   involvement, the way they can already delete it? The code names this as a deliberate future
   step, not yet built. `#inferred`
2. Is the current one-size 404 for "case in another project" acceptable for the bulk endpoint
   too, or should a partially-visible selection surface differently to the caller? Not
   independently traced. `#inferred`

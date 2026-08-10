# §159 — the state nothing could enter, and the picker that was described as rendering it

**2026-08-10 · two phases · `backend/` + contract + `frontend/`: no migration, both `schema.d.ts`
copies regenerated, no `packages/` source.**

§156 found fourteen columns nothing writes and called five of them the finding. §157 and §158 took
the first two of those five. This is the **second** item on that slice's list rather than the third
of the five: **an archive operation for a project, or the decision not to have one** — `archived` is
a required contract field with no way to become true.

The decision is to have one.

## What the state was, precisely

`project.archived_at` has existed since `V1`. `Project.archive(Instant)` has existed, been correct,
and had no production caller for the same span. `ProjectView.archived` is derived from the column,
is **`required`** by `openapi.yaml`, and has therefore been promised to the SPA and to both
`packages/` clients as a field that is always present and always `false`.

That is §156's expensive half stated exactly: not a column nobody looks at, but one a consumer draws
a conclusion from. A reader of `GET /projects` had every reason to believe the answer meant
something.

**The one caller `archive` did have was a test fixture.** `ProjectEndpointTest.seedProject` takes an
`archived` flag and calls the mutator, so the suite has asserted since §34 that an archived project
is listed and marked — against a state only the test could produce. That is §153's rule (*a test
caller is a consumer for a read seam and not for a write one*) turning up on a class §153
structurally could not see, because §153's question was about repository methods.

## The finding is one sentence in a javadoc, and it had been true of nothing

`ProjectService.listVisible` explains why archived projects are in the list:

> Archived projects are included. The contract carries `archived` as a field rather than as a
> filter, **and the SPA's picker distinguishes them** — omitting them server-side would make an
> archived project unreachable rather than marked.

The argument is right and the clause in bold was false from §34 to §159. `ProjectsPage` never read
the field. Neither did any other file in `frontend/src` outside three test fixtures, nor anything in
`packages/`. So the prose described a rendering that existed nowhere, of a state that existed
nowhere — self-consistent, consistent with nothing else, and the fourth slice running where a
comment about a seam stood in for the seam.

Phase 2 makes it true rather than merely correcting it, which is the only reason the sentence
survives at all: the strike-through in that javadoc now records that **both halves were missing and
they were one defect, not two.**

## Three decisions, and the third is the one a later slice must not inherit by accident

**One endpoint, both directions, idempotent.** `PUT /projects/{projectId}/archive` with a body that
names the *target state*, not a verb. Two verb endpoints would make the client choose which to call
from a copy of the state it may have read minutes ago, and the request that loses that race does the
opposite of what the person pressed. Naming the state makes the stale case a no-op —
`SuiteStatusInput`'s argument over two values instead of four. Setting the state a project already
has answers 200 and writes **nothing**: no timestamp re-stamped, no audit row, no `lock_version`
bump. Two administrators agreeing is not a conflict, and an audit row per repeated click buries the
change that actually happened.

**`project:settings`, not the COMPANY-scope `project:create`.** Three reasons, and the third decided
it:

1. Archiving destroys nothing and is reversible by the same key, so it is not the counterweight to a
   create; it is the largest of the project's own settings.
2. Company Admin and Instance Admin hold `project:settings` on every project through their
   company-wide grant, so gating on the project key withholds nothing a company key would have
   granted.
3. **`ProjectService.createGranted` has no `Unreachable` arm** — correctly, because a create names no
   target. Gating this on a company key would mean answering 403 for a project id the caller cannot
   see, which is the oracle §6t exists to prevent, arriving through the gate rather than through the
   controller.

A property of the catalogue rather than of this slice, and worth writing down because nothing in the
code says it: roles spec **§A6a** withholds `project:settings` from both token-only roles, so **no CI
credential and no agent can retire a project.** No extra check was written for that, and none should
be — a second implementation of the ceiling is how the two come to disagree.

**Archiving marks a project; it does not lock it.** An archived project is still listed, still
readable, and every write in the contract still succeeds against it. That is `DocPageStatus.ARCHIVED`
one level up — the in-repo precedent for exactly this question, decided the same way for the same
reason: a state that takes something out of circulation while keeping it, and everything pointing at
it, resolvable.

The stronger form was considered and is **a separate decision, not a piece of this one.** The
enforcement point exists and is single — every project-scoped write goes through
`ProjectService.writeGranted` or one of its three siblings — so what a refusal costs is a fourth
`GateResult` arm handled at **53 call sites**, and `GateResult`'s own header names the failure mode
of doing that carelessly (*"a fourth outcome added later and handled in two of the three write
endpoints, with the third defaulting to whatever its `else` branch happened to be"*). More
importantly it is a product rule about what a retired project is *for*, and inheriting it as a side
effect of giving a column a writer would be deciding it without noticing.
`Archiving.archivingDoesNotLock` and browser row `AR-3` pin the current answer, so the day somebody
takes the other decision it is visible in a diff.

## The timestamp, kept as a timestamp

`archived_at` stays a timestamp rather than becoming the boolean the wire carries. "Retired on 3
March" is a fact somebody will want on a screen and in an export, and a boolean throws it away with
no migration path back that has anything to backfill from. `unarchive()` therefore **loses** the
date, and the alternative — a second column recording the un-archiving — was refused: that is a
history of one state kept in the row rather than in `audit_event`, where every other "who changed
what, when" in this product lives. `project.archived` and `project.unarchived` are written there with
the actor, so the sequence is recoverable from the place a person would look for it.

## The SPA half, and the two screens

The picker marks the project and **still lists it** — the server returns retired projects on
purpose, and a client-side filter would make one unreachable rather than marked, with the card the
only route to the screen that can restore it. The project's own page carries the chip and the
action: not a settings screen, because the two that exist are each about one subsystem (§6g's review
policy, the classification vocabulary) and this is about the project.

One button whose label is the move available from the state the project is in, never two of which
one is always a no-op — the docs tree's rule for an archived page, quoted in its own comment. **No
confirmation dialog**, deliberately: the action destroys nothing, hides nothing and is undone by the
button that replaces it, and a dialog here teaches people to click through dialogs on a screen that
also has real ones.

`useSetProjectArchived` writes the detail cache from the response and invalidates the list. Writing
a cache from a mutation response is normally the client guessing at `caseCount` and `myRole`, which
only the server computes — it is legitimate here because the response *is* the server's own
assembly of those fields, through the one assembler the `GET` uses, and
`Archiving.responseIsTheOneAssembler` pins the two against each other on the server side.

## What the frontend tests cost, and the thing worth remembering

**A `PermissionGate` verdict replaces the control's subtree.** The denied branch wraps the child in a
tooltip anchor; when the permission answer arrives the wrapper goes away and React builds a *new*
button node. An element captured with `findByTestId` before that moment is detached from the
document and stays disabled for ever — so a test that holds the node across the wait times out
blaming the permission, and the permission is fine. Re-query inside the wait. This cost most of the
phase's debugging and is written at the call site as well as here.

## Documents corrected rather than added to

- `Project.archivedAt`'s javadoc — the §156 argument, struck through and replaced with what the
  column now is, plus the timestamp-not-boolean decision.
- `ProjectService.listVisible`'s picker claim, above.
- `ArchitectureRulesTest.WRITERLESS_BY_DECISION` loses `Project.archivedAt`: **twelve to eleven**,
  the third member to leave by being *written* rather than deleted (§157, §158, §159) and the third
  time the rule itself did not move. **That map's javadoc had been stale by one since §158** — it
  still said thirteen while the method comment four lines below said twelve, in the file whose entire
  subject is tallies kept by hand. Both now say eleven, and the "five this rule sees alone" figure
  moves with it.
- The rule's own failure message used `ProjectView.archived` as its worked example of a read drawing
  a conclusion from a value nothing produces. It is past tense now, beside the health signals, and
  both examples are labelled as such rather than deleted — the message is the place a future reader
  learns what the rule is *for*.

## Gates

| Gate | State |
|---|---|
| `cd backend && ./gradlew build` | **SUCCESSFUL in 7m50s — 2438 tests across 504 result classes, 0 failures.** §159 adds **+9 tests, +1 result class**: `ProjectEndpointTest$Archiving`. Nested in the existing class rather than given its own file, because the fixture it needs is already there and is the hostile one — a Test Lead granted on exactly one project, a Test Engineer reaching another through a team, a member with no grant, a Company Admin with no project grant, and a project that was already archived. |
| `cd frontend && npm run build` / `lint` / `test:run` | **run — build green, oxlint 0 warnings, 1211 tests across 93 files** (+4 tests, +1 file). |
| `cd packages && npm run build` / `lint` / `test:run` | **run — green, 229 tests.** No `packages/` source is in this slice, but `client/src/schema.ts` is regenerated, so all three were run rather than declared unaffected. |

The seed fixture in `ProjectEndpointTest` grew a `restoreArchiveState()` and it is not tidiness:
§159 gave that class its first tests that **write**, and `IntegrationTestBase` does not clean between
tests while every helper there is written as "create if absent". Without the restore, a project one
test archives is still archived when the read-only tests two groups up assert it is not — green
alone, red in the suite, attributed to whatever slice was in flight. §19's failure mode, one
`@Nested` class later.

`AR-M1`…`AR-M6` are in the mutation queue and **none was run**, which is the policy the last two
slices between them broke four times. `AR-1`…`AR-6` are in browser lane **2 (`A0`)** — the cheapest
block added since §156: no scheduler tick, no review row, no second account except for the two
permission rows.

## What is next

§157's list, minus what this slice took:

1. **The three remaining health derivations** — `unlinked` and `outdated_spec_changed`
   (`documentation`, which `repository` may **not** depend on, so those two are a seam and not merely
   a statement), and `orphaned`, which needs a decision before it needs code.
2. **`IngestionJob.rawStorageKey`** — §67's port plus a read operation. Since §125 the port argument
   the column's javadoc rests on is **out of date**: `storage` exists, it is a foundation module,
   `execution` may depend on it, and `FilesystemBlobStore` is the body. What is left is a retention
   decision (a CI upload is unbounded and nothing prunes it) and one download endpoint.
3. **`TeamRef` ×2**, still — §147's named next tenant, and still not the same weight of evidence
   that moved `UserRef`.
4. **The read-only decision this slice deliberately did not take**: whether an archived project
   refuses writes. It is a fourth `GateResult` arm at 53 call sites and a product rule, and it now
   has a test and a browser row asserting the *current* answer, so taking it means changing them on
   purpose.

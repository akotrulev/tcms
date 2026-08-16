# §229 — the assignee nobody could give, and the correction the register could not make

**A4 + A6 of `docs/audits/2026-08-15-flow-review-and-improvements.md`**, whose §0 ledger is the
tracker for the authorised A1–A8 scope. The third slice of that run, after **§227** (A2 + A3 + A5,
reports) and **§228** (A1 + A7, the case's status and the suite's review surface).

**Seven of the eight are now done. `A8` is the remainder** and nothing about it was started here —
see *What is NOT done*.

Two phases, two modules, no migration, no permission moved, no new permission key.

---

## Phase 1 — A4: `test_execution.assignee_id` had one writer, and it fired once

### What was broken

The column has been rendered on the run detail and on the execution queue since M2. Its only writer
was `POST /runs`'s whole-checklist `assigneeId` — which sets **every** row of a new run to one
person — and `RunCreateDialog` did not offer that field either: it sent a hard-coded `null`, with a
comment explaining why. So in the shipped product every execution was permanently unassigned, and
the two screens that show an assignee could only ever print *unassigned*.

`configurationId` was the same shape beside it, and its comment named the blocker: the environment
list lived only on `TestPlanDetail`, so a run scoped by suites or tags (§99) rather than by a plan
had nowhere to read it from. `RunCloneDialog`'s header recorded the consequence in as many words —
*"there is no configuration picker anywhere in this SPA"*.

### What shipped

- **`PUT /projects/{projectId}/runs/{runId}/executions/{executionId}/assignee`** →
  `ExecutionAssignmentService`. `null` unassigns, so it is a toggle rather than a one-way door.
- **`GET /projects/{projectId}/configurations`** → `ConfigurationService`, gated on the project read
  like `/statuses` beside it. **Not in the audit's text**, and A4 is not deliverable without it.
- **An Assignment panel on `ExecutionPage`** (`ExecutionAssignee.tsx`), and **Configuration** +
  **Assign to** on *both* run dialogs.

### The decision worth carrying: which key, and why two obvious answers are wrong

Three keys were candidates and only one survives contact with the `Automation Service` credential:

| Key | Why not |
|---|---|
| `run:create` | It is what sets this column on the create path — and a CI credential holds it, because opening the run it is about to post results into is its job. |
| `result:submit` | About the verdict, which this write deliberately does not touch. The credential holds this one too. |
| **`run:execute`** | *"May this caller work a run by hand"* — the one key in the catalogue that separates a **person** from a **credential** rather than one role from another. Handing a case to a tester is part of working a run by hand. |

Either wrong answer compiles, passes every persona test in the file, and lets a leaked CI token
reorganise a team's workload. `Assignment.theRobotMayNotReassignWork` is the **only** observation in
the suite that can tell the three apart, which is why the mutation rows for it (`§229 PM-1`,
`PM-2`) expect exactly one red each and say that an extra red is itself a finding.

**No new permission was seeded.** The catalogue has taken none since `V3`, and a key granted to
nobody is a 403 no administrator can grant their way out of (roles spec §A6).

### Two seams read rather than re-spelled

- **`ExecutionPeople.isActiveMember`** is the membership predicate, because it is the one
  `RunCreateService` applies to the same column. `app_user` is instance-level (ERD §0.1's exception
  list), so an existence check accepts a user id belonging to **another client entirely** — the
  distinction `RunCreateService`'s own comment says cost it a red test.
- **`ExecutionService.detail`** became package-private so the write answers what the read after it
  answers (§34's `viewOfCreated` property). A second assembler would put the pinned steps, the
  partitioned evidence and the resolved people together a second way.

**A deliberate asymmetry, pinned so nobody "tightens" it:** the endpoint accepts any active company
member, while `GET /members` answers the narrower *holds a grant on this project*. So the picker is
a strict subset of what the server takes. Narrowing the server would make the two writers of one
column disagree about who may hold it — `aCompanyMemberWithoutAGrantIsAccepted` is that pin.

### Why a separate service, and why no `If-Match`

`ExecutionService`'s whole shape is the three-permission verdict story: which key applies is a
property of the row, so the row is read before the permission is known, and its `Outcome` carries
`amending` and `NotExecuting` because those two refusals need opposite advice. None of that is true
here — one key, known in advance, and a refusal meaning *ask a lead* rather than *use the other
door*. Folding it in would have widened a sealed interface whose arms are load-bearing.

**No `If-Match`.** The verdict PATCH takes one because two testers on one row is the ordinary case
and a lost verdict is unrecoverable. Requiring it here would refuse a coordinator's reassignment
because somebody recorded a result in between — a conflict about a field neither call touches. The
save still bumps `lockVersion`, and the response is written into the execution's cache so the
runner's next save does not 409 against this write.

**A closed run refuses with 409, and that is a judgement rather than an inheritance.** Assignment is
scheduling, not a record of what happened, so it could defensibly stay open; it does not, because an
assignment written into a finished checklist is a task nobody can act on appearing on somebody's
queue. The sentence is its own — `RunLifecycle.CLOSED_MESSAGE` says *"results and evidence"*, which
is true and about neither thing this caller was doing.

---

## Phase 2 — A6: the register could register and archive, and could not correct

### What was broken

`artifact:edit` has been seeded since M0 with two consumers, and both of them *register* or
*circulate* a row. Nothing could change what one **says**. So a typo in a title could be answered
only by superseding — which asserts on the record that a newer *document* replaced it — or by
deleting and re-registering, which loses the author and the date. Both say something untrue about
the project in order to fix a spelling.

`DELETE …/artifacts/{artifactId}` shipped in §126 with both its 409 guards and **had no caller at
all**; `ArtifactTable.tsx` said so itself.

### What shipped

**`PATCH /projects/{projectId}/artifacts/{artifactId}`** over `title`, `category` and
`description`, plus **Correct…** and **Remove…** on the register's row menu.

### Three fields, and the absences are the design

Every other mapped column is `updatable = false`, each with its own recorded reason: `scope` and
`planId` decide which permission verdict the row is under, and `kind` with the three payload columns
are the target — plan §6n's answer to *"this points at the wrong thing"* is to **supersede**, which
produces a second row naming the first, rather than to swap the target under a `created_at`, an
author and an audit trail that go on describing what was replaced.

So the dialog offers none of them, and says so in a banner rather than leaving four missing controls
to imply it. `theUneditableColumnsAreUntouched` drives a body carrying all of them and asserts the
row does not move — a property of the **mapping**, which no line of the service shows.

### §68 applied rather than extended

Jackson gives an absent field and an explicit `null` the same Java value, so no operation in this API
can tell them apart. The settled convention is *scalar null means clear*. That is honoured, and the
two `NOT NULL` columns are therefore **required**: a caller omitting `title` under this convention is
asking to clear a column that cannot be cleared, and a field error is more honest than inventing a
third reading of a `PATCH` body in a product that already has two.

**The audit's B1 — unify the case `PATCH`'s absent-clears with something safer — is deliberately not
pre-empted here.** It is a decision over the whole contract. The first draft of this operation
claimed plan-style *absent-leaves-alone* semantics and was wrong about the codebase: `PlanUpdateRequest`'s
own header states the same scalar-null-clears rule.

### Two refusals, and only one of them exists

- **`write_once` is honoured** — a sign-off record's stated meaning is that corrections supersede and
  never overwrite, which is a rule about editing at least as much as about deleting. Nothing sets the
  column, so the test sets it in SQL; enforcement that arrives *after* the control it protects is the
  `Company.suspend()` shape.
- **A `SUPERSEDED` row is editable, and that is the decision.** `setStatus` refuses it because a
  status is a fact about *another* row. A title is not. Copying that refusal would read as
  consistency and would make a typo in what the register says was replaced permanently
  uncorrectable — the same dead end, one row further back. Permanence is `write_once`'s job.

That decision is what forced the UI change worth knowing about: **the row kebab no longer shuts on a
superseded row.** It used to disable the whole button, which was right while the menu was Archive and
Restore; with Correct… in it, a shut kebab would have put the register's one edit path out of reach
on every row anybody had ever replaced. The two status items stay greyed, and *why* is the sentence
already on the status chip beside the title — which is where a reader looks, and where a tooltip
actually fires (MUI raises no hover events on a disabled `MenuItem`).

### The delete gets the dialog the archive was refused

`ArtifactRowActions` recorded, when archiving shipped, that a confirmation belongs on removal and not
on archiving: archiving destroys nothing and the way back is the item beneath the one just pressed.
`DeleteArtifactDialog` is that dialog. It names what a `FILE`'s removal costs — the bytes go with the
row, the register's only act that destroys something — and points at **Archive** for the case
somebody actually meant. Both 409s render *inside* it, because the client can predict neither.

The two permissions are asked **differently**, and the difference is the schema's: `artifact:edit` is
qualifiable (`V19`), so the first three items ask `canOnScope` and a client asking the plain key
would fail **open** the day a migration qualifies it again. `artifact:delete` is unqualified for
everyone who holds it, so Remove asks `can` — asking it by scope would invent a narrowing the server
does not have.

---

## Two things the contract rules caught, both on first drafts

Worth knowing before adding any endpoint to this repo, because neither is discoverable from the code
you are writing:

- **`ContractConventionTest.everyObjectSchemaIsPairedOrExempt`** fails until a new input schema is
  listed against the record the handler binds. Both `ExecutionAssigneeInput` and `ArtifactEditInput`
  hit it.
- **`ErrorBodyPolicyTest.bodilessForbiddenSitesArePinned`** refuses a new `.build()` on a 403. The
  fix is a **body**, not a row in the list — the three sites on that list are there because there is
  nothing to say (a binary download cannot carry a `Problem`; two artifact refusals have no field to
  land a sentence on). Phase 1's assignment 403 met it live.

---

## Gates

Phase 1, all on `shanks`, and all green:

| Stack | Result |
|---|---|
| `backend` `./gradlew build` | **BUILD SUCCESSFUL** — 2905 / 0 / 0 (+14) |
| `frontend` build / lint / `test:run` | clean, 0 warnings, **1464 tests / 109 files** (+30) |
| `packages` build / lint / tests | clean, **78 / 128 / 123** |

Phase 1 is committed and pushed (`067abc4`).

**Phase 2's gates are INCOMPLETE, and this is the one thing to read before continuing.** What was
run:

| Stack | Result |
|---|---|
| `backend` `ArtifactEndpointTest` + `ArtifactCirculationTest` on shanks | **BUILD SUCCESSFUL** (+12 in `Correcting`) |
| `frontend` `src/features/artifacts` | **27 passed** (+5) |
| `frontend` `npm run build` / `npm run lint` | clean, 0 warnings |

What was **not** run, and must be before phase 2 is pushed:

- **The full `./gradlew build`.** Two attempts failed for an environment reason, not a code one:
  another Claude session was running `./gradlew test --tests com.tcms.auth.SocialControllerTest`
  **in the same `~/builds/tcms` tree**, and its writes to `build/test-results` collided with mine —
  *"Could not write XML test results"* × 500, plus two unattributable failures. Confirmed by
  `pgrep -af gradle-wrapper.jar` on shanks. **This is the two-writers-one-tree hazard the parallel-lane
  rules describe, arriving on the shared *build* tree rather than on a checkout**, and
  `~/bin/tcms-remote`'s `rsync --delete` makes it worse in the other direction. Re-run when the tree
  is quiet.
- **The full frontend suite and the `packages` suites** after phase 2. `packages/client/src/schema.ts`
  was regenerated, so `schemaFreshness` and the §203 exchange harness both have new input.

---

## What is NOT done

- **A8 — cross-navigation between the two link-creation surfaces.** The one item of the authorised
  A1–A8 scope still open; the audit's row is unchanged and remains its specification.
- **Phase 2's full gates**, above. Phase 2 is committed locally and **not pushed** for that reason.
- **C10 — MCP artifact tools.** It named A6 as its prerequisite, and that prerequisite now exists.
  The register still has no MCP surface at all.
- **Queue rows written and not run** (§65): `§229 PM-1…PM-13` (A4) and `§229 PM-14…PM-22` (A6) in
  `docs/verification/backend-mutation-checks.md`; `§229 AS-1…AS-13` (A4, lane **A3**) and
  `§229 AR-1…AR-8` (A6, lane **A5**) in `docs/verification/frontend-browser-checks.md`.
  **Three of those predict zero reds and say so** — `PM-13` (the moved `configurationsOf`, which is
  E1 and moves nothing observable today), `PM-20` (the `updatable = false` mapping, where zero is the
  *good* answer) and the `write_once` browser row, which has no live observer at all because nothing
  in the product sets that column.
- **`§229 AR-7` is not driveable with the seeded six** — it needs a caller holding `artifact:edit`
  and not `artifact:delete`, which no seeded role is. Named rather than skipped.

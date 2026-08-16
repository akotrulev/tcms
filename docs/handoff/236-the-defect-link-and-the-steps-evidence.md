# §236 — the defect link and the step's evidence (C5 + C9)

**Date:** 2026-08-16 · **Branch:** `master` · **Slice 4 of the C run**, in the §3.6 order.

Two audit items that share a screen and nothing else. **C5** gave `defect_link` its first writer in
the product's history — `V62`, an entity, a service, two endpoints. **C9** wired the runner's step
rows to an upload path that had been complete on both ends since §72 and had never once been
called with a `stepId`.

One migration (`V62`), no permission seeded, none moved.

---

## 1. C9 first, because it is the shorter story and it corrects the shape I proposed

The audit called C9 *frontend-only*, and it is. **I proposed adding a contract field before
establishing that, and was wrong** — recorded here rather than quietly dropped, because the wrong
version is the one a later reader would re-propose.

The reasoning was: a file attached to a step would land in `TestExecution.evidence` beside the
execution-level ones with nothing to tell them apart, so the write would be unreadable — so
`Evidence` needs a `stepId`. Every step of that is false, and the file that says so was two
directories away:

- `EvidenceViews.partition` (§72) already splits rows into `onExecution` (`step_result_id IS NULL`)
  and `byStepResult`.
- `ExecutionViews.steps` already hangs each step's list off its `StepResultView`.
- `openapi.yaml`'s `StepResult` has carried a **required** `evidence` array the whole time.

So step evidence was never in the flat list; it was served *inside its step*, which is better than
the discriminator I was proposing. **The lesson is the cheap one and it keeps recurring: the gap
was in the SPA, and I went looking for it in the contract because the SPA's absence was easier to
see than the server's presence.** `ExecutionPage.tsx` mentions `evidence` exactly once, at the
`EvidencePanel` mount — which reads as "evidence is handled" rather than as "one of the two evidence
surfaces is missing".

### What shipped

`StepEvidence.tsx`, one strip under each step row: the files already on that step, and an **Attach
to this step** control that passes `stepId`.

**The affordance is gated on the SERVER's verdict, not the local mark, and that is the whole design
decision.** `EvidenceService` resolves `stepId` against `step_outcome` and answers a 400 naming the
field when there is no row — *"Record a result for this step before attaching evidence to it."*
Inventing a `step_result` there would record a verdict nobody gave, so the refusal is right. But the
marks on the runner are **local state until Submit**, so an affordance keyed on what the tester just
clicked would 400 every time. It is keyed on `step.statusId` — what the server served — and says the
server's own sentence while disabled.

That is a case of *disable-don't-hide over a rule the client cannot enforce and must not
contradict*. The sentence is duplicated in the SPA on purpose; the alternative is letting the user
find out by failing.

---

## 2. C5 — four decisions, and the schema was lying about two of them

`defect_link` has been in `V1` since M0, is read by three screens, and had no writer and no entity.
`defect:create` was seeded by `V3` in the same milestone and had no consumer. The audit's decision
was *ship the minimal external-link write*; the four things that had to be decided rather than
defaulted are below, because none of them is readable off the schema.

### (a) Which end does a link attach to — the readers answered it

Both `execution_id` and `case_id` are nullable, so this was a real choice. Every reader is
execution-scoped: `TestExecutionRepository.findDefectLinks` is `WHERE d.execution_id IN (…)`, and
`ExecutionViews`, `CaseHistoryService` and `PlanStatisticsService` all group by execution.
**Nothing reads `case_id` at all.**

So the write is execution-scoped and **`case_id` stays NULL**, with the reason in `V62`'s header: a
case-scoped link is a different product — *this defect concerns this test case in general* rather
than *this run of it* — with no screen, no reader and no permission argument of its own. It is
derivable through `test_execution` if it is ever owed.

### (b) Entity or native — both, and the split is the point

The read stays native. Its javadoc said it was native **because there was no writer**, which C5
falsifies; that sentence is struck through in place with the reversal (the §152 shape, and the
fourth slice in a row to hit it). What replaced it is a reason that survives: the read is a batch
projection over N executions, so an entity read is a fetch through a previously unmapped edge or one
query per row — and the mapping deliberately **omits `status_cache`**, which the read serves.

The write is an entity, and mapping the table buys `ddl-auto: validate` over `defect_link` for the
first time.

`findDefectLinks` now spells `company_id` out beside RLS (§235's rule, which this cache's header
carries), which is also what makes the new index's leading column usable. Two call sites, both
`CompanyContext.require()`.

### (c) `provider` — the default was a claim, and dropping it was not enough

`provider TEXT NOT NULL DEFAULT 'JIRA'` names a product the instance does not talk to. `V62` drops
the default. **But the second half is the one worth reading:** `DefectLinkInput` has no `provider`
field either, so a caller cannot supply one.

A caller-supplied `JIRA` is *worse* than the default was — it reads as somebody's deliberate
statement rather than as a column nobody filled in, and pre-M4 there is nothing to check it against.
The server writes `EXTERNAL`, meaning *a link a person made*, which is the only thing this writer can
say truthfully. M4's adapter will state a real provider because it will have spoken to one.

### (d) `status_cache` and `synced_at` — writerless, and **not** §234's defect in reverse

The question C7 forces: is a column written by nobody and *read as though it meant something* the
same defect as a column enforced for three slices and settable by nobody?

**For these two it is not, and the difference is what NULL says.** `status_cache` is declared
nullable and documented as *"the tracker's status as of the last sync"*; a row never synced has no
such status, so **NULL is a true answer**. Two of three readers render it conditionally and show
nothing. That is the opposite of `DEFAULT 'JIRA'`, which asserted a fact nobody established.

So they keep their columns, gain no writer, and — the part that needed deciding — **no caller may
set them**. A client-supplied status would be one person's guess recorded as a tracker's answer,
which is the exact claim `V62` exists to stop making. Asserted by
`DefectLinks.ignoresFieldsTheInputDoesNotDeclare`, which sends both and reads the row back.

### Two things the schema was lying about, found on the way

- **`DefectLink.url` has been a required property since M0 while the column was nullable.** Nothing
  had violated it for the only reason that matters: no writer, no rows. `V62` makes it `NOT NULL`,
  and the table being empty is *provable from the code* rather than measured — there is no INSERT
  anywhere, and this is the commit that adds the first.
- **The table had no index at all beyond its primary key.** Free while empty, a sequential scan per
  run-detail render once not. One partial unique index on `(company_id, execution_id, provider,
  external_key)` serves the read (its prefix is the lookup) *and* enforces the replay, so two
  concurrent retries of one link collide on the constraint rather than both writing.

### The delete, and why it is charged `defect:create`

There is no `defect:delete`, and the catalogue has seeded no key since `V3`. The alternatives were
to charge it to something that does not describe it, or to ship a create with no correction path —
which leaves a mistyped key rendering as a broken `href` on three screens forever. So the key that
makes the link unmakes it, stated at the site rather than inferred, with **no authorship gate**:
nothing has carried a qualifier since `V51`, and inventing a row-scoped rule the catalogue does not
express would be this service granting itself something.

A closed run freezes both writes, mirroring `EvidenceService` — a link is part of the record of what
happened, and making it looser than the evidence beside it is the incoherence §67 wrote down.

### The URL is a security bound, not tidiness

Three screens render `defect.url` as an anchor's `href`, and React does not sanitise one. `http` and
`https` only; `javascript:` executes in the reader's session and `data:` renders attacker-authored
HTML on this origin. Re-stated from `documentation.ExternalLinkService` rather than shared, because
`execution` may not depend on `documentation` (plan §3). `DefectLinks.refusesASchemeThatExecutes`
drives it and asserts nothing was stored.

---

## 3. One stale claim corrected that this slice did not cause

`Evidence.createdBy`'s contract description still taught `evidence:delete`'s **`OWN` qualifier**,
emptied by `V51` — while `DELETE …/evidence/{evidenceId}`, forty lines away in the same file,
already said so. Corrected in place with the reversal, and the field's real justification put back:
it is load-bearing for the *screen* (which must be able to say "nobody recorded" rather than invent
an author), not for a permission any more.

**Two more of the same family are recorded and NOT fixed**, because this slice does not touch those
files and the sweep belongs to the rule rather than to me: `Evidence.java`'s class javadoc
(*"`evidence:delete` is `OWN`-qualified for Test Engineer"*) and `EvidenceService`'s header, which
says it twice. `Evidence.java` also carries a whole `<h2>` explaining that the run-closed invariant
is **not** enforced — §72 enforced it, and `EvidenceService.attach` refuses on `run.isClosed()` at
the top. That one is a section, not a sentence, and it is the more misleading of the three.

They are live for the same reason §233's rule missed them: `tierClaims.test.ts` matches
`Lead-only` / `does not hold` / `not held by` / `withheld from` and the qualifier forms, and *"is
`OWN`-qualified for"* on a key the spec no longer qualifies is a shape it does not have. **That is
the instrument gap, and it is the finding rather than the three sites.**

---

## 4. Gates

| Stack | Result |
|---|---|
| `backend` `./gradlew build` (shanks) | 3038 / 0 skipped / 0 failures — **+12**, all in `ExecutionEndpointTest$DefectLinks` |
| `frontend` `build` + `lint` + `test:run` (shanks) | **1547 tests / 115 files** — **+18** on a 1529 baseline; the two new files carry 14 `it` blocks and the other four are the per-file sweeps (`renderFailures`, `contractDoubles`) that grow with the corpus. Lint 0 warnings |
| `packages` `build` + `lint` + `test:run` (shanks) | **336** (78 cli / 130 client / 128 mcp), unchanged; both `schema` copies regenerated and byte-identical |

**Arrival was gated before anything was touched**, per the standing instruction: the frontend suite
was green on master at 1529/113 and the backend tree was `UP-TO-DATE` against §235's 3026/0/0. Both
counts in `CLAUDE.md` were stale and are corrected in this commit.

**No browser pass and no mutation run** (§65). Twelve browser rows (`§236 DK-1`–`DK-7`,
`SE-1`–`SE-5`, lane `A3`) and eight mutation rows (`§236 DK-1`–`DK-7`, `SE-1`).

**Two of the mutation rows are expected GREEN and are recorded as gaps rather than decisions** —
`DK-5` (nothing drives a link against a closed run, so the gate ordering copied from
`EvidenceService` has no observer) and `SE-1` (nothing asserts the step refusal, only the success
path; a mutation making it fall back to an execution-level attach would pass, and the tester's file
would silently land under no step). Those two are the cheapest things to close next.

---

## 5. What was deliberately not built

- **No `GET` on either new controller**, and the class header says it must not grow one: a result's
  defect links are already served on the result as `TestExecution.defects`. A second answer to an
  answered question disagrees the first time one changes.
- **No case-scoped defect link** — §2(a).
- **No `Location` header on the 201**, because there is no `GET` to point it at (§119's warning).
- **No `Idempotency-Key`.** The unique index makes the create idempotent on its natural key, which
  is stronger than a header a caller may omit.
- **No MCP surface.** `defect:create` is an `AGENT_KEYS` key, so a tool is legal — but the register
  slice (§234) is the precedent for that being its own decision, and this one was not taken.

# §64 — The shared-step library, and the reference nobody checked

**Date:** 2026-08-02. Backend + frontend + contract, one slice. **No migration — `V20` is still the
next free number.** M1.5's other half (conditions shipped in §26).

---

## What this closes

`useSharedSteps` had **zero call sites**, and `GET /projects/{id}/shared-steps` had **no server**.
Dead on both sides at once, which is exactly why neither side noticed — §61's orientation pass
flagged it as one of two dead ends to *"delete or build"*, alongside `/reports`.

`/reports` was checked and **not** built: behind it is **M2.5**, a 7–8 week milestone (`ExecutionFact`
read model, `ReportModel`, Thymeleaf + headless-Chromium renderers, an async job pipeline). It is a
milestone, not a slice, and it remains the last MSW-only surface.

The step library was the smaller and more urgent one, because it was not merely dead:

> **`TestStepInput.sharedStepId` has been accepted and PERSISTED UNCHECKED since M1.**

`CaseEdit.validate` checks `suiteId` against the project's live suites and step `id` against the
case's existing ids, each with a paragraph in its javadoc explaining the failure it prevents. The
identical argument was never made for the column beside them. `test_step.shared_step_id` carries a
foreign key (`V1:604`), so:

- an id **naming nothing** fails the constraint → **500 where a field error belongs**;
- an id naming a **sibling project's** entry **satisfies the key and is stored**, after which every
  read of that case resolves a library entry through a project the reader holds no grant on — the
  leak `ConditionService`'s own javadoc argues against for the neighbouring library.

The writer shipped without the library it references, so nothing could ever validate against it.
That is the general shape worth keeping: **a field whose validator needs a table that does not exist
yet gets no validator, and nothing later goes back for it.**

`library:manage` gets its **first consumer in Java**. Seeded by `V3`, granted to Test Engineer and
up since M0, never once asked — §62's `case:approve` shape, a second time in three slices.

---

## Decision 1 — `usageCount` is derived, and that is the slice's real content

`shared_step.usage_count` exists, is `NOT NULL DEFAULT 0`, and **nothing maintains it**. This is the
state `Condition.usageCount` documents at length and ships anyway, with `ConditionRepository`'s
`ORDER BY c.usageCount DESC` sitting on top of a column that reads 0 on every row — so the condition
library's "most-used first" has **never once ranked anything**. Three options, and the middle one is
the trap:

| | |
|---|---|
| **Mirror conditions** | A second permanently-zero column in a second `ORDER BY`. The picker's whole value is that the block four hundred cases already share sorts to the top; ranking by a constant makes that alphabetical and nothing says so. |
| **Denormalize with a writer** | Every path that rewrites a case's step list must recompute it — and a case edit **replaces the list wholesale** rather than diffing it. The counter drifts on the first path that forgets, and no read reveals it. |
| **Derive it** ✅ | Correct by construction, no writer to forget. Bounded to one project's library. |

So the query counts, and **the column is not mapped on the entity at all** — mapping it and ignoring
it would be worse than leaving it off, per `Condition`'s own rule that a field mapped before its
reader is a field nobody notices has gone stale.

Two properties of the count that are easy to get wrong and invisible when wrong:

- **Distinct live *cases*, not step rows.** A case referencing an entry twice would inflate it, and
  the inflation reads as popularity — §6e's rule about conflating an execution count with a
  distinct-case count, one layer down.
- **`c.deletedAt IS NULL`.** Cases are soft-deleted because executions reference them, so without it
  a number that can **only ever go up**: the library would rank by everything ever written rather
  than by what is in use.

**Conditions keep serving the stored 0, and the asymmetry is written down** rather than left looking
like an inconsistency: a condition's usage spans `case_condition` **and** `suite_condition`, and the
suite side propagates by inheritance, so that count is a design question rather than a join.

### The mutation guard is in the fixture, not the assertion

`SharedStepEndpointTest` seeds `usage_count` to values that **contradict** the real references — 777
on an entry three cases use, 5 on one nothing uses. With the column seeded to the derived answer,
reading it and deriving it would **both pass** and the test would prove nothing about which one
shipped. Verified: dropping `DISTINCT` and the `deleted_at` predicate reddens exactly three tests
and nothing else.

---

## Decision 2 — the reference rule is a UNION, and the second half is the subtle one

> A step may reference a shared step that is **live in this project**, **or** that **this case's
> stored steps already reference**.

The first half is the check that was missing. The second half is why the check cannot simply be
"live in this project":

**Retiring a library entry is a soft delete precisely BECAUSE references to it survive** — the same
rule `Condition` states for attachments. Without the stored half, the day an entry is retired
**every case using it becomes unsaveable**: a title fix on an untouched step is refused over a
reference the author never made and cannot see. That is strictly worse than the defect being closed,
and it is only avoided by resolving the union.

**`CaseEdit.of` passes the stored ids alone and no library at all.** Reconstruction is not an
authoring act, so there is nothing there for a library check to protect — and without that carve-out
a bulk `MOVE_TO_SUITE` over a case using a retired entry would fail to reconstruct and be skipped as
*"cannot validate its own content"*: a case the caller can see, refused for a reason it did not
cause, by a check added for a different path entirely.

The create path unions in nothing, deliberately: a new case references nothing yet, so a retired
entry is correctly refused there while an existing case keeps saving with one. **The concession is
to references already made, not to making new ones** — with one documented consequence, since the
rule is expressed as a set and cannot tell a new step from an old one carrying the same id.

---

## The live pass found the defect no gate could

Driven in a real browser in **LIVE mode** against a rebuilt container (§63's trap: the running image
predated the change, so a stale container answers with the old rules).

The refusal was correct — **400 with a field error, not the 500 the foreign key would have raised**
— and it carried **two** messages on `steps`:

```
"Step 1 refers to a shared step that is not in this project's library."
"Add at least one step, or switch to Gherkin."
```

The second tells the author to add a step they demonstrably just added, directly under the sentence
explaining why the one they added was refused. **Two messages on one field arguing with each other,
and the wrong one is the one that looks actionable.**

The cause: `normalizeSteps` **drops** a step it has already complained about, so the
structured-format emptiness check was reading *"nothing survived validation"* as *"nothing was
sent"*. It now asks the request rather than the result.

**It predates this slice and is swept rather than spot-fixed.** A blank action and an unknown step
id are dropped the same way, so the same contradictory pair was already reachable through two older
refusals. Deleting the check instead would have been wrong — a structured case that really sends no
steps has to be refused and has no per-step message to carry it, so that half is pinned by its own
test.

### What the live pass proved

- An empty project's picker reads **"The library is empty"**, not "no results" — the two empties said
  differently (§47/§49/§63's copy class).
- A block written through the dialog stored **exactly three body lines** from input carrying a blank
  line and a trailing newline.
- Reopening the picker from a *different* case read **"1 case"** while `psql` shows
  `usage_count = 0` on that row. **The derivation, against ground truth.**
- A **sibling project's real entry** — which satisfies the foreign key and would have been stored
  before this slice — refused 400 with one message; an id naming nothing likewise.
- `psql`: exactly one shared-step reference in the company, pointing at an entry in its own project.
- **0 client errors.**

---

## Frontend

`SharedStepPicker` — search, insert, or write a new block inline. **Inserting sets `action` to the
entry's TITLE, not its body**: a shared block is a *reference*, resolved when the case is read, so
copying the body in produces a step that looks identical on screen and has quietly stopped tracking
the entry it came from.

The create form is gated on `library:manage`; **the picker is not.** Reading the library needs
nothing beyond the project grant the page already required, so gating the dialog would withhold from
a Viewer a list they may read — §31's disable-don't-hide applied to the one control the server would
actually refuse.

**Two mock defects, both "the mock agrees with itself":**

- The fixture carried `usageCount` 48/33/91 while **no fixture case referenced any entry**, so mock
  mode showed a confident ranking live mode would have answered 0 for. The handler now derives the
  count the way the server does — through `caseDetail`, because a summary row carries no steps and
  counting over summaries answers 0 forever — and the fixture values are **0**, so a handler that
  forgot to derive renders an unranked picker rather than a plausible fake.
- `GET /shared-steps` did not `requireAuth`, alone among the handlers.

A fixture case now references an entry, which makes the detail page's **"Shared" chip reachable in
mock mode at all** — it has rendered since Phase 1 against fixtures where every `sharedStepId` was
null, so nothing had ever drawn it.

**A test-harness trap worth keeping:** `PermissionGate` renders the denied branch wrapped in a
tooltip anchor and the allowed branch bare, so the gated button is a **different DOM node** either
side of the verdict. A held reference never becomes enabled and the wait times out against a node
that is no longer in the document — re-query inside `waitFor`. And because the gate is fail-closed
while `/me/permissions` is in flight, **"disabled" alone is also what pending looks like**: only the
paired enabling test makes the disabling one mean anything.

---

## Gates

- `backend` — `./gradlew build` clean, **1199 tests, 0 failures, 0 skipped** (+43). Mutation-verified
  twice. Fixture prefix **`ab`**, checked free first — §19's collision has now happened five times.
- `frontend` — `npm run build` clean, `npm run lint` 0 warnings, **`npm run test:run` 461/461** (+9:
  7 component tests, and 2 the contract checks picked up from the new operation and the new
  `api.post` call site). Mutation-verified.

## Left for later, deliberately

- **Edit and delete of a library entry.** The roles spec's *"gated by an impact modal, not by
  permission"* is about editing — the change propagates to every case referencing it — and the
  impact modal needs the affected-case list to exist first. Creating propagates nowhere, which is
  why the permission is the whole control on this path.
- **`/reports` is now the only dead contract left**, and it is M2.5's, not a slice's.
- **`ConditionRepository`'s `ORDER BY usageCount` still ranks by a constant.** Named here rather
  than fixed: the condition count spans two tables with inheritance on one side, so it is a design
  question and deserves its own slice rather than being smuggled into this one.
- The picker's search has **no debounce** — it refetches per keystroke against a `typeahead` budget.
  Fine at library scale, and the same shape the condition picker already has.

# §152 — the seams nobody calls, and the javadoc that was revised into being wrong

**Date:** 2026-08-09 · **Branch:** `master` · **Scope:** `backend/` only — **no migration, no
contract change, no frontend, no `packages/`.**

A re-derivation, in the sense §28/§29/§46 mean it: a mechanical question asked of the code rather
than a step taken off a list. Driven by the `be-java:be-java` plugin skill.

---

## 1. The question

§121 deleted `CaseFlakeRepository.findQuarantined` rather than wiring it: a seam written ahead of
its consumer had guessed the module boundary wrong, and the generator its javadoc named could never
legally have called it. §116, §117, §132 and §151 each found the same shape from the other end — a
column with no writer, a column with no reader. **Five findings, five slices, every one found by
hand, and every one found because the slice happened to be about the thing next to it.**

So: *which repository methods have no caller?* One question, over the one population where it can be
answered mechanically.

## 2. THE FINDING — ten, and the grep that found the wrong ten

The rule is `ArchitectureRulesTest.everyDeclaredRepositoryMethodHasACaller`. Written **before** the
deletions and run red, so the population is measured rather than asserted:

| | |
|---|---|
| `AppUserRepository.existsByEmail` | the citext-safe existence check |
| `CompanyRepository.existsBySlug` | |
| `DocBlockRepository.findForPages` | **javadoc: "this has exactly one caller"** |
| `EvidenceRepository.findByExecutionIdIn` | **javadoc: "for the run detail"** |
| `PermissionRepository.findByAreaOrderByKeyAsc` | left the interface declaring nothing |
| `RoleAssignmentRepository.findByProjectId` | |
| `RoleRepository.findByScope` | |
| `SuiteRepository.countLiveCasesBySuite` (+ its `SuiteCaseCount` projection) | **§4 below** |
| `TeamRepository.findByName` | |
| `TeamRepository.findByParentTeamId` | |

**The hand-grep that opened this slice got two of the ten wrong, in both directions**, which is the
§147 lesson arriving as a demonstration rather than a lesson:

- It **missed** `TeamRepository.findByName`, because `RoleRepository.findByName` *is* called and a
  search for `.findByName(` cannot tell two interfaces apart.
- It **wrongly flagged** `TestCaseRepository.existsByAutomationKey`, whose caller is
  `cases::existsByAutomationKey` — a method *reference*, which no `.name(` pattern matches.

The rule reads accesses **and** references for exactly that reason, which is also what keeps the
Spring Data projection accessors (read through
`Comparator.comparingLong(ProjectRoleName::getPermissionCount)`) out of the report.

## 3. Why the rule is narrow, and why it counts test callers

**Narrow**, per §147's precedent: the broad version — "no uncalled public method anywhere" — was
measured first, and it reports every controller handler in the codebase, because Spring calls those
reflectively and bytecode cannot see it. It would ship with an ignore list longer than the rule.
Spring Data repositories are the one population where every legitimate caller is a compile-time one.

**Over `PRODUCTION_AND_TESTS`, deliberately.** Three methods this would otherwise have condemned are
read only by tests asserting what a write actually left in the database —
`AuditEventRepository.findByActionOrderByOccurredAtDesc` most clearly, since the audit trail has no
screen yet and the test *is* the only reader there is. A seam whose consumer is a test is a seam with
a consumer. A seam with no consumer at all has never once been executed, and that is the state the
rule names.

## 4. The dangerous one — a javadoc *revised* into being wrong

Nine of the ten are dead weight. `SuiteRepository.countLiveCasesBySuite` is not.

§98 made a suite **flat** — membership is the union of three arms (filed ∪ added ∪ tagged) — and
replaced this query with `SuiteMembership.countsBySuite`, which computes that union. It did not
delete the old one. It **rewrote its header in the same slice** to say:

> *"This one feeds the internal reads that mean **filing** specifically."*

There are no such reads and there had never been any. The query still runs, still returns a
plausible number per suite, and the number is **the filed arm alone** — which is precisely the count
§98 removed from the wire, after a suite defined by a tag rule showed a count of twelve beside a grid
of none (`CaseScope`'s header records that defect). The next author needing a per-suite count finds
this method, believes the header, and ships §98's defect back.

**A callerless method is not neutral. Its javadoc is evidence nothing has ever checked**, and the
one place a stale seam gets *more* convincing is the slice that supersedes it, because that is when
somebody last had a reason to edit its comment.

## 5. The second half — three claims with the file that disproves them sitting beside them

`TestExecutionRepository.countEvidence`'s header read:

> *"`evidence` has no entity because nothing in the product uploads one — `evidence:attach` has no
> endpoint — and an entity would be a mapped write surface for a table with no writer."*

`Evidence.java` is the entity. `EvidenceController.java` is the endpoint. `EvidenceService.java`
enforces `evidence:attach` and names it in a constant. **Seven files whose names begin `Evidence`
sit in the same package as the comment saying they do not exist.** Each clause was true when
written; nothing brought them back into contact, because a javadoc is the one artifact in this
repository that no gate reads.

The fourth: `findDefectLinks` claimed to be *"the only native query in this class"* — contradicted by
`countEvidence`, ten lines above it, which was already native when the sentence was written.

The half of each argument that survives is kept and now stands alone: the query is grouped because
the alternative is a count per execution, and it is *queried* rather than answered as a constant `0`
because a count nobody looked up is a claim rather than a lookup. That half never depended on the
table being empty.

## 6. What is NOT here

- **No replacement for any deleted method.** Each is three lines to write again on the day something
  needs it, and the rule requires that day to also be the day it gets a caller. `findForPages` is
  the one with a named future — the page tree carrying block counts, which `DocPageSummary` still
  has no field for — and the note sits where the method was.
- **`PermissionRepository` now declares nothing at all**, which is left as-is and documented rather
  than "fixed": the catalogue is 55 immutable seeded rows, `PermissionCatalogue` calls `findAll()`
  once and holds the result, and there is no second question to ask of that table.
- **No sweep of the same question at other seams** — service methods, ports, event handlers. The
  rule's own comment records why the broad form was refused, and each of those populations has a
  reflective caller the bytecode cannot see.
- **Nothing on a screen or a wire differs**, so there is no browser-queue row and no contract change.

## 7. Gates

`cd backend && ./gradlew build` — **SUCCESSFUL in 6m18s, 2402 tests across 499 result classes, 0
failures** (§151's run: 2401 / 499). +1 test, no new class: one rule added to an existing
architecture class. `frontend/` and `packages/` were not rebuilt — neither was touched.

The rule was verified red against the tree it was written on, naming all ten. Rows `SC-1`…`SC-4` in
`docs/verification/backend-mutation-checks.md`; `SC-1` and `SC-2` are marked RUN.

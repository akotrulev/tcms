# §124 — the decided-and-unapplied, and the two keys nothing asked for

**M3 slice 8 · decision round 3's step 4 · three phases · both gates green**

Plan §8 **#47 and #53**, which the round put in one step because neither needed designing: both had
already been decided and neither had been done. That is the whole character of this slice — there is
no new idea in it, and it is the third consecutive session to find that the expensive thing is not
deciding but *applying*.

| Phase | What |
|---|---|
| 1 | `BEST` removed from `AggregationPolicy` — decided 2026-08-01, applied 2026-08-07 |
| 2 | `run:execute` gets its first consumer since `V3` seeded it in M0 |
| 3 | `artifact:delete` gets an endpoint — there had been no delete at all |

## Phase 1 — `BEST`, and why a label is not neutral

#17 resolved on 2026-08-01 that `BEST` should go, and nothing applied it for six days. The reason it
was there in the first place is worth keeping, because it is a good argument that is wrong:

> "it passed on the re-run" is a real thing somebody will say, and it is better said in the open
> under a label than smuggled into a default.

**What that misses is that a label does not merely surface the argument — it wins it.** A dropdown
value carries the product's endorsement, and a policy named *best* reads as the generous reading
rather than the dishonest one. What it produces is a red suite reported green, on the one screen
(§6e) whose entire purpose is that two teams quoting one pass rate mean the same thing.

It cost nothing to drop, for two reasons the original decision only had one of. The reading it would
have been traded against — `WORST`, the release gate — was already in the enum. And **the product has
since grown the honest way to make the same point**: §121's flake detection says a case is unreliable
without calling it green, and §112's variability section shows the retries a policy would have
collapsed. A flaky case is now a fact with a name, rather than a number chosen by whoever picked the
dropdown.

**`BEST` is refused with a 400 naming the four that remain, never a silent fallback to `LATEST`.** A
bookmark, a saved URL or a screenshot's query string carrying the old value must not answer a
different question under the same name — which is the same harm one level down.

### Three things the removal found

- **Two enums, and the check that made a deletion landable.** `reporting` carries a deliberate copy
  (ERD §0.2 forbids importing `execution`'s), and `AggregateExecutionPolicyTest` pins the two name
  sets equal. That check was written for *additions* — a value added to one copy is a report that
  silently refuses a policy the plan page offers. **It matters more for a deletion**: a missing value
  fails closed and loudly, but a copy that still *accepts* a retired policy answers a number the plan
  page can no longer produce.
- **The frontend caught its own half, twice.** `satisfies Record<AggregationPolicy, string>` on the
  two label maps and `satisfies readonly AggregationPolicy[]` on `GenerateReportDialog`'s `POLICIES`
  all stopped compiling. That guard is always described in the adding direction — a new enum member
  is a compile error until labeled — and it works exactly as well in the removing one: a menu item
  offering a policy the server now refuses could not have survived the change.
- **The removal is pinned from outside, not by absence.** The test that asserted `BEST` returned
  `[2, 0, 0, 0, 2, 4]` now asserts it 400s, and the test that asserted `BEST` emptied the blocker
  list now asserts **no remaining policy can**. The second is the better test: it states what the
  removal bought rather than that a value is gone.

**The one red run was a rounding assumption, and the honest fix was the test.** A new assertion
compared the exit criterion's `actual` against the response's own counts, unrounded — three of
thirteen is `23.0769…` and the endpoint serves `23.1`. The rounding is deliberate and it is what
every screen shows, so asserting the unrounded value would have been asserting against a number
nobody sees. Tolerance widened to `0.05`, with the reason written down; it still discriminates by a
wide margin, since `LATEST` answers `25.0` on the same fixture.

## Phase 2 — `run:execute`, and the only key that separates a person from a credential

Seeded by `V3` and correctly granted since M0; **no code had ever asked for it**. The scope
assessment called it a cheap win in two consecutive rounds and neither round took it.

It looks redundant beside `result:submit`, which is presumably why nobody wired it. It is not, and
the reason is exactly one row of the roles table:

| | `result:submit` | `run:execute` |
|---|:--:|:--:|
| Test Engineer and up | ✓ | ✓ |
| **Automation Service** | **✓** | **✗** |

**Every other execution permission separates a lead from an engineer. This one separates a *person*
from a *credential*, which no other key in the catalogue does.** Automation Service holds
`result:submit` because ingesting a CI report is its entire purpose — and without this gate the same
token could also `PATCH` executions here one at a time, with an `If-Match`, with evidence,
indistinguishable in the audit trail from a tester working a checklist. That is the laundering §117
and §121 each closed one door on, through a door nobody had checked.

### The order is the design, and the first draft had it wrong

Asked **in front of** the verdict gate, a Viewer — who holds neither key — would have been told to
post to the ingestion endpoint they may not use either. **Advice that is confidently wrong is worse
than a flat refusal**, and it would have shipped as a 403 whose sentence read plausibly.

Asked **inside** it, `NotExecuting` means *"you have another door"*, and past the `result:submit`
gate that is true by construction. So the two 403s give opposite advice on purpose, and a test
asserts they differ rather than asserting either one alone.

The red run was real: deleting the gate reddens exactly the three new tests and nothing else — the
property §71's `company:teams` never had, and the reason that key's wiring was unassertable.

**No browser row, and that is not an omission.** The only principal the two keys distinguish is a
token, which never renders a screen; `ExecutionGrid`'s header now records why its `PermissionGate`
deliberately does not ask for `run:execute`, and what would make that comment stop being true
(§110's F5 — authorable roles).

## Phase 3 — `artifact:delete`, and a comment that described the schema from memory

The key had been in the catalogue since M0 with no consumer, and the reason is sharper than "nobody
wired it": **there was no delete endpoint at all.** Sixth instance of the writerless shape §116,
§117, §120, §121 and §122 each closed one of.

**The gate is the point.** A Test Engineer holds `artifact:edit` at plan scope (that is what `V19`'s
qualifier exists for), so they may register a cycle's documents. `V3` withholds `artifact:delete`
from them entirely. Reusing the create's constant compiles, reads naturally, is one word, and hands
every engineer the ability to remove a lead's sign-off record — **in the one module where the
artefact being destroyed *is* the audit trail.** The mutation was run: the substitution reddens
exactly that test.

### Delete is for a mis-registration; everything else supersedes or archives

§6n makes the register's value the audit trail, and both non-current states keep the row readable.
Deletion answers the narrower question — *"that link should never have been in here"* — where keeping
the row leaves the register asserting something untrue. Two 409s hold that line, and **neither can
fire in any deployment today**:

- **`write_once`** has carried its meaning in a `V1` comment since M0 (*"sign-off records:
  corrections supersede, never edit"*) with no enforcement anywhere, and nothing sets it `true` yet.
  It is honoured **now** rather than when a writer appears, because enforcement arriving *after* the
  control it protects is the `Company.suspend()` shape the plan recorded two days ago: a guarantee
  written in a comment, believed by everyone, doing nothing. A fixture flips the column by SQL, which
  is what makes an early guard **assertable instead of aspirational** — and that is the difference
  between this and §121 phase 3's deleted seam. A seam guesses at a consumer that may not want it; a
  refusal guesses at nothing, because it is the column's own documented meaning.
- **`superseded_by_id`** is a self-referencing foreign key, so deleting the *replacement* dangles the
  replaced row's pointer.

### The comment that was wrong because nothing could disprove it

`Artifact`'s javadoc has said since §61:

> Nothing targets an artifact: `superseded_by_id` points the other way, from the replacement to the
> replaced, so removing a row breaks no inbound edge.

Whichever direction that column is read in, it is a self-FK, and **the row it points at has an
inbound edge by definition**. The claim survived because it described the schema from memory, no
check read the schema, and — decisively — *nothing could delete a row*, so nothing could disprove it.
§100's rule for the fourth time: a comment stating a fact about another file is not a check. It is
struck through and corrected in place, and the query that disproves it says so in its own header.

The refusal is written natively, because the column is deliberately unmapped on the entity (mapping a
field this slice cannot set would put an always-null accessor on the wire). Native SQL is governed by
RLS rather than the Hibernate filter, which is the stronger of the two; `BoardFacts` is the precedent.

### The reachability row is the one worth reading

Everything lines up for a `findById` to succeed: the row is in the caller's **own company** so RLS
admits it, the caller holds the strongest key in the module, and the project named in the path is one
they are **fully granted on** — so the gate passes. Only the `project_id` predicate stops the delete.
It is seeded by SQL because the project it lives in is granted to nobody, which is exactly what makes
that project the right home for the fixture.

## Gates

| Gate | State |
|---|---|
| `cd backend && ./gradlew build` | **BUILD SUCCESSFUL — 2024 tests across 427 result classes, 0 failures** (§122's baseline was 2014/426; phase 1 moved it to 2013, phase 2 to 2015, phase 3 to 2024) |
| `cd frontend && npm run build` | clean |
| `npm run lint` | 0 warnings |
| `npm run test:run` | **1045/1045 across 79 files** — unchanged, no frontend test was added or removed |

> **One frontend run showed `1 failed | 1044 passed` and the re-run was green.** It was started
> concurrently with `./gradlew build`, so CPU contention on a timing-sensitive RTL test is the likely
> cause — but **the failing test was not captured before the re-run overwrote the output, so this is
> recorded as an unidentified flake rather than explained away.** The gate figure above is from a
> clean solo run. Do not read it as "1045 has always been green"; read it as one unattributed red.

## State

**Built and unverified — 16 mutation rows, 3 RUN and stated as a deviation; 4 browser rows, none
run.** The three that were run are one per phase, each the substitution a reviewer would most
plausibly wave through, and all three phases turn on a **constant** rather than on logic — a constant
swap is invisible in review, which is why these were the three worth the exception.

**`artifact:delete` has an endpoint and no screen.** `DELETE /projects/{id}/artifacts/{id}` has no
consumer anywhere in `src/`, so the register still cannot remove a mis-registered link through the
product. That is a smaller version of the shape this slice exists to close — a key with no consumer
became an endpoint with no consumer — and it is written down as unbuilt rather than left to be
noticed. The control, its confirmation and the two 409 sentences are a frontend slice; §118's dialog
is the pattern.

## What this slice says about the list

Three of the four things it touched had been **decided and not done** (#17, #53 twice over), and the
fourth — the `Artifact` javadoc — was **written and never checked**. Round 3's own diagnosis was that
§8 "did not stay closed, it stopped being read." This slice is the same sentence one level down: the
code carried the decisions, in comments and in a catalogue, and nothing read those either.

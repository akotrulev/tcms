# §45. The third parallel run, and the decision to stop running them (2026-08-01)

**Not a feature slice.** §42, §43 and §44 are the product. This records the run that produced them,
and — because the user ended the experiment at the end of it — what three runs of evidence actually
support. Read it before proposing parallelism again.

| Lane | Slice | Location | Contract | Flyway |
|---|---|---|---|---|
| A | §42 plan and run creation (`execution`) | main checkout, `master` | **holder** | `V14` |
| B | §43 the `company:read` repair (`identity`) | worktree | forbidden | `V15` |
| C | §44 frontend resilience residuals | worktree | forbidden | none |

## The decision: stop, on cost, not on correctness

**The user ended the experiment after this run** — *"clearly the parallel runs are not working out
too well, they take 2x the time and tokens"*. The instruction is to work sequentially unless
parallelism is explicitly asked for. That is the operative rule; the rest of this section exists so
the reasoning is not re-derived from scratch, in either direction.

**What the cost actually was.** ~937k agent tokens across three lanes, and wall clock bounded by the
*longest* lane rather than the sum — lane A ran ~61 minutes before pausing to ask whether to
continue, against ~36 and ~45 for B and C. That is the arithmetic that makes parallelism disappoint
here: **it only ever saves the shorter lanes' time**, and this project's lanes are not the same size
because a vertical slice is not a unit of anything. Add a per-lane orientation pass over the same
handoff, and a worktree `npm install` for the frontend lane, and the overhead is real.

**What did not fail, and should not be misremembered as having failed.** File overlap between the
three lanes was **zero**, for the third consecutive run. The `openapi.yaml` holder rule held. The
Flyway pre-assignment worked so well it needed no renumbering at all — lane A landed `V14` before
lane B's `V15`, so landing order and assignment order coincided for the first time. Both merges were
clean; neither had a conflict. §33's machinery did its job.

So the honest summary is: **the seams were fine and the economics were not.** Anyone revisiting this
should not go looking for a coordination failure to fix, because there wasn't one.

**The one shape that would still justify it** is unchanged and is not a vertical slice:
`docs/plans/2026-07-31-parallel-execution-rework.md` §5's three fan-outs — four result parsers (M3),
four `IssueTracker` adapters (M4), three report renderers (§6e). Those share a **frozen port**
instead of each re-deriving a premise from the handoff, which is precisely the cost identified
above, and they are naturally equal-sized, which is the other one.

## The finding of the run: the same defect twice, in two lanes that never spoke

This is the part worth carrying forward, and it is not about parallelism.

| | `company:read` (§43) | Automation Service (§42 → repaired at integration) |
|---|---|---|
| The spec says | every member holds it (§A3) | the role holds five capabilities (§A6) |
| The seed says | granted on five of six roles | granted, exactly those five |
| The system does | unreachable at company scope for 5 of 6 roles | **unreachable entirely** — the role 404s on every path |
| Defeated by | `evaluateCompanyWide` is SYSTEM + COMPANY only | `canView` runs first, and `canView` *is* `project:read` |
| Found by | the first test that asked as a Test Lead | the first test that asked as the robot |
| Repaired by | `V15` — grant the missing baseline | `V16` — grant the missing precondition |

**Both are the same failure**: a permission the spec grants and the seed contains, made unreachable
by the scope or the ordering of the gate that asks it. Neither is a bug in any one class. Both were
invisible to every reading of `V3` — and had to be, because **the defect is an absent row, and
absence is what a reading is worst at** (the working agreement already says a catalogue-wide
invariant needs a computed check; this is the second and third evidence for it).

The Automation Service instance was worse than §43's. `ProjectAccess.canView`'s javadoc justified
the method's whole existence on the claim that *"every one of the six built-in roles holds
`project:read`"* — a sentence that was **false when it was written**, load-bearing, and had survived
every review since. The role that exists so a CI job can post results could not post one, which
would have been discovered at M3 as an ingestion failure with no obvious cause.

**Both repairs grant the missing permission rather than weakening the gate.** That is now the
project's rule for this family: loosening a gate to reach a permission is how a gate stops meaning
anything, and `canView` in particular is the one predicate two hundred call sites depend on.

**The rule is now a script, not a sentence.**
`SeedDataTest.everyProjectScopeRoleHoldsTheVisibilityPrecondition` derives from the catalogue that no
PROJECT-scope role holds permissions without `project:read`, so a role added a year from now is
covered by nobody remembering this. Mutation-verified: seeding the wrong key kills the computed
check, the §A6 enumeration and lane A's endpoint test together.

**Lane A's handling of it is the reusable behaviour**, and it is the third run in a row a lane has
done this correctly: it found the defect in a file another lane held, **did not fix it**, and pinned
it with a test asserting the *broken* behaviour whose failure message names the repair. Flipping that
test to `201` was the first thing integration did, and the test found its own way to the person
fixing it. That is strictly better than a note in a handoff, which is what the same situation got
in §41.

## The second finding: a slice that makes a state reachable inherits every screen that never rendered it

Lane A's live browser pass — **the first in three runs**, after §41 and §37 both ended without one —
found two defects no gate could have:

1. Creating a plan navigated to the plan *statistics* route, whose endpoint is deliberately
   unimplemented (§38 says why), so a **successful** create showed a red toast and "Not found" a
   second later.
2. A `NOT_STARTED` run rendered "Started — · Still running". `RunDetailPage` had two states where
   there are three and had never rendered the un-started one — **because until this slice no run
   could be created**.

The second generalises: **no test written before a state was reachable can fail on it.** The state
did not exist, so the screen was not wrong when it was written, and no amount of coverage on the old
states would have caught it. This is a distinct argument for the browser pass from §40's — that one
was about defects only *driving* finds; this one is about defects that only *become possible* when a
slice lands.

Recorded also because it predicts where to look next: **anything that materialises a new state should
be followed by an audit of the screens that already claim to render that entity.**

## Two operational notes

**The file-sync daemon has a second failure mode, and it looks nothing like the first.**
`CLAUDE.md` warns that `<name> 2.<ext>` conflict copies are hard compile errors. Lane A hit the other
one: **105 `* 2.xml` copies in `build/test-results/`, which Gradle hashes as task inputs**, producing
a `:test` task that stalled for 20 minutes with no error and no output. `find build -name "* 2.*"
-delete` and the build ran in 1m49s. A hang, not a failure — so the existing warning would not have
led anyone to it.

**Lane B did not update `role`'s table comment** when it added the seventh built-in, so V1's
`'Seeded with exactly six built-ins'` became false the moment `V15` ran. Corrected in `V16` with a
`COMMENT ON TABLE` (V1 is immutable — it has run everywhere). This is §41's lesson recurring within
one session: *a comment true only until someone changes the thing it describes is a comment that
will lie.* A migration that changes a count should be assumed to falsify a comment somewhere.

## Gate evidence — the merged tree, not the lanes

Three separately-green trees are not evidence about the tree that results from combining them. Every
number below is from `master` after both merges **and** the integration commit.

```
backend:   ./gradlew build → BUILD SUCCESSFUL in 2m 15s
           810 tests, 0 failures, 0 skipped   (733 + 63 A + 13 B + 1 integration)
frontend:  npm run build   → ✓ built; index 250.45 kB / 79.07 kB gzip
           npm run lint    → oxlint, exit 0, no output
           npm run test:run→ Test Files 15 passed (15) · Tests 252 passed (252)
           find "* 2.*"    → nothing outside node_modules
```

Integration had to fix **one** thing (the Automation Service gap), against three in §41 and three in
§37. Both lane merges were conflict-free and neither migration needed renumbering.

## Not done

- **`GET …/plans/{planId}/statistics` still runs on MSW**, and lane A's first live defect was a
  navigation *into* it. `bySuite` needs the live suite tree, `coveragePercent` a repository-wide
  denominator, and `trend` a `reporting` module that does not exist.
- **`documentation`, `reporting` and `work` writes still run on MSW.**
- **The contract-hygiene computed checks are still not built** — "every path declares at least one
  4xx" and "every `$ref` at a nullable column is an `allOf`". Lane A added the 4xx to its own two
  paths and audited the nullable `$ref`s **by reading**, which §8 is explicit is worth less than a
  script. Now the third run in a row this has been named and not done, and this session produced two
  more pieces of evidence that readings miss absent things.
- **`data-testid` on MUI `TextField` lands on the root `<div>`, not the `<input>`** (§44). Playwright's
  `fill()` fails on it; every `TextField` in the app likely shares the shape. A class, not an
  instance, and it belongs to the test-id contract rather than to any one lane.
- **Teams and memberships** remain the next write surface with no endpoints (§39), and `company:teams`
  / `company:members` are already answered by `AdminReach`.

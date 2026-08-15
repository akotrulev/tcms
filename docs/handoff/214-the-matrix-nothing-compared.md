# §214 — The matrix nothing compared

**One phase**, `backend/` test-lane plus one comment in `packages/`. **No migration, no contract
change, no permission moved, no production code in any stack, and not one byte of either `schema`
copy.** One new test, one new by-decision map, one class header corrected, one module header in
another package corrected.

§213's own "what is NOT done" opened it, at the top of the list:

> **Nothing reads the migration.** The chain still ends at the spec on this side, and
> `SeedDataTest` is the only link that sees `V52`'s rows.

That is true, and it is not the interesting half. The interesting half is what that link was
actually comparing.

## The chain two documents described, and the one the code had

`packages/client/src/test/roleMatrix.ts` has stated the chain since §212, in its own header, and
§213 restated it in the handoff:

> only `SeedDataTest` can compare the two — it derives Test Lead minus Test Engineer from the live
> catalogue and asserts §A4's seven keys exactly, in both directions. So the chain is
> `migration → SeedDataTest → spec → this file`.

Every clause of that is true. What it implies — that the arrow from the seed to the spec is a
comparison of the tables — is not. What `SeedDataTest` had was four assertions about **properties**:

| Test | What it reads the spec for | What it actually compares against |
|---|---|---|
| `testEngineerToTestLeadBoundary` | §A4's seven keys | a hand-written list of seven, one delta of one pair |
| `automationServiceIsTheSmallestRole` | §A6's exact grant | a hand-written list of six |
| `viewerIsReadOnlyPlusCommentAndExport` | §A2/§A3 | a hand-written `contains` and a hand-written `doesNotContain` |
| `instanceAdminHoldsEveryPermission` | — | the catalogue, derived |
| `catalogueMatchesTheContract` | — | `openapi.yaml`, both ways — **the keys, never the grants** |

Three of those name the spec in their failure message and never open the file. So the spec's §A3
tables — 55 keys across seven role columns, **385 cells** — were compared to the seeded rows at
exactly one place, and that place is the delta somebody happened to be arguing about in §210.

The direction that matters is not the one that reads like a missing test. A migration granting
Company Admin a key the spec withholds is a defect with a 403 attached and somebody eventually meets
it. A **spec edit** that quietly widens a column has no symptom at all — and both rules in
`packages/` parse that document to decide what prose in four corpora may claim, `agentKeys()` derives
the whole MCP surface from its Test Engineer column (§213), and `tools/list` is permission-filtered.
A wrong cell there is a wrong tool surface and a green suite.

## The population is three cells and they are one fact

Measured before the check was written, which is the only way a small number means anything: parse
§A3, replay `V3` plus the five migrations that have moved a grant since (`V15`, `V16`, `V40`,
`V51`, `V52`), compare.

| Role | Seeded and not in §A3 | In §A3 and not seeded |
|---|---|---|
| Instance Admin · Company Admin · Automation Service · Company Member | — | — |
| Test Lead · Test Engineer · Viewer | `company:read` | — |

All three are the same documented fact, and the spec states it in the paragraph immediately under
the table that cannot express it: `V3` seeds `company:read` onto five of the six built-in roles,
and §A3's `COMPANY` table has a column called **Every member**, which is `V15`'s baseline role
rather than a seventh tier. The per-role rows are **inert rather than wrong** — a `PROJECT`-scope
grant of a `COMPANY`-scope key is unreachable from `evaluateCompanyWide`, which is the §39/§43
finding — and `companyReadIsAlwaysExplicit` already pins them as deliberate in the other direction.

So they are `MATRIX_DIVERGENCE_BY_DECISION`, with the argument at the entry, **pinned both ways**:
§162's rule, that a one-directional ignore list cannot report a member that stopped needing to be on
it. An entry that stops describing a divergence fails the build exactly as an unexplained
divergence does.

That makes this a **guard** in §201's and §204's sense — a way the two documents could disagree is
removed, rather than a disagreement repaired — and this repository has learned to say so plainly
rather than let a clean run read as a save.

## The parse, and the three ways it asserts its own yield

A parser that silently reads nothing looks exactly like a matrix that agrees, which is the failure
mode every check in this class is written against. `matrixFromTheSpec()` is bounded by the `### A3.`
and `### A4.` headings and asserts:

- **55 distinct keys**, which is the catalogue `catalogueMatchesTheContract` pins independently;
- **no empty role**, which no role in this product is;
- **an unrecognised column heading fails**, rather than being skipped. §A3 has three table shapes —
  eight `PROJECT` tables with the six abbreviations, a `COMPANY` table with `Every member` and a
  prose `Covers` column, a `SYSTEM` table with `IA` alone — and a column nobody compares is the
  thing this test exists to stop. A seventh tier arriving is a spec decision (§A2), not a parse fix.

`Agent` is deliberately not in it: §A3 has no column for it, it is §A6a's *derived* role, and
`agentIsTestEngineerMinusNothingItCouldUse` is what pins it — from the live catalogue, in both
directions, which is the shape that caught `V51` (§207).

**Measured red in both directions**, against mutations rather than asserted:

| Mutation | Result |
|---|---|
| §A3's `case:comment` row: Viewer `✓` → `—` | `Expecting empty but was: {"Viewer / case:comment"="the database grants it and §A3's row for it says —"}` |
| a bogus `Viewer / doc:edit` entry in the by-decision map | the second half fails naming it: *"entries that no longer describe a divergence"* |

## Why the four property assertions stay

They are not redundant and deleting them would be the obvious wrong simplification. An equality says
two documents differ; a property test says **why a cell is what it is** and fails with the argument
attached — `automationServiceIsTheSmallestRole`'s message is about what a stolen CI token can do,
and that is worth more than "the table disagrees" on the day somebody widens it. What the equality
adds is the cells nobody thought to name.

## Files

| Path | What |
|---|---|
| `backend/src/test/java/com/tcms/schema/SeedDataTest.java` | `theSeededMatrixIsTheSpecsMatrix`, `matrixFromTheSpec()`, `SPEC_COLUMNS`, `MATRIX_DIVERGENCE_BY_DECISION`, and the class header, which claimed this class already held the spec's tables to the seed. |
| `packages/client/src/test/roleMatrix.ts` | The chain sentence, struck and corrected — plus the CI window under it, stated where a reader of that file will meet it. |

`backend/build.gradle.kts` needed **no** change: `../docs/specs` has been a declared `Test` input
since §185, as a directory rather than a file, for exactly this case — a fifth artifact added there
is one this suite would want to see. Had it not been, this rule would have been green by cache on
every spec edit, which is worse than not having it.

## Gates

Run on `shanks`. `frontend/` holds no changed byte.

| Gate | Result |
|---|---|
| `cd backend && ./gradlew build` | **SUCCESSFUL, 2702 tests / 0 skipped / 0 failures** (+1) |
| `cd packages && npm run build` | clean |
| `npm run lint --workspaces` | 0 warnings, 0 errors |
| `npm run test:run --workspaces` | **320 passed** — 78 cli / 123 client / 119 mcp, unchanged |

## Verification

Nothing here reaches a server or a screen. No browser row and no mutation row: the slice adds a
test and two comments, and §65's queues are for behaviour a gate cannot observe. `§203 PL-1` and
§212's `PL-7`/`PL-8` are carried forward unrun.

## What is NOT done

- **The window is unchanged, and it is now the largest thing on this list.** The comparison lives in
  the **backend** suite and `ci.yml` runs `-x test`. Between a bad spec edit and the 01:17 UTC
  nightly, both `packages/` rules answer confidently out of a wrong document — and the push that
  made it wrong has already been delivered to the lab. Closing it is a decision with a cost:
  either a targeted `--tests '*SeedDataTest*'` job on the `tcms-lab` runner (a Testcontainers boot,
  ~30s measured, and the first crack in the 2026-08-02 `-x test` trade), or a `packages/`-side parse
  of the migration SQL, which is a small SQL interpreter and is not obviously honest.
- **Nothing still reads the migration.** `SeedDataTest` reads the *rows Flyway produced*, which is
  the better end of it — but a migration whose `INSERT` never ran, or ran against a different role
  id, is indistinguishable here from one that was never written. The seed is compared to two
  documents and to nothing that says what was intended.
- **`CLAUDE.md`'s and `README.md`'s convenience copies of the matrix are still copies.** Both say in
  as many words that the spec wins and the copies are the bug, and nothing compares either of them
  to anything. That is the same population one corpus over, it is measurable with the parser this
  slice just wrote, and it is the nearest standing candidate.
- **The three §212 items are untouched** and two are decisions rather than gaps: the
  `{@link Type#member}` citations nothing resolves (needs the compiler; `:compileTestJava` does not
  run javadoc), artifacts having no MCP surface at all, and `library:manage` being off it.
- **The tool count is still not pinned anywhere** (§213), unchanged.

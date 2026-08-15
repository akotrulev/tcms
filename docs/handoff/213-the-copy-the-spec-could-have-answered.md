# §213 — The copy the spec could have answered, and the list nothing compared

**One phase**, `packages/` only — four test-lane files, no shipping code in any stack. **No
migration, no contract change, no permission moved, no `backend/` or `frontend/` source, and not one
byte of either `schema` copy.** Two of the four edits are comments.

§212's own "what is NOT done" opened it, and it is the last entry on that list a slice can take
without a decision:

> **`AGENT_KEYS` is still a hand-kept copy.** §211's rule over tool descriptions reads it, and this
> one does not; the two now answer the same question from different sources, which is a
> disagreement waiting to happen and is **left as one because the honest fix is for the MCP rule to
> read `roleMatrix.ts` too, across a package boundary that currently has no test dependency.**

## The stated blocker was false, and the file that states it is the file that disproves it

`packages/mcp/src/test/harness.ts` has imported across that boundary since §203:

```ts
import { checkExchange } from '../../../client/src/test/contractExchange.js';
import { currentUser, permissions, project } from '../../../client/src/test/fixtures.js';
```

Twelve lines above the constant, under a comment explaining *why* the relative path is the right
shape (*"the comparison must exist once; `dist/` deliberately does not carry it"*). The dependency
this item was deferred for the absence of was already there, already argued, already load-bearing
for §203's whole exchange check.

This is another blocker note outliving its blocker, and **the count is deliberately not restated
here**: §121 called itself the fourth, §113's inert nightly the sixth, §143's *"a person at a
terminal is needed"* the seventh, and §115, §145 and §193 are three more that were never numbered —
a hand-kept tally of hand-kept tallies is the defect §158 landed on. What is worth recording is the
*kind*. Most of that family were true once and were fixed by a later slice without the note being
updated. This one and §193's were **never true**, and this one's life was one commit: §212 wrote it,
and the code contradicting it was in the file the note is about, inside the diff's own context
lines.

## The population is zero

Measured before changing anything, which is the only way a zero means something: the hand-kept list
and §A3's Test Engineer column agree **exactly, both ways, at 32 keys**. Nothing was stale. So this
is a guard in §201's and §204's sense — the way the copy could go wrong is removed rather than a
wrongness repaired.

That is worth being blunt about. The old comment's warning (*"a stale copy here widens or narrows
`tools/list` in every test at once"*) had never once been the thing that happened, across the three
migrations — `V40`, `V51`, `V52` — that moved the grant under it. Three slices remembered. The
fourth is the one this removes.

## The derivation, and the one way the parse could widen it silently

`agentKeys()` sits beside `roleMatrix()` in `packages/client/src/test/roleMatrix.ts` and is one
line of content: the `Agent` role is roles spec §A6a's **Test Engineer entire, minus
`company:read`**, and `roleMatrix` parses `PROJECT` scope alone, so `company:read` — a `COMPANY`-scope
key — is not in the set being subtracted from. The subtraction is invisible.

An invisible subtraction is a subtraction nothing notices going missing. So the guard is the case
where it would matter: if §A3's `COMPANY` table ever grew the six `PROJECT` columns, its rows would
parse into `TE` and the Agent would widen by a key **no token can answer for** — a credential with
no membership row has no `V15` baseline, and `evaluateCompanyWide` reads the SYSTEM and COMPANY
terms only. `agentKeys()` throws on `company:read` in the Test Engineer column and names both
readings in the message, because "the tables changed shape" and "the grant moved" want opposite
repairs.

**Measured red** against a doctored spec (a `company:read` row inserted into the `PROJECT` matrix,
restored immediately) rather than asserted.

## The second finding: the test whose name was a claim its body did not make

`server.test.ts`'s **`serves the whole surface to a credential that holds the Agent grant`** has
existed since §140. Its body:

```ts
expect(tools.map((tool) => tool.name)).toContain('tcms_write_case');
expect(tools.every((tool) => tool.name.startsWith('tcms_'))).toBe(true);
```

One name, and a prefix. **That passes on any subset.** `tools/list` is permission-filtered, so a
narrowed grant hides tools — which is the exact failure the constant's own comment warned about —
and this test, the only one that looks at what an MCP host actually receives, would have reported
green through it. It is §205's shape one file over: a check that reads as coverage and measures a
property nobody was worried about.

It compares against the whole registry now. **Measured red** by dropping one key (`doc:edit`) from
the derived set: **7 failed**, that test by name, `19` names against `20`. Under the old body the
same mutation is green.

Note what this changes about where the risk sits. Before, a stale `AGENT_KEYS` was invisible here;
now a *spec* that drifts from `V52` is visible here — and `SeedDataTest.agentIsTestEngineerMinusNothingItCouldUse`
is still the only thing that can catch the spec drifting from the seeded rows. The chain is
unchanged and this slice only shortens it: `migration → SeedDataTest → spec → both packages rules`.

## Files

| Path | What |
|---|---|
| `packages/client/src/test/roleMatrix.ts` | `agentKeys()`, its guard, and the header paragraph that argued *against* a second constant now recording that there is not one. |
| `packages/mcp/src/test/harness.ts` | `AGENT_KEYS` is `agentKeys()`. The javadoc's "keep this list in step" instruction is struck — there is no list. |
| `packages/mcp/src/server.test.ts` | The whole-registry comparison, and why `toContain` was not one. |
| `packages/mcp/src/writes.test.ts` | §211's rule comment: its instrument is the spec now, not this package's copy. |

## Gates

Run on `shanks`. `backend/` and `frontend/` hold no changed byte, so neither suite was run — and
both of this repo's cross-corpus rules (`citedFiles`, `tierClaims`) live in `packages/` and scan
those trees from here, so the corpora were still read.

| Gate | Result |
|---|---|
| `cd packages && npm run build` | clean |
| `npm run lint --workspaces` | 0 warnings, 0 errors |
| `npm run test:run --workspaces` | **320 passed** — 78 cli / 123 client / 119 mcp. Unchanged: no test added, one rewritten in place. |

## Verification

`packages/` still has no persistent verification queue (§203's finding, unchanged). Nothing here
reaches a server: no row is added, and §212's `PL-7`/`PL-8` are carried forward unrun beside
`§203 PL-1`.

## What is NOT done

- **Nothing reads the migration.** The chain still ends at the spec on this side, and
  `SeedDataTest` is the only link that sees `V52`'s rows. A spec that drifts from the seed reddens
  the **backend** suite, which `ci.yml` does not run — so the window between a bad spec edit and
  the 01:17 UTC nightly is a window in which both `packages/` rules confidently answer from a wrong
  document.
- **`roleMatrix` parses `PROJECT` scope only**, so no rule in this package can say anything about a
  `COMPANY`- or `SYSTEM`-scope claim. §212 chose that deliberately for the tier rule; `agentKeys()`
  inherits it, and the guard above is the whole of what compensates.
- **The tool count is still not pinned anywhere.** The new comparison is registry-against-list, so
  a tool deleted from `tools.ts` moves both sides and passes. §212's `21` is a sentence in a module
  header and in `CLAUDE.md`, checked by neither.
- **The other three items on §212's list are untouched** and two are decisions, not gaps: the
  `{@link Type#member}` citations nothing resolves (needs the compiler; `:compileTestJava` does not
  run javadoc), artifacts having no MCP surface at all, and `library:manage` being off it.

# §208 — The retire that cost more than the delete

**One phase.** `LifecycleLadder`, `CaseBulkService`'s refusal sentence, two endpoint tests, one new
unit test, `openapi.yaml` (two description blocks) and both regenerated `schema` copies, the roles
spec, and three prose sites in `frontend/src` plus one behavioural table there.

**No migration, no permission moved, no grant changed.** The catalogue is the same 55 keys and the
same rows it has been since `V51`. What moved is a **gate placement** — four lines of one method —
and the reason that is a slice rather than a diff is that the argument underneath it had been dead
for a day and nothing in the build could tell.

This is §207's own named follow-up, taken with its product question answered first.

---

## The incoherence, stated the way §207 left it

`V51` (§207) made `case:delete` a plain grant every Test Engineer holds over every case. It did not
touch the authoring ladder, and it said so:

> A Test Engineer may now delete a colleague's case and may not deprecate it. […] Not repaired here,
> and that is a scope decision rather than an oversight.

`LifecycleLadder.permissionFor` charged the **approve** key for the move to `DEPRECATED` from every
state, on one sentence:

> Retiring is the soft form of a delete, and the delete is Lead-only (`suite:delete` outright,
> `case:delete` `OWN`-qualified), so leaving the retire at the edit key would route around both.

Half that premise died with `V51`. The conclusion did not merely weaken — **it inverted**: the
stronger act (destroy a colleague's case) became cheaper than the weaker one (take your own draft out
of use).

---

## 1. The question was not "is this a bug", it was "what is a retire"

The repair was **not** derivable from the defect, and that is worth stating because the previous
twenty slices mostly were. "The premise is gone" tells you the old answer is unsupported. It does not
tell you the new one. Three readings were live:

- retiring is CRUD on content → the edit key, always;
- retiring is an approval act in its own right → the approve key, always, and the prose gets rewritten
  to stop leaning on a delete;
- retiring is content work **unless there is a sign-off to end** → the edit key below `APPROVED`, the
  approve key at it.

**The third, decided at the user's direction.** It is the only one that answers on the act's own
terms rather than by reference to a neighbour, and it turns out to be the rule the class already had
for every *other* edge — which is why the implementation shrank rather than grew.

### The rule, in one sentence

> **The approve key buys a sign-off, and it buys touching an artifact that has one.**

```java
static String permissionFor(LifecycleStatus current, LifecycleStatus requested, Keys keys) {
    if (requested == LifecycleStatus.APPROVED) {
        return keys.approve();
    }
    return current == LifecycleStatus.APPROVED ? keys.approve() : keys.edit();
}
```

Five of the sixteen pairs move: `DRAFT → DEPRECATED`, `IN_REVIEW → DEPRECATED`,
`DEPRECATED → DEPRECATED`, `DEPRECATED → DRAFT` and `DEPRECATED → IN_REVIEW`. Seven of the sixteen
still cost approve, and the three that matter — `APPROVED → DRAFT`, `APPROVED → IN_REVIEW`,
`APPROVED → DEPRECATED` — are §75's finding, untouched.

---

## 2. `rank` was the shape of the old answer, and deleting it is part of the new one

The old method had an ordinal:

```java
boolean unmaking = rank(requested) < rank(current) && rank(current) >= rank(APPROVED);
```

with `DEPRECATED` at **3 — above `APPROVED`**. That single number is where both halves of the
incoherence lived. Ranking retirement above approval is what made retiring reachable only from the
ladder's top, *and* what made reviving read as an unmaking. Neither was ever argued; the rank comment
argues only that the order is "the domain's own — drafted before reviewed before approved before
retired", which is a claim about **narrative order**, not about authority. The two were the same
sequence by coincidence and were being used as if they were the same thing.

With the gate turning on one status rather than on an order between two, `rank` has no caller and is
deleted — §152's shape. `PlanLifecycle#rank` is **not** the same copy and stays: `PlanStatus` has no
`DEPRECATED`, so its three values genuinely are a line and the unmaking term genuinely is an ordinal
question there.

---

## 3. The suite half was re-argued, not inherited

The tempting move was to split the two aggregates — free the retire on a case, where `V51` broke the
premise, and leave it Lead-only on a suite, where `suite:delete` is unchanged and the sentence still
parses. **It was considered and rejected**, and the reason is that the sentence parsing is not the
same as it being true.

`suite:delete` **removes** the folder. Deprecating **keeps** it, keeps its members, keeps every
reference into it, and can be undone. Those are not two strengths of one act. And the ladder already
knows this: `decide`'s `DEPRECATED`-from-anywhere rule exists because *"a folder full of cases can
neither be deleted (the delete refuses a non-empty suite) nor retired, which is a dead end reachable
by ordinary use"* — an argument that the move must be **legal** from a draft, sitting for eighty
slices next to a gate making it **unaffordable** from a draft. The dead end was closed on one layer
and left open on the one above it.

So retiring a draft folder is the same kind of act as renaming one, and `suite:manage` is already the
key for that. Roles spec §A4 principle 1, as `V51` rewrote it — *content vs shared structure and
judgement* — holds on both aggregates without an exception: the contributor's key does structure
work, and **removal** is still `suite:delete` and still Lead-only.

---

## 4. Reviving is symmetric, and that is a decision rather than a fallout

`DEPRECATED → DRAFT` costs the edit key too. This was asked and answered separately, because it does
not follow from the retire decision and it is the half that can make the slice worse than what it
replaced.

The ladder **cannot see** whether an artifact was approved before it was retired — the pair is the
whole of its input — so the question is which way to resolve the ambiguity:

- **Fail-closed (keep approve).** No behaviour change, and a Lead's decision to retire can only be
  undone by a Lead. Cost: a contributor who may now retire a draft cannot undo it. Retiring becomes a
  **one-way door for exactly the caller the slice just let through it**, which is §68's and §69's
  bound and the argument `decide` already makes for `DRAFT`-from-anywhere.
- **Symmetric (edit).** Chosen. Nothing is unmade coming back: `standingOf` maps `DEPRECATED` to
  `OUTSIDE_REVIEW`, so the sign-off — if there was one — ended **at the retirement**, performed by
  somebody who held the approve key to do it. What returns is a `DRAFT` that must pass review again,
  which is `decide`'s existing rule and §6g's clone argument.

The second reading is the one where the two halves of the ladder tell the same story. It is written
down as a decision in `permissionFor`'s javadoc rather than left to be re-derived, because the
fail-closed instinct will propose the mutation again — which is why `§208 DM-2` is the mutation row
flagged ⚠️.

---

## 5. The finding: sixteen pairs, five assertions, and none of them a table

`LifecycleLadder`'s header has said since §75 that *"a test can enumerate all sixteen pairs without a
database"*. **No such test existed.** The gate's entire coverage was five assertions spread across
two endpoint tests — each one a container, a Spring context, a seeded persona and a real row — so the
eleven pairs nobody thought to name cost nothing to leave out and were invisible from inside either
file. §205's shape: a check that was described and not written, and the description reading as
evidence.

`LifecycleLadderTest` is that table now, ~40 lines and no context. Its last test is total over the
enum by construction and pins the approve key's whole reach as a list of seven strings, both
directions, for `SeedDataTest.ownIsCarriedByNoGrant`'s reason: an assertion per interesting pair
records which pairs somebody thought about and says nothing about an eighth appearing.

The endpoint tests keep their rows and are not redundant — they assert the **wiring** (that each
caller passes its own `Keys` and the row's stored status), which is the half a pure unit test
structurally cannot see. What changed is that they are no longer the only observer of the **rule**.

---

## 6. One refusal sentence was left describing the wrong thing

`CaseBulkService.leadOnly` had a dedicated arm:

```java
case DEPRECATED -> "Only a test lead can deprecate cases.";
```

After §208 that arm is reachable **only when the row is already approved** — so the sentence would
have been true of the permission and false of the reason, hiding the one fact a caller cannot see
from a selection of four hundred: *which* rows refused, and why. `DEPRECATED` joins the arm that
names the row's current state (*"Only a test lead can change a case that is already approved."*).
§149's shape one sentence over — an `errors` value that is technically a message and practically
useless — and it has its own mutation row (`§208 DM-4`) because a gate test cannot catch it.

---

## 7. What the frontend already had right, and the §31 gap under it

The SPA carries a **second copy** of this rule: `SuiteList.tsx`'s `suiteMoves`, per-status, with the
key each edge costs. It is a disabling hint rather than a gate, and it was updated in the same commit
— three rows from `suite:approve` to `suite:manage`.

The case side needed **no** change, and the reason is the finding: `BulkActionDialog` gates only the
`APPROVED` option and never gated `DEPRECATED`. Until §208 that was a §31 disable-don't-hide gap in
the hardest direction to notice — the select offered Deprecate **enabled** to every Test Engineer
while the server skipped every row — so a contributor's Deprecate submitted, returned 200, and
reported that nothing had happened. §208 closes it from the server's side, because the option was the
half that was right.

Also repaired there: `BulkActionDialog`'s header still claimed *"`case:delete` is the only
`OWN`-qualified key in the catalogue"*, which `V51` made false one slice earlier. The conclusion it
supports is unchanged and now rests on a stronger fact — **no** key carries `OWN` — so it is struck
in place rather than rewritten.

---

## Gates

Run on `shanks` (`~/bin/tcms-remote`), the Linux observer:

| Stack | Command | Result |
|---|---|---|
| `packages/` | `npm run build` · `npm run lint --workspaces` · `npm run test:run --workspaces` | ✅ · 0 warnings ×3 · **302** (119 client / 78 cli / 105 mcp), unchanged |
| `frontend/` | `npm run build` · `npm run lint` · `npm run test:run` | ✅ · 0 warnings · **1341 across 100 files** (+2) |
| `backend/` | `./gradlew build` | ✅ **2663 tests · 0 failures · 0 errors** |

The backend figure is +10 against **§206's** 2653, which is the last count on record — §207 recorded
no total, so its own additions are inside that delta. This slice contributes **eight**:
`LifecycleLadderTest`'s six, plus one net on each endpoint test (three added, two replaced).

---

## Verification

**Nine rows, none run** (§65).

- `§208 DP-1`…`DP-5` in the browser queue — the Deprecate a contributor could not press, the half
  that is still refused, the way back, and the mixed bulk selection. Lane **L2**; **no second account
  needed**, unlike `§207 AQ-*`, because this gate turns on the artifact's status rather than on who
  authored it.
- `§208 DM-1`…`DM-4` in the mutation queue. `DM-2` is the ⚠️ row: it is the mutation a reviewer is
  most likely to *propose* rather than to make by accident, and it must redden `leavingAnApproval` as
  well as the revive rows — because the same edit silently frees `APPROVED → DRAFT`, which is §75's
  whole finding.

---

## Files

| File | What |
|---|---|
| `backend/…/repository/LifecycleLadder.java` | `permissionFor` rewritten to four lines; `rank` **deleted**; the class header's §A4 sentence struck and re-based; `Keys`' two `@param`s restated; `decide`'s `DEPRECATED` bullet gains the one-layer-up argument. |
| `backend/…/repository/CaseBulkService.java` | `leadOnly`'s `DEPRECATED` arm folded into the already-in-this-state sentence (§6). |
| `backend/src/test/…/repository/LifecycleLadderTest.java` | **New.** All sixteen pairs, no Spring context, no database. The test the class header has described since §75. |
| `backend/src/test/…/repository/CaseBulkEndpointTest.java` | `engineerCannotDeprecate` → `engineerDeprecatesADraft`, plus `engineerCannotDeprecateAnApprovedCase` asserting the refusal **sentence**. |
| `backend/src/test/…/repository/SuiteWriteEndpointTest.java` | `engineerCannotDeprecate` → `engineerDeprecatesADraft`; `leadDeprecatesADraft` subsumed; plus `engineerCannotDeprecateAnApprovedSuite` and `engineerRevivesARetiredSuite` (using the existing `RETIRED` fixture). |
| `docs/specs/openapi.yaml` + both `schema` copies | `setSuiteStatus`'s two permission bullets rewritten with the superseded argument struck; `BulkOperation`'s description **corrected beyond this slice** — it stated the target-decides rule §75 replaced and never mentioned the per-row half at all. Regenerated in both stacks. |
| `docs/specs/2026-07-30-tcms-roles-permissions-naming.md` | §A4 principle 2 gains *what `*:approve` buys, stated once*; one change-log row. **No matrix cell changed.** |
| `frontend/src/features/cases/SuiteList.tsx` | Three `suiteMoves` rows `suite:approve` → `suite:manage`; the header's approve-keyed argument re-based. |
| `frontend/src/features/cases/SuiteList.test.tsx` | Two tests: an engineer deprecates a draft, an engineer restores a retired suite. Both use `waitFor`, because a pending permission query and a refused one render identically. |
| `frontend/src/features/cases/BulkActionDialog.tsx` | Prose only. The stale `OWN` claim struck (§7), and the gating rule stated: only `APPROVED` is knowable from the form. |
| `docs/verification/*.md` · `CLAUDE.md` | Nine queue rows; the frontend test count. |

## Left open

- **`ArtifactStatus` has two unreachable values.** Found while deriving the seven lifecycles for this
  slice's question, not part of it. `Artifact.java:230` is the only writer of the column and it writes
  `ACTIVE`; `SUPERSEDED` and `ARCHIVED` have no endpoint, and `artifact.superseded_by_id` exists with
  nothing able to set it. That is §116/§117/§156's writerless shape on an enum rather than a column,
  and the register's own javadoc calls them *"the outcomes of acts that do not exist yet"* — so the
  candidate is the acts, or the decision not to have them.
- **A doc page's three statuses have no ladder at all** and every transition is free to anyone with
  `doc:publish`. Deliberate as far as `DocPageStatus`'s javadoc goes (*"not a lifecycle the way
  `LifecycleStatus` is"*), and recorded here only because this slice is the first time the four
  status models were compared side by side. Not obviously wrong; obviously unexamined.
- **§207's `DQ-3` is still the row that matters** and this slice does not touch it. Nothing proves the
  retained `OWN` machinery works from seeded data.
- Everything §206 carried forward, minus nothing — §208 took an item from §207's list, not from that
  one.

# §175 — three declarations of one team, and the refusal that named the wrong obstacle

**2026-08-12 · one phase · `backend/` + contract + both `schema.d.ts` copies: no migration, no
frontend or `packages/` source, and one property leaves the wire.** §174's named next slice, and the
oldest standing candidate in this repo — `TeamRef` has been "the next tenant" since §147, twenty-eight
sections ago.

## What the candidate said, and what was actually in the way

§147 extracted `UserRef` into a new `com.tcms.api` module and, in the same slice, **refused** to
extract `TeamRef`:

> *"`project.TeamRefView` and `identity.RoleAssignmentView.TeamRef` are two copies of `TeamRef` and
> were left alone. This is the next tenant by the same argument that made `UserRef` one, but two
> copies is not six … the extraction should be taken when a third consumer appears rather than on
> the strength of this slice's momentum."*

**There were three copies on the day that was written**, which §165 found and corrected eighteen
sections later. So the stated condition — *a third consumer* — had already been met before the
sentence naming it was finished, and the candidate then sat for another ten sections behind a
trigger that had already fired.

That is worth more than a tally correction, because the refusal was still *right* and its reason was
not the one it gave. §165 found the real obstacle while proving the count wrong: **only one of the
three records served the `TeamRef` schema.** The other two served *inline* object shapes —
`RoleAssignment.team` and `CurrentUser.teams[]` — declared anonymously in `openapi.yaml`, with the
same properties and no relationship to `TeamRef` that a generated client, a compiler or a convention
test could see. And one of them, `auth.CurrentUserService.TeamRef`, was **not the same shape**: it
carried `isLead`.

Collapsing three Java records behind three schemas that do not agree produces one record that agrees
with the contract in one place and coincides with it in two others. **The obstacle was in
`openapi.yaml`, and the candidate had been filed as a Java refactor for twenty-eight sections.**

## The three decisions, contract-first

### `RoleAssignment.team` is a `$ref`

Inline `{id, name}` → `allOf: [{$ref: TeamRef}], nullable: true`, the idiom its own `user` sibling
two lines above has used since §37. **Nothing on the wire moves**: same keys, same values, same
nullability. What changes is that the shape has a name, so a generated client can hold one value
where it held two structurally-identical anonymous types, and a rule can compare the server's record
to the schema instead of to a sentence in a test.

### `CurrentUser.teams[]` is a `$ref`, and `isLead` is gone

The flag was the one thing making this position a different shape, and the decision is not "it was
in the way". Four things, in the order they were checked:

- **Nothing reads it.** Zero hits in `frontend/src`, zero in `packages/`, zero in any generated
  client. The only `isLead` on the frontend is a local variable in `TeamMembersDialog` derived from
  the team's own `leads` array.
- **It confers nothing.** `team_membership.is_lead`'s own field comment says so: *"this flag confers
  no permission and is not read by the evaluator"* — it names the default reviewer.
- **`CurrentUser` exists to drive affordances.** `isSystemAdmin`'s description says exactly that. A
  flag no affordance turns on is a value nobody keeps correct, which is §156's finding in the
  direction this repo meets less often — not a column nothing writes, a property nothing reads.
- **A lead is named on `Team.leads`**, which is where the one screen that renders one reads it.

**What was checked before deleting, and is the reason the array itself stays.** `GET /teams` is
gated on `company:teams` — Company Admin and up — so `CurrentUser.teams` is the **only** way a
regular member learns their own teams. Deleting the array would have removed a capability; deleting
the flag removes a claim. The two are not the same edit and the difference is a permission gate.

### `com.tcms.api.TeamRef`, and the fourth copy that now fails a build

With both inline shapes resolved, the extraction is a consequence rather than a judgment. `api`'s
charter — *the contract defines it and more than one module serves it* — is met in the strongest
form available: three positions, three modules (`project`, `identity`, `auth`), and modules that may
not import one another.

`ArchitectureRulesTest.noRecordOutsideApiRepeatsAnApiShape` holds it from here, **matched on shape
rather than on name**, so the next copy is caught under whatever name whoever writes it chooses.
**Measured before the record moved**, per this repo's rule about catalogue-wide claims: exactly two
production records had components `{id: UUID, name: String}`, and both are the ones deleted. That
mattered — the shape is generic enough that homing a *near*-miss (`{id, key}`, `{id, title}`) would
have reddened the build on unrelated types, and the rule would have been blamed for it.

## What the collapse costs, said plainly

The three positions can no longer be compared **to each other**, because there is nothing left to
disagree. That is the intended end state and it is also a loss: `ContractConventionTest` reported on
three pairings here and now reports on one. The day `ReviewAssignee.team` genuinely needs a member
the other two must not have, no rule will say so and the decision is a human's — so `TeamRef`'s
schema comment carries the warning that **a field added there now reaches all three**, which is the
cost of the collapse and the point of it.

## The maps that emptied, and the one that stayed

`ContractConventionTest.HOMONYMS` — *production records carrying a schema's name without serving
it* — was **both** `TeamRef` entries and nothing else. It is `Map.of()` now.

**Kept, deliberately, and the reason is a rule rather than sentiment.**
`everyNameMatchedRecordIsPairedOrExplained` reads it on every run and names it in its failure
message: the day a second record is named for a schema it does not serve, the build fails *pointing
at this map*, which is when the sentence telling them apart is cheap to write. Deleting an empty map
moves that discovery to the first reader confused by the two records — and the staleness rule
underneath it (an entry whose class no longer exists fails the build) needs a map to be a rule about.
Empty is a result.

`INLINE_RECORDS` loses two of its twenty-five locations, which is the population rule doing its job
in the pleasant direction: the entries are gone because the anonymous shapes are gone, and
`everyInlineShapeIsPairedOrExempt` derives the population, so a stale entry would have failed.

Two stale claims were corrected where they were found, both made false by this slice rather than by
an earlier one: `RESPONSE_RECORDS`' header used `TeamRef` as its worked example of *"a name match
proposes, a human accepts"* — now `Attachment`, whose two owners no name match proposes at all — and
`CurrentUser`'s contract comment about `required` not descending, which was written for an inner
object that no longer exists there.

## Changed

`api/TeamRef.java` (new) and `api/package-info.java` (the third refusal, taken, with the note that it
named the wrong obstacle); `project/TeamRefView.java` **deleted**, `RoleAssignmentView.TeamRef` and
`CurrentUserService.TeamRef` **deleted**; `TeamDirectory`, `IdentityTeamDirectory`,
`ReviewAssigneeView`, `ReviewAssigneeEdit`, `ReviewService`, `AdminDirectoryService`,
`CurrentUserService` repointed; `openapi.yaml` (two `$ref`s, one property removed, three comments)
and both `schema.d.ts` copies; `ContractConventionTest` (`RESPONSE_RECORDS`' entry and its header,
`HOMONYMS` emptied, two `INLINE_RECORDS` entries removed).

**No production behaviour changed and no test was added.** The rules that already existed were
enough to hold the result, which is the argument for having built them: §170's inline-pairing rule
is what made this an edit rather than an argument, and §147's shape rule is what makes it stay one.

## Gates

`./gradlew build` **SUCCESSFUL (8m 30s) — 2538 tests, 516 containers, 0 failures** — identical to arrival, and that is the expected reading: no test method was added or removed, three map entries left, and no production behaviour moved. Frontend
`npm run build` · `npm run lint` · `npm run test:run` — clean · 0 warnings · **1251 across 96 files**
(unchanged; no frontend source moved). `packages` built in order, lint clean, **229** (66 + 85 + 78),
with both `schema.d.ts` copies regenerated and byte-identical to each other.

Browser rows `§175 TR-1`…`TR-2` **recorded and not run** (§65). Mutations `§175 TR-1`…`TR-3` are
checks over checks and were **run** (§165, §170 precedent) — see `backend-mutation-checks.md`.
**Built and unverified** in the product sense.

## The next slice

The browser queue is at the **bottom** at the user's direction (2026-08-12).

- **the bound-and-undeclared query parameter**, §171's recorded gap;
- **Git traceability (M4)**, from §170's deletion of `IngestionRequest.branch`/`.commitSha`;
- **`OUTDATED_SPEC_CHANGED` / `UNLINKED`**, derivable from `case_doc_link`;
- the **read-only-archive** decision §159 deliberately did not take;
- **serving the relabel audit** — §174's recorded absence, if a link history is ever worth a screen;
- **`CurrentUser.teams` has no reader either** — this slice deleted the flag and kept the array on a
  permission argument, and the array is still rendered by nothing. The honest next question is not
  "delete it too" but *which screen owes the person their own teams*;
- **run the browser queue** — ~700 rows, thirteen lanes, one sitting, scoped in
  `frontend-browser-checks.md`.

# §209 — The acts that did not exist, and the three surfaces that advised them

**Two phases.** Phase 1 is `backend/` plus the contract and both regenerated `schema` copies; phase 2
is `frontend/` only. **No migration, no permission moved, no grant changed** — the catalogue is the
same 55 keys `V51` left, and `artifact:edit` / `artifact:delete` keep the distribution they have had
since `V3`.

This is §208's first opened item, taken with its product question answered first.

---

## The gap, stated the way §208 left it

> **`ArtifactStatus` has two unreachable values.** `Artifact.java:230` is the only writer of the
> column and it writes `ACTIVE`; `SUPERSEDED` and `ARCHIVED` have no endpoint, and
> `artifact.superseded_by_id` exists with nothing able to set it. […] the register's own javadoc
> calls them *"the outcomes of acts that do not exist yet"* — so the candidate is the acts, or the
> decision not to have them.

**The acts.** Plan §6n specifies both — *"Superseding an artifact keeps the old version reachable and
marks it Superseded rather than deleting it"* — so this was never a design question about whether the
states belong; it was two writers nobody had written.

---

## 1. The finding is not the enum, it is who had already been told about it

The unreachable values would have been a §156-shaped note — *a column nothing writes* — except that
by the time this slice opened, **three shipped surfaces were already instructing users to perform the
acts**:

| Surface | What it says | Since |
|---|---|---|
| `ArtifactWriteService.delete`'s 409 | *"This is a sign-off record… **Supersede it with a correction instead.**"* | §124 |
| `ArtifactDialogs`' upload helper text | *"The bytes are fixed at registration — **replacing the document means registering a new artifact that supersedes this one**."* | §126 phase 3 |
| `openapi.yaml`, the delete's own description | *"an artifact that is no longer current is `SUPERSEDED` … or `ARCHIVED` … and both stay readable"* | §124 |

That is `ui-rules` §4's rule — *every backend-mandated follow-up has a UI affordance; an error naming
an action the UI doesn't offer is a design bug* — failed three times over, in the one module where
plan §6n makes the audit trail the point. And each of the three reads as *documentation of a
behaviour* rather than as a promise, which is exactly why none of them was ever checked: the register
told you what to do instead, confidently, and the thing it told you to do did not exist.

The shape §205 named — a check that was described and not written, with the description reading as
evidence — one layer up: an **act** that was described and not written, with the description reading
as an affordance.

---

## 2. The two acts are reached differently, and the asymmetry is the model

The one decision worth making here, and the whole slice falls out of it:

> **`ARCHIVED` is a judgement about this row. `SUPERSEDED` is a fact about another one.**

| | `ARCHIVED` | `SUPERSEDED` |
|---|---|---|
| Written by | `PUT /projects/{projectId}/artifacts/{artifactId}/status` | `supersedes` on either create |
| Chosen? | yes | **never** — it holds exactly when `superseded_by_id` names a replacement |
| Reversible? | yes, the same act back | **no** — see §5 |
| In `ArtifactStatusInput`'s enum? | yes | **no**, and that absence is the design |

`ArtifactStatusInput` declares `[ACTIVE, ARCHIVED]` — a **proper subset** of `Artifact.status`. A
contract offering a value the server refuses is a contract disagreeing with itself about one field,
which is §161's shape stated before it could happen. `parseCirculation` refuses `SUPERSEDED` as a
request value and `Artifact.circulate` refuses it as an argument, so a second parser cannot let it
through; `ArtifactCirculationTest` asserts a **correctly spelled** member is refused, which the
obvious implementation — a lookup over `values()` — would not survive.

### Superseding is one request, not two

The alternative was a `PUT` on the row being replaced, after the replacement had been registered. It
was considered and rejected twice over: it leaves a window in which the newer document exists and the
register still calls the older one current, and it needs a **second permission check** on a row the
create never gated. One request removes both, which is `ArtifactFileController`'s own
bytes-arrive-with-the-registration argument applied to a different pair.

---

## 3. The same-scope rule is the whole of the authorization, and it looks like tidiness

`supersedes` writes a second row. Nothing gates that row.

What makes the create's own scope-qualified gate sufficient is the rule that the replacement must sit
at the **same scope** and, at `PLAN` scope, in the **same plan** as the artifact it replaces — after
which the check that passed for the new row passed for a row in exactly the same place.

Delete that rule and this is the escalation:

> A Test Engineer holds `artifact:edit` at `PLAN_SCOPE`. They register a plan-scope note whose
> `supersedes` names the project's test strategy — a document plan §6n gives every plan **read-only**
> — and the strategy is retired. The response is a `201` that looks entirely correct.

`Supersession.theEngineerCannotRetireWhatTheyMayNotEdit` is the assertion, and `§209 AM-1` the
mutation row. It is also the honest domain rule independently of the gate: a cycle's sign-off does
not replace the project's environment guide, and one release's document does not replace another's.

**The kinds are free to differ.** A Confluence link replaced by an uploaded PDF, or better, a stale
upload replaced by an authored doc page, is the move plan §6n steers people toward — what is recorded
is that one document took over from another, not that a file was swapped.

**`write_once` is deliberately not checked on this path.** Superseding is precisely what a sign-off
record is *for*; the delete's own refusal says so and names it as the way forward.

---

## 4. Only an `ACTIVE` artifact may be replaced

Two refusals, both `400` on `errors.supersedes`, and neither is defensive:

- **Already superseded.** A second replacement would overwrite `superseded_by_id` and lose the middle
  of the chain, leaving the register asserting that v3 replaced v1 and that v2 replaced nothing.
- **Archived.** The row is already out of circulation, so replacing it answers a question nobody
  asked. The message names the way back rather than refusing flatly.

---

## 5. What the slice deliberately did NOT change, and why it took an argument

**A `SUPERSEDED` artifact stays deletable.** `ArtifactEndpointTest.theReplacementCannotBeDeleted` has
asserted since §124 that *"the OTHER end of the same edge is deletable — the guard is about the
direction of the pointer, not about superseding in general"*, and the tempting move was to overturn
it: superseding creates a record, plan §6n makes the trail the point, so refuse both ends.

It was considered and rejected. **Being replaced is not what makes a record permanent —
`write_once` is**, and that column's stated meaning is exactly this. Refusing both ends would make a
*mis-registered* link immortal the moment somebody corrected it, since the replacement cannot be
deleted either; and it would put the audit guarantee on the wrong column, where the day `write_once`
gets its writer there would be two overlapping answers to "why can this not be removed".

There is one asymmetry worth naming, and it is the cost of the model rather than a defect: a
superseded row **cannot be archived** (the 409), because the state is not this row's to leave. So the
way to take a replaced document out of view is to archive its *replacement*, which is what the
refusal sentence says.

**`write_once` itself gets no writer here**, at the user's direction. Nothing in the product creates
a sign-off yet, so a flag on the create would be an author declaring their own row undeletable — plan
§6g's argument, which is the same one that keeps `status` off the input. The delete's guard stays,
still unreachable, and the candidate is recorded below.

---

## 6. Three "blocked" notes, and the one that finally read as a pattern

`artifact.superseded_by_id` is mapped. The entity's header had argued the exclusion this way:

> mapping a field *this slice cannot set* would put an always-null accessor on the wire's other side
> and invite a caller to believe it means something

— and had already recorded the same reasoning expiring once, for `attachment_id` at §126 phase 3,
where the file calls it *"the fifth time in this project a 'blocked' note has outlived its blocker"*.
This is the third occurrence **inside one entity**, which is the point at which the note stops being a
coincidence: the header now says so, and `ddl-auto: validate` keeps the remaining two inert.

`supersededById` is on the wire because a status the reader cannot follow is a dead end. "Superseded"
without *by what* sends somebody scanning the register for a title they have to guess — `ui-rules`
§1's names-not-ids rule, where the id in question is the one the client resolves from rows it already
holds, because the same-scope rule guarantees the replacement is among them.

---

## 7. The test that had been standing in for a product that could not do it

`ArtifactEndpointTest` reached the superseded state with raw SQL:

```java
/** Points {@code replaced} at {@code replacement} — the self-FK nothing writes yet. */
private void setSupersededBy(UUID replaced, UUID replacement) { … }
```

That helper is **deleted**, and the two tests that used it drive the register's own act. The guard it
served was written before its writer existed — the same call `aSignOffRecordCannotBeDeleted` still
makes — and §209 is the round where one of the two fixtures stops being necessary.

`ArtifactCirculationTest` is the new context-free half: ~140 lines, no Spring context, no container,
no database, because the rule it pins is total over a string and over an enum. §208's
`LifecycleLadderTest` shape, written on the same day the class it tests was.

---

## 8. What the SPA already had right, and what it had exactly backwards

The register's table needed a **status label** it did not have (it lowercased the enum member), and
`lib/labels.ts` gains `artifactStatusLabel` — the `satisfies Record<Union, string>` rule, so a fourth
state is a compile error.

Two `ui-rules` decisions are worth recording because both went against an instinct:

- **Archive and Restore are two menu items, not one that flips.** §4 refuses the control that swaps
  its own meaning; the state decides which of the two is live. A superseded row disables both with
  the server's own 409 sentence.
- **No confirmation dialog on Archive**, which is a deliberate deviation from §4's *"every
  destructive action confirms"*. It destroys nothing, the row stays on screen with its new status,
  and the way back is the item directly beneath the one just pressed — §7 of the same skill refuses a
  blocking modal where the interruption is not the value. **Removal** is the destructive act, and it
  is still the one with no control anywhere in the SPA (see below).

The gate is `canOnScope('artifact:edit', row.scope === 'PLAN')` — what the *server* evaluates, so the
disabled state is the gate rather than a guess about it. Asking the plain key would enable the
control on every row for every engineer and turn each project-scope click into a 403, which is §31's
renders-then-rejects shape.

The **Replaces** picker reads the register at the scope the *form* is on rather than the one the page
is filtered to; the two differ the moment somebody viewing a plan registers at project scope. It
offers only `ACTIVE`, non-`inherited` rows — the same-scope rule stated where it can be enforced
rather than refused.

---

## Gates

Run on `shanks` (`~/bin/tcms-remote`), the Linux observer:

| Stack | Command | Result |
|---|---|---|
| `backend/` | `./gradlew build` | ✅ **2694 tests · 0 failures** (+31 against §208's 2663 — 15 unit, 16 endpoint) |
| `packages/` | `npm run build` · `npm run lint --workspaces` · `npm run test:run --workspaces` | ✅ · 0 warnings ×3 · **302** (119 / 78 / 105), unchanged |
| `frontend/` | `npm run build` · `npm run lint` · `npm run test:run` | ✅ · 0 warnings · **1349 across 100 files** (+8) |

Three repository-wide rules caught this slice and each one is worth naming, because none was reached
by any test I wrote:

- **`ContractConventionTest`** — a new object schema with a same-named record and no pairing.
- **`ErrorBodyPolicyTest`** — parameterizing `scopeProblem(detailText, fieldMessage)` to serve both
  403s handed the rule a *variable* where it reads the construction site for a literal sentence. The
  repair is two small builders rather than a widened rule; the file records why they are not folded.
- **`contractFixtures.test.ts`** and the §203 exchange harness, both on shanks and **neither
  locally**: an `api.put` double answering `{}`, and three object literals cast to `Artifact`.

---

## Verification

**Eleven rows, none run** (§65).

- `§209 AR-1`…`AR-6` in the browser queue — the picker fed at the form's scope, the named
  replacement, the qualifier read against the row from both sides, the superseded row's dead end, and
  the escalation `AR-6` drives from the client.
- `§209 AM-1`…`AM-5` in the mutation queue. `AM-1` is the ⚠️ row: deleting the same-scope comparison
  is a `201` that retires a document the caller may not touch. `AM-5` is the second kind of ⚠️ —
  expected **green**, recorded as the gap rather than as a pass, `§207 DQ-4`'s shape.

---

## Files

| File | What |
|---|---|
| `backend/…/documentation/ArtifactStatus.java` | The *"acts that do not exist yet"* sentence struck; the chosen-versus-derived asymmetry stated once, where both readers of the enum will find it. |
| `backend/…/documentation/Artifact.java` | `supersededById` **mapped** (§6); `supersededBy` and `circulate` added, plus a private `touch`; the unmapped-column count 3 → 2. |
| `backend/…/documentation/ArtifactInput.java` · `ArtifactFileInput.java` | `supersedes`, and `asRegistration()` carries it — so one validation path answers for both endpoints. |
| `backend/…/documentation/ArtifactStatusInput.java` | **New.** A `String`, and the reason is one more than `DocPageStatusInput`'s: the endpoint takes a proper subset of the enum. |
| `backend/…/documentation/ArtifactWriteService.java` | `setStatus`, `applySupersession`, `validateSupersedes`, `parseCirculation` (package-private, §184's call), two audit actions, two new `Outcome`s. |
| `backend/…/documentation/ArtifactController.java` | The `PUT …/status` endpoint; `statusScopeProblem` beside `scopeProblem` (see Gates); the 409 helper takes its title. |
| `backend/…/documentation/ArtifactFileController.java` | Two arms added to the outcome switch — the sealed interface collecting, exactly as its own comment predicted. |
| `backend/…/documentation/ArtifactView.java` | `supersededById` on the wire. |
| `backend/src/test/…/documentation/ArtifactCirculationTest.java` | **New.** Both parsers, both directions, no Spring context. |
| `backend/src/test/…/documentation/ArtifactEndpointTest.java` | Two `@Nested` classes (16 tests); `setSupersededBy`'s raw SQL **deleted**; a second plan of the same project, for the half one plan cannot express. |
| `backend/src/test/…/contract/ContractConventionTest.java` | The `ArtifactStatusInput` pairing. |
| `docs/specs/openapi.yaml` + both `schema` copies | The new operation, `ArtifactStatusInput`, `supersedes` ×2, `supersededById`, and the delete's description extended with the decision in §5. |
| `frontend/src/api/{types,queries/documentation,queries/index}.ts` | `ArtifactStatus` / `ArtifactCirculation`; `useSetArtifactStatus` (**not** `handled` — there is no form to land a message on). |
| `frontend/src/lib/labels.ts` | `artifactStatusLabel`. |
| `frontend/src/features/artifacts/ArtifactTable.tsx` | The row menu, the row-scope gate, the status chip naming its replacement. |
| `frontend/src/features/artifacts/ArtifactDialogs.tsx` | The **Replaces** picker, its own scope-keyed read, and the clear-on-scope-change. |
| `frontend/src/test/fixtures.ts` | `anArtifact` — the factory the §203 harness required. |
| `docs/verification/*.md` · `CLAUDE.md` · both orientation caches | Eleven queue rows; the three test counts. |

## Left open

- **`write_once` still has no writer**, and now it is the only column of `artifact` in that state that
  something *enforces*. The candidate is not the flag but the act: what creates a sign-off record, and
  whether that is a category, a plan transition, or a review outcome. Deferred at the user's direction
  and recorded here so the next reader does not re-derive the question.
- **The register still cannot delete anything through the product.** `DELETE
  /projects/{id}/artifacts/{artifactId}` has had no consumer in `src/` since §124, and §209 puts a ⋯
  menu on every row — which is where that item, its confirmation and its two 409 sentences belong.
  This slice deliberately did not add it: it is a *destructive* act and therefore §4's dialog, which
  is the shape §118 already built. The menu existing makes it cheaper than it was.
- **`GENERATED_REPORT` is still refused by both creates.** Unchanged by this slice and named again
  only because superseding makes it slightly more pointed: a pinned report is the one artifact kind
  whose natural update *is* a supersession.
- **Neither `@tcms/cli` nor `@tcms/mcp` wraps the artifact register at all** — not the creates, not
  the delete, and now not the status endpoint. Recorded as a fact rather than as a gap: the MCP
  surface is capped by the `Agent` role, and `artifact:edit` is not in that ceiling.
- **`ArtifactStatus` is answered; the other three status models are not.** §208's second opened item
  — a doc page's three statuses have no ladder at all — is untouched, and §207's `DQ-3` is still the
  row that matters.

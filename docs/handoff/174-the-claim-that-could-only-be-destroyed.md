# §174 — the claim that could only be destroyed to be corrected

**Two phases. Phase 1 is `backend/` + the contract + a migration; phase 2 is `frontend/`.** §173's
named next slice, and the third consecutive section about `doc_link` — §172 built the way to make
one, §173 made withdrawing one confirm at both ends, and this one makes the claim itself
correctable.

## The row that asked for it, and the fallback it had been living on

Browser row `M6-38` has said this since §115, while waiting for a screen that did not exist:

> **If the editor cannot change a link's relationship**, say so — the row is then a finding about
> the editor, not about the report, and the fallback is to record it as a new link and delete the
> old.

The fallback works and it is not free: it produces a **new row**, so `created_by` and `created_at`
become the person who noticed the mistake and the moment they noticed it. The record of who first
claimed that this case verifies that statement is destroyed by the act of correcting what the claim
says. §172 made that fallback *reachable* for the first time; this section makes it unnecessary.

## What the immutability was actually protecting

`DocLink` had **every column `updatable = false`**, and the class header states the policy in words:
*"Three factories, no setters."* The `toBlock` factory's javadoc gave the reason:

> A link is an assertion, and changing what it points at is two acts — withdrawing one claim and
> making another — which the API spells as a delete and a create. An editable link would let the
> record of "who said this verifies that" be rewritten in place with no trace.

**Every clause of that turns on the target coordinates, and it was applied to `relationship` by
being applied to everything.** The argument stays, in place, for the coordinates: a link that
silently changes which block or which file it points at keeps every count drawn off `doc_link`
agreeing with itself while pointing somewhere new, which is worse than a link that is withdrawn and
remade. `relationship` is not a coordinate — it is the *claim*, it is the field a person
realistically gets wrong, and getting it wrong is not cosmetic: the coverage matrix counts
`VERIFIES` and ignores the other two, so a mislabelled link is a **false coverage number**.

And the trace the argument was protecting is now *written* rather than protected by refusal. That is
the whole of `V48`.

## The migration answers only the half the database has

**Nothing in Postgres ever made the column immutable.** `doc_link.relationship` has been an ordinary
`NOT NULL TEXT` with a `CHECK` since `V1` — no trigger, no rule, no grant standing between a caller
and an `UPDATE`. The immutability was entirely in Java, so the Java change is where the decision
lives and `V48` exists for the question the database does have to answer: **who corrected it, and
when.**

Two nullable columns, no default, **no backfill**, and the last of those is the one worth reading:

> `NULL` means **never relabelled**, which is true of every row that exists today and is a different
> fact from "relabelled by the person who created it, at the moment they created it". Defaulting
> `updated_at` to `created_at` — the tempting one-liner — would state that second fact about every
> row that has ever existed, and no later reader could tell the fabricated values from the real ones.

`updated_by` carries no FK to `app_user`, for `created_by`'s reason one line above it in `V1`: an
actor can be a token or a job, and a user row can be removed while its traces stay.

## Both ends, and that is §173 being spent rather than repeated

`M6-38` asks only for the documentation end. Shipping only that would have re-created, the same
week, exactly the asymmetry §173 spent a slice removing from the *withdrawal* of these same rows —
one act behaving two ways depending on which screen reached it. So:

| | endpoint | gate |
|---|---|---|
| documentation page | `PATCH .../doc-pages/{pageId}/links/{linkId}` | `doc:edit` |
| case detail | `PATCH .../cases/{caseId}/external-links/{linkId}` | `case:edit` |

Two endpoints, **one `LinkRelabelInput` schema and one Java record behind both**, and each gated by
whoever owns the thing the link is a property of — the split `ExternalLinkService`'s header already
argues for the create and the delete.

## Five decisions in the endpoints

- **`PATCH` on the link, not a second `POST` on the block.** The create is keyed on (block, source)
  and answers `200` with the existing row when that pair is already linked, so re-posting a
  different relationship would have to either overwrite silently — making a create an update, on
  the endpoint whose documented contract is that a duplicate *"writes nothing"* — or keep ignoring
  it, which is what it does and is why this endpoint exists.
- **`relationship` is required here with no default**, and this is the one place it differs from the
  create. Absent on a `POST` means "the ordinary one" and resolves to `VERIFIES`; absent on a
  correction means the caller did not say what to correct it **to**, and defaulting there would have
  the server invent a claim on the field whose entire purpose is to keep *proves it* and *mentions
  it* apart.
- **`200` whether the value moved or already said this**, matching the create's idempotency — but a
  no-op writes **no audit row and raises no `links_changed` event**. A stamp for it would report a
  correction nobody made, and an audit row for it buries the ones that matter.
- **`DocLink.relabel` is a named act, not a setter.** `setRelationship` would be a bean accessor
  that says nothing about what changing the value means; this one takes the actor and the clock, so
  **there is no way to move the field without stamping who moved it**. The audit becomes a property
  of the type rather than a convention two services have to remember.
- **The event still fires**, because `case_doc_link` carries `relationship` (`V46`) and the coverage
  matrix reads it. A correction nobody propagates leaves the case end and every count still stating
  the claim that was just withdrawn.

**`ContractConventionTest` caught the unpaired schema on the first run** — §170's rule doing exactly
its job, one section after it was widened.

## Phase 2, and the control that was decorative until a test could not click it

The claim gets one shared control, `components/LinkRelationshipControl.tsx`, raised by both panels —
§173's move one slice later, and worth sharing twice over because the *reading* has to match too: a
screen that spells the three values its own way is how a coverage number stops meaning one thing.

**It was written as a `Chip` and that was a real defect, found the only way it could be.** MUI's
`Chip` renders a `div`; `disabled` on it becomes a class plus `pointer-events: none`. It *looks*
disabled, exposes no disabled state to a screen reader, and is indistinguishable from an enabled
control to anything that asks the DOM — so **the permission gate on it would have been decorative**,
and a `toBeDisabled()` assertion against a Viewer's chip passes against an enabled one. What
surfaced it was the other direction: a test could not click the **enabled** control either. It is a
`Button` now, which is also what it is — it opens a menu.

Two smaller calls: the no-op is **sent nowhere** (the server answers 200 and writes nothing, which
is right of the server and pointless from here — a round trip that cannot change anything still
invalidates a page), and on the case panel a **block** link's control is disabled rather than
absent, because that claim is corrected on the documentation page, which is the same split that
gives a block link no Remove there.

The cache rules are inherited unchanged and are still opposite: the block end **invalidates** (its
`links` are assembled inside the same GET) and the case end **patches without invalidating** (its
panel is a projection an event refreshes, so a refetch would put the old claim back).

## One thing built and not served, on purpose

`updated_at` / `updated_by` are written and **read by nothing** — no API field, no screen. That is a
deliberate absence rather than an oversight, and it is the shape this repo keeps finding, so it is
recorded: the audit trail is the point, the read that would use it is a link history no other link
surface has either, and inventing a field for it now would be a second thing to keep correct. The
`audit_event` row (`doc_link.relabelled`) is the readable half today.

## Changed

**Phase 1** — `V48__doc_link_relabelled.sql`; `DocLink` (the field, two columns, `relabel`, the
struck-through factory javadoc); `LinkRelabelInput`; `BlockLinkService.relabel` +
`ExternalLinkService.relabel` + an `Outcome.Relabelled` on each; both controllers; `openapi.yaml`
(two `patch` operations, one schema) and both `schema.d.ts` copies; `ContractConventionTest`'s
pairing entry; +7 backend tests across the two endpoint suites.

**Phase 2** — `components/LinkRelationshipControl.tsx`; `useRelabelBlockLink` +
`useRelabelCaseExternalLink`; both panels; +8 frontend tests.

## Gates

`./gradlew build` **SUCCESSFUL — 2538 tests, 516 containers, 0 failures** (+7). Frontend
`npm run test:run` **1251 across 96 files** (+8), build and lint clean. `packages` 229, unchanged,
with both `schema.d.ts` copies regenerated and byte-identical.

Browser rows `§174 RL-1`…`RL-5` and mutations `§174 RL-1`…`RL-3` **recorded and not run** (§65).
**Built and unverified.**

## The next slice

The browser queue is at the **bottom** at the user's direction (2026-08-12).

- **`TeamRef`** — three types, two of them a contract change first; §170 narrowed it to *should the
  three be one*;
- **the bound-and-undeclared query parameter**, §171's recorded gap;
- **Git traceability (M4)**, from §170's deletion of `IngestionRequest.branch`/`.commitSha`;
- **`OUTDATED_SPEC_CHANGED` / `UNLINKED`**, derivable from `case_doc_link`;
- the **read-only-archive** decision §159 deliberately did not take;
- **serving the relabel audit** — this slice's own recorded absence, if a link history is ever worth
  a screen;
- **run the browser queue** — ~698 rows, thirteen lanes, one sitting, scoped in
  `frontend-browser-checks.md`.

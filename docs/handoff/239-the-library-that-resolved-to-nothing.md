# §239 — The library that resolved to nothing

**C4**, slice 7 of the C run and the last of the eleven, in the audit's §3.6 order. Three phases:
the SPA, then the contract + backend + `packages` in one commit, then the docs.

**The one C item that deletes a feature.** One migration, `V63`, which contains no DDL and no DML.
No permission seeded, none moved. Catalogue stays at 55.

## What decided the slice: nothing ever resolved a shared step

The audit's C4 row says the picker's insert *"writes the entry's title as the action while the body
lives elsewhere"*, and `SharedStepPicker.tsx`'s own header explained where elsewhere was:

> The body lives in the library and is resolved when the case is read.

**It is not, and it never was.** `SharedStep.getBody()` had exactly **one** reader in the whole
backend — `SharedStepView`, the library's own list endpoint. Grep it. Not the case detail, not the
case list, not the execution screen, not `TestCaseDetailView`, not a report, not an export, and not
`test_case_version.snapshot`. `TestStepView` served the raw id and said so in its own javadoc:
*"served unresolved… the shared-step library endpoint is not built"* — itself stale, since
`SharedStepController` had existed since §94.

So the feature was not half-alive in the sense the audit meant. Its **reference half did nothing at
all**. Two places told the reader otherwise:

| Where | What it said |
|---|---|
| `CaseEditorPage.tsx` chip tooltip | "This step references the project's step library. Editing the library entry changes every case using it." |
| `CaseDetailPage.tsx` chip tooltip | "This step is shared. Editing it changes every case that uses it." |

Editing an entry changed **nothing, anywhere**. Both sentences were false on the day they were
written, and there is no error, no 500 and no empty panel to reveal it — the reader simply believes
a claim about propagation that no code implements.

## Therefore the backfill is empty, and that is the whole risk assessment

The decision on record reads *"migrate `sharedStepId` refs to copied text"*, and the handover
warned that a **title**-copy might lose content on screen today. Reading the read path first
inverted that:

- The step's visible text is `test_step.action`, and it always was. The picker put the entry's
  **title** there at insert time.
- Copying the **title** again is what already happened. An `UPDATE` doing it a second time would
  overwrite the actions of every author who has since edited a step by hand — replacing their text
  with the library title they edited away from.
- Copying the **body** would insert into live, authored test cases a block of text that has never
  once been rendered to anybody. A migration that adds steps to test cases is a content change
  nobody asked for, delivered by a release.

**The correct backfill of a pointer that resolved to nothing is no backfill.** Retiring the feature
costs no case a single character; what it costs is a chip whose tooltip was a lie.

## `V63` destroys nothing, and the DROP is owed

`data-migrations`' rule — no `DROP COLUMN` in the release that stops writing to the column — is
easy to satisfy here because there is nothing to move first. This slice is the release that stops
writing. So `V63` is **comments only**: two `COMMENT ON` statements stamping the table and the
column RETIRED, and a header carrying the reasoning above.

Left fully populated, unreachable from Java:

| | |
|---|---|
| `shared_step` | every entry anybody ever authored |
| `test_step.shared_step_id` | every reference any case ever made |

Owed to a later slice, **as its own decision**:

```sql
ALTER TABLE test_step DROP COLUMN shared_step_id;
DROP TABLE shared_step;
```

That statement is the destructive one — `shared_step.body` holds authored content this slice has
made unreachable but has not deleted, and nobody asked to lose it. Whoever writes it should say
whether the entries are exported first. It also finally retires `shared_step.created_idempotency_key`
(`V59`, §232 B4), the eighth of eight creates carrying the key and now the dead one.

Nothing enforces the emptiness in the meantime and nothing needs to: `hibernate.ddl-auto: validate`
requires every **mapped** thing to exist and says nothing about a column no entity mentions. And the
column **drains on its own** — a case saved after §239 writes `NULL` there, because the field left
the payload.

## `case_version.snapshot` was checked BEFORE anything was planned

It would have decided the question the other way. If a snapshot embedded the id rather than resolved
text, retiring the table would break every read that renders an old version, and C4 would have had
to become "make it real" instead.

A snapshot is a serialized `TestCaseDetailView`, so it embeds **both** the step's resolved `action`
text **and** its raw `sharedStepId`. The text is complete: a snapshot rendered today reads exactly
as it did before this slice. The id is decoration, and old snapshots keep carrying it forever, which
is correct — `test_case_version` is append-only and a past claim is not rewritten because the
product changed.

**One note for whoever builds the diff endpoint.** `TestStepView` no longer declares `sharedStepId`,
so an old snapshot deserialized back into that record meets an undeclared property. Nothing does
this today — `TestCaseVersion.getSnapshot()` has **no reader in `src/main` at all**, which is
precisely why none of this could break anything — but that endpoint must read snapshots tolerantly
rather than strictly. Recorded in `V63`'s header too, where a schema reader will meet it.

## What went, across four surfaces

**Contract.** `GET`/`POST /projects/{projectId}/shared-steps`, the `SharedStep` and
`SharedStepInput` schemas, and `sharedStepId` from **both** `TestStep` and `TestStepInput`. Both
`schema.d.ts` copies regenerated.

**Backend.** Nine `SharedStep*.java` files. `TestStep` stops mapping the column, `TestStepView`
stops serving it, and `CaseEdit` loses the entire `acceptableSharedStepIds` union — §64's
sibling-project check, its `referencedSharedStepIds` resolver, and the library lookups in both
`CaseCreateService` and `CaseUpdateService`. `CaseCloneService` stops copying the reference.

**SPA.** `SharedStepPicker.tsx` and its test, the "Insert shared step" button
(`CaseEditor.Steps.InsertSharedButton` — a locator, so it is named here for anything driving the
screen), both chips, the detach control, `sharedStepId` through all three of `caseSchema`'s
mappings, `useSharedSteps`, `useCreateSharedStep`, `keys.sharedSteps` and both type aliases.

**Packages.** `tcms_list_library` and `tcms library list` read one endpoint instead of two. The CLI
command **keeps the name `library`** — it is a locator, and renaming it to `condition` to announce
an absence would break every script using it in order to describe something that is gone.

## `/conditions` is now the only shared authoring object, and the contract has to say so

Seven descriptions in `openapi.yaml` cited `/shared-steps` as a live sibling — `POST /conditions`
opened by calling itself *"the same gate as `POST /shared-steps`, and for the same reason"*, and
`Condition.usageCount` explained its own derivation by pointing at `SharedStep.usageCount`. Each is
now self-contained, and `/conditions` says the thing that matters out loud: **do not read this
endpoint as one of a pair.** It is the one place a case attaches an entry by id and an edit really
does reach every case using it — the property shared steps advertised and never had.

The same rewrite ran through eleven `{@link SharedStep*}` references in the backend's `condition`
sibling, which had been explaining themselves by pointing at classes this slice deletes.

## `IdempotencyKeyCapTest` goes DOWN for the first time

Ten declarations become **nine**. `SharedStepService` was the tenth (§232 B4) and its copy of the
cap was deleted **by deleting the service**.

That is the argument that test exists to make, arriving from the direction nobody had seen. Its
header defends per-service copies over a shared constant on the grounds that a foundation module
must not become a grab bag. The cheaper argument is now available: **a shared constant would have
outlived the feature that introduced it**, and somebody would later have had to work out whether
anything still used it. A per-service copy is deleted by deleting the service. Written into the
field's javadoc.

## Numbers, and one that did not reconcile

Gates on shanks:

| | Arrival (§238) | §239 | Delta |
|---|---|---|---|
| backend | 3070 / 0 / 0 | **3024 / 0 / 0** | −46 |
| frontend | 1547 / 116 files | **1540 / 115 files** | −7, −1 file |
| packages | 336 | **336** | — |

−46 backend is exactly `SharedStepEndpointTest` (33) + `CaseSharedStepReferenceTest` (13). −7
frontend is exactly `SharedStepPicker.test.tsx`, confirmed against a static `it(` count across both
trees (1158 → 1151) so that no test vanished silently under cover of a deletion that was expected
to remove some.

**Two arrival figures in circulation were wrong and neither was measured.** The handover named
backend 3038 / frontend 1547; §238's own row records **3070** and quotes **1552**. The backend
number reconciles perfectly against §238's 3070, so 3038 was §236's figure carried forward. The
frontend one goes the other way: 1547 is right (it is §236's, and it matches this slice's own
arrival-minus-seven), and §238's 1552 was quoted from `CLAUDE.md` rather than measured — §238 was
backend-only and did not run the suite. **A test count copied from a document is not a
measurement**, and this run had two of them disagreeing in opposite directions.

## Built and unverified

`SS` rows in both verification queues, per §65. None run.

## The C run is complete

Eleven of eleven. Ten were *finish*; this was the one *retire*, and it is the only slice in the run
that removed a user-visible affordance.

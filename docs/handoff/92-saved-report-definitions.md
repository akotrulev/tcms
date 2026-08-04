# §92 — saved report definitions: the row that is one person's, and the delete a foreign key would have refused

**R5 of `docs/plans/2026-08-04-m2.5-reporting-slice-plan.md`.** Three phases, three commits. It gives
`report_definition` — a `V1` table — its first writer, serves the `GET` that has been declared and
unimplemented since M0, and makes a saved report **runnable** with `definitionId` on generate.

**`V28__report_artifact_definition_on_delete.sql`**, contract **+2 operations** and a changed
generate body, **no new permission key**, **no new table**, policy count unchanged at **59**,
`V29` next free.

---

## 0. This slice is §92 and its first two commits say §91

A concurrent strand claimed §91 (`docs/handoff/91-the-way-back-to-the-picker.md`, the rail work)
**while this one was in flight** — it appeared on disk between this slice's phase 1 and phase 3.
Phases 1 and 2 had already been committed under `§91 phase 1` / `§91 phase 2`, and rewriting two
published commits with another session live in the same tree is the operation
`docs/plans/2026-07-31-parallel-execution-rework.md` §0 blames for three silent losses of committed
work. So the commits keep their subjects, everything else — this file, the code comments, the queue
sections — says **§92**, and this paragraph is the reconciliation. References resolve by handoff
filename, so nothing else in the repo is ambiguous.

The wider lesson is §10's, unchanged and paid again: **the § number is not reserved until the
handoff file exists**. Writing the file first — even empty — would have cost nothing.

---

## 1. What R5 is, in one line each

| | |
|---|---|
| `GET /projects/{id}/reports` | **The caller's own** saved reports, newest first. Declared since M0, served by nothing until now. |
| `POST /projects/{id}/reports` | Save one: type, name, parameters. |
| `DELETE /projects/{id}/reports/{definitionId}` | Hard delete. The reports it produced survive it. |
| `definitionId` on `POST …/generate` | The definition supplies the **type** and the **base** for the parameters; the request overrides. |

---

## 2. The finding is that the row is one person's, and every query has to say so

`V1` gave `report_definition` both an `owner_id` **and** a `shared_with_team_id`. Those two columns
only make sense together: if a definition were readable by everybody on the project, sharing it with
a team would mean nothing. So the table's own design says a saved report is **private until shared**
— and sharing is the one part of this surface that would want a permission key the catalogue does not
have (§67's argument against `evidence:read`, §79's, §80's, §82's). `shared_with_team_id` therefore
stays unmapped and v1 ships **owner-only** definitions.

That is a decision with a consequence the contract now states outright: **two people asking for the
same project's saved reports receive different lists** — §84's sentence about the team board,
arrived at from a different direction.

Where it is enforced is the part worth reading. `ReportDefinitionRepository` has **no method that
can find a definition the caller does not own**: not `findById`, not `findByIdAndProjectId`. The
alternative — resolve the row, then check the owner in the service — is the shape that passes an
access-control test because the check *ran*, one call site over, and it is the shape §87 already had
to guard against on `report_artifact`.

Two answers follow from it and both are deliberate:

- **Somebody else's definition is a 404, never a 403.** The caller cannot list it, so an answer that
  distinguishes "not yours" from "no such thing" turns the delete route into an oracle over other
  people's saved reports — §6t at row level.
- **On generate, the same row is a 400 field error and not a 404.** The caller can see the project
  and named something in it; the id is data in a body rather than the thing being addressed. Gone and
  somebody-else's are still one answer.

---

## 3. `report:view` for all three, including the two writes — and it is the split §87 built roles for

No new key, for the fifth slice running. The choice was between the two keys `V3` already seeds, and
`report:view` wins on the argument the row itself makes: **the definition belongs to its creator and
is visible to nobody else, so saving one writes to the caller's own workspace and not to project
content.** Nothing leaves the server; nothing another person can see changes.

The other direction is what makes it more than a preference. Requiring `report:export` would mean the
persona §6e's two-key split exists for — *may read reports on screen, may not carry them off* — could
not **save the selection it is allowed to read**, and would have to retype it every release. That
persona is not hypothetical here: §87 had to create it as a custom test role because **no built-in
lacks either key**, and this slice's gate assertions are only non-vacuous because that fixture
exists. `Definitions.viewSaves` (201) and `Regenerating.savingIsNotGenerating` (403) are the pair —
saving is allowed, running is not, on the same person, in the same project.

---

## 4. The migration the plan said this slice would not need

R5's plan line reads *"**Migration.** None — `report_definition` is `V1`; `V27` must still be head."*
It is wrong, and reading two foreign keys is what found it rather than running the delete:

```
report_job.definition_id       REFERENCES report_definition(id) ON DELETE SET NULL   (V27)
report_artifact.definition_id  REFERENCES report_definition(id)                      (V1)
```

`V27` chose `SET NULL` and wrote down why — *"deleting a saved report must not delete the history of
what it produced"*. `V1` predates that reasoning and left the default, `NO ACTION`. `ReportJobRunner`
copies the job's `definition_id` onto **every artifact it writes**, so from the moment phase 2 let a
job carry a definition, deleting a **used** saved report would raise a foreign-key violation from
inside the delete transaction: a 500 on a route whose contract says 204, and **only** for the
definitions somebody actually used — which is to say only for the ones anybody cares about.

The application-side alternative (null the artifacts first, in the service) holds the invariant in
one code path and leaves every other path — a retention reaper, an operator's `DELETE` — able to hit
the violation. `V28` puts the rule where its sibling already keeps it. No table, no policy:
`SchemaConventionTest` stays at **59**.

**Its test is written so a green run cannot be an accident**: `artifactSurvivesTheDefinition` asserts
the artifact carries the definition id **before** the delete, so "204" cannot mean "nothing
referenced it".

---

## 5. The merge is shallow, and that is the decision

`definitionId` + `parameters` is *the saved selection with this request written over it, key by key
at the top level*. A deep merge is the tempting default and it has no basis: `parameters` is untyped
by design — each report takes different ones — so nothing tells a deep merge whether a nested object
is a **set of filters to combine** or **one value to replace**, and it would answer differently per
report. Half-overriding a saved selection is invisible in the output: the report renders, the numbers
are wrong, and nothing on the document says which half won.

Two adjacent decisions:

- **The stored `parameters` is the merged result**, not what the caller typed. It is what
  `report_artifact.filter_snapshot` is frozen from, and what has to be reproducible is what the
  report was **computed over** (`V1`: *"a report you cannot reproduce is a report nobody trusts"*).
- **A `type` that contradicts the definition is refused, not resolved.** A client holding a stale
  copy of a definition it has since changed would otherwise receive a report of something it did not
  ask for, rendered perfectly. An **absent** `type` is no longer a mistake when a definition is
  named, so the contract's `required` drops to `[format]` with the rule in prose — OpenAPI can only
  express the conditional as a `oneOf` whose two arms differ by one optional field.

The definition's type is re-checked **at generate time as well as at save time**, which looks
redundant and is not: a definition outlives the release it was saved in, so a report retired from a
deployment leaves saved rows naming it. Without the second check the job is accepted and dies in the
worker carrying the registry's internal sentence instead of the catalogue.

And the mirror image: **`parameters` are not validated against the report type at save time.** A
parameter that becomes required in a later release would otherwise make an existing saved report
unsaveable, while the generate it feeds already refuses a bad selection with that report's own
sentence, at the moment somebody is waiting for the answer.

---

## 6. `ReportTypes` — one refusal, because there are now two endpoints that make it

"This server has no report called X. It can produce: …" is now asked by save and by generate. Two
copies would drift in §58's E1 way — the sentence gets edited on one endpoint when a report is added
and the other goes on naming a shorter catalogue — so both ask one component, which asks the
`ReportComputations` registry rather than a literal list. A report added in R6 becomes both
requestable and saveable **by existing**.

---

## 7. What was checked rather than assumed

- **Route collision.** `ReportDefinitionController` and `ReportController` are both mapped under
  `/projects/{projectId}/reports`. Not a collision: the pipeline's routes are literal segments
  (`/generate`, `/jobs/…`, `/artifacts/…`) and Spring resolves a literal ahead of a template, while
  the ids are UUIDs so no definition can be named `generate`.
- **The module declaration did not move.** `reporting` still declares
  `{"tenancy", "project", "identity"}`: this slice reads `ProjectService` and `PeopleDirectory`,
  both `project`, and adds nothing.
- **Names are not unique**, and there is no constraint to make them so — the rows are one person's
  and a collision is theirs to notice. A uniqueness index would be a migration adding a rule §6e does
  not ask for.
- **A definition saves *what to compute*, never how to render it.** `report_definition` has no format
  column; the format is chosen per download. The same saved report is Markdown today and HTML next
  release without becoming a different saved report.

---

## 8. Found and not fixed

- **§90's finding is untouched**: the on-screen report view is served through the artifact route and
  therefore costs `report:export`, so the very persona this slice lets *save* a report still cannot
  *read one on screen*. The fix is the server's (serve the `JSON` artifact under `report:view`, or
  give the view its own route) and it is a slice of its own, not a rider on this one. Browser rows 31
  and 44 read together are the whole state of that split.
- **`EvidencePanel`'s anchor** (§89's) — every evidence download in live mode is a 401. Unswept.
- **No job list** (§89's): nothing lists a project's generation jobs, so the Reports screen still
  shows only what the current tab created. R5 does not change that — a definition is not a job.
- **`schedule_cron` is still written by nothing.** Scheduled reports are §6e phase 2, and the factory
  deliberately does not take the column, so a scheduler cannot arrive by accident and quietly
  attribute scheduled runs to whoever saved the definition.
- **§86/§87's three open rows** — the unasserted scheduler loops, the unobservable bytes-first
  ordering, and `case_health`'s missing index on `failing_repeatedly`.

---

## 9. Gate

**Phase 1:** `./gradlew build` → **BUILD SUCCESSFUL, 1641 tests across 321 classes, 0 failures, 0
skipped** (1628 → 1641), counted from the XML. Frontend `npm run build` clean, lint 0 after
`gen:api`.

**Phase 2:** `./gradlew build` → **BUILD SUCCESSFUL, 1649 tests across 322 classes, 0 failures, 0
skipped** (1641 → 1649).

**All 21 new tests passed on their first run**, stated plainly rather than taken as evidence they can
fail — §89's poll test had also passed first time, in a form that could not fail. What makes these
falsifiable is recorded per row in the mutation queue, and three of them are only non-vacuous because
of §87's custom roles.

**The frontend gate is RED and not on this slice's account.** `npm run build` fails on
`src/app/AppShell.test.tsx` — an **untracked** file belonging to the concurrent strand of §0 — with
two `TS6133 declared but never read` errors. No error is in any file this slice touches, `npm run
lint` is 0, and nothing here was committed into that file. It is recorded rather than worked around:
the next session should expect it to be gone once that strand commits.

**Zero mutations run inline** (§65, restated 2026-08-02). **10 backend mutation rows** and **5 browser
rows (40–44)** queued; rows 43 and 44 are live-only, because mock mode signs in as one persona and
therefore cannot show an owner bound at all — which is stated in the mock's own header rather than
left to be discovered.

**Built and unverified.**

---

## 10. What is left in M2.5

R5 was the last of the two remaining backend slices before the aggregate report. What remains, in the
plan's order: **R6** (the aggregate execution report — selection, aggregation policy, headline
totals, and the cross-check test decision D calls the slice's most valuable artifact), **R7** (the
inconsistency callout and the manual/automated split), **R8** (version divergence). F1 and F2 have
shipped; the frontend half R5 now makes possible — a Save control on the Reports screen's definitions
panel, and regenerate-from-a-definition — is **not built** and is named here rather than assumed.

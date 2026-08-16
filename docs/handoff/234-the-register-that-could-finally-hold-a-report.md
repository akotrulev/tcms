# §234 — The register that could finally hold a report, and the promise it could not make

**Date:** 2026-08-16 · **Scope:** C6 + C7 + C10 of
`docs/audits/2026-08-15-flow-review-and-improvements.md` — slice 2 of the C run, in the §3.6 order
(slice 1 is §233). Contract, `backend/documentation`, `frontend/features/{artifacts,reports}`,
`packages/{client,mcp}`. **No migration, no permission added or moved, no new key** — every column
this slice writes has been in `V1` since M0.

## The shape of the whole slice

Three decisions that looked unrelated on the audit's list are one thing: **`artifact` had two
columns nothing could write, and the register had no surface for an agent.** `V1` declared
`report_artifact_id` and `write_once` in M0 and the product has been enforcing and rendering
around both ever since — a delete guard since §126, an edit guard since §229, an
`ArtifactKind.GENERATED_REPORT` the register renders and the create refused. Closing them
together is why the MCP surface could arrive complete rather than as a delete arm with no read.

## C7 — a guarantee that was enforced for three slices and could not be made

`write_once` means *corrections supersede, never overwrite*. The delete has honoured it since §126
and the edit since §229, and **nothing in the product could set it** — both tests reached it with
`UPDATE artifact SET write_once = true`. That fixture was the right call when the guard was
written (§126's own argument: a refusal guesses at nothing, it is the column's documented meaning
enforced) and this is the round where it stops being a stand-in. Both tests now register a
sign-off through the endpoint a lead uses, and the two SQL helpers are deleted.

Three properties are the design, not caution:

- **`updatable = false`.** A promise that can be made later can be made about somebody else's row,
  to freeze a document they are still correcting; one that can be taken back is not a promise. It
  is decided at registration and there is no field for it on `ArtifactEditInput` — two layers say
  no, and `anEditCannotSetIt` drives a body naming it anyway.
- **It is on the wire.** This is the §31 half and the reason it is worth a contract change: both
  refusals were `409`s **the client could not predict**, so Correct… and Remove… enabled on every
  sign-off row and the register answered after the correction had been typed. They are greyed now,
  with the reason as a line *inside* the menu — MUI raises no hover events on a disabled
  `MenuItem`, which this file had already learned for the superseded case.
- **Archive and supersede are still allowed**, and both are asserted. Circulation is not content;
  and a write-once row that could not be superseded would be a record nobody could ever correct,
  which is the opposite of what the flag is for.

## C6 — the request names the job, the row keeps the artifact

`reporting` has produced artifacts since §87. The register has rendered any kind of row since §57.
Nothing joined them, so *"these were the numbers we signed the cycle off on"* was a claim the
product could generate, could register, and could not do both.

**The one design point to carry: `report_artifact` has no `project_id`.** `V1` scoped it by
company and definition, `ReportArtifact`'s header records that as designed, and the reporting
module's own download route already proves an artifact's project *through the job that produced
it*. So an artifact id cannot be gated against the project in the path and a job id can — the
request takes `reportJobId`, and what is stored is the artifact, because that row is immutable and
therefore answers the same bytes for ever. Regenerating makes a new job and a new artifact and
leaves the register entry alone; that is what pinning means.

`ReportFacts` is the reader — `documentation` may not depend on `reporting` (plan §3), so it is
`PlanFacts`' pattern, second instance here and fifth in the codebase. It keys on
`artifact_id IS NOT NULL` rather than `status = 'COMPLETED'`: the two agree in the designed system
and the column is the one that is actually false for the row the refusal exists to catch.

**`ArtifactView.url` now has three arms and the third is gated as a report** (`report:export`),
not as an artifact. Pinning a report records that it was produced; it does not re-license carrying
it off, so a reader holding `artifact:read` alone sees the entry and is refused the bytes.

On the SPA, **"Save to register" reuses `RegisterArtifactDialog`** rather than carrying a small
form of its own. That file's header already refuses a separate Upload dialog over a list of shared
concerns — scope, the plan requirement, the category list, both length limits, the
403-as-a-field-error mapping — and every one applies unchanged. Pinning differs in exactly one
respect, *what the payload is*, and that is the one thing the user never chooses: pressing the
button is the choice. So the dialog takes `pinnedReport`, fixes the kind and hides the picker.

## C10 — the register's MCP surface, read and write together

`tcms_list_artifacts` and `tcms_write_artifact` (register / edit / status / delete, the delete
behind §210's consent prompt). **21 tools → 23.**

`writes.ts`' §211 note said `artifact:delete` had no tool because *artifacts* had none, and that
adding a delete alone would be a tool whose id can only come from somewhere else. That condition
was **satisfied rather than waived**: the read serves the ids the delete takes. The note is struck
through in place, not deleted.

Two shortfalls came with it and both are the multipart wall again, not new decisions. **`FILE`
cannot be registered here** — a tool call carries no bytes, the same limit `tcms_record_result`
meets at evidence — so `register` derives its kind from the three payloads it *can* name rather
than taking a `kind` argument that could disagree with them. And the **delete is hard for a
`FILE`**; it is offered anyway, because unlike evidence this surface can also create the row it
removes, and archiving sits beside it as the reversible act.

## Two things the plan did not contain, both found by checks

**`PATCH …/artifacts/{artifactId}` declares no `If-Match`.** `writeConformance.test.ts` refused
the edit arm for it. It cannot simply be added: the endpoint would need a version to compare and
`Artifact` carries no `lockVersion` on the wire at all, so closing it is a contract change on both
the response and the request. Recorded in `CREATE_WITHOUT_A_KEY` with what it costs — two callers
correcting one row race and the later write wins silently, bounded by what the edit can reach
(three metadata fields, no payload, no scope).

**The MCP capability summary lost its most important section, silently.** Adding two tools took
the "not available to this credential — ask for this permission" list to 1848 bytes, over the
host's 2KB ceiling with the header, and `fit` dropped it whole. That section was *always* going to
be the one sacrificed as the registry grew — it is last-but-one in the drop order — and the
credential it is sacrificed for is the one holding almost nothing, which is exactly the reader it
exists for. A dropped section is indistinguishable from a credential with nothing hidden. It
degrades to the permission keys alone now (the half `instructions.ts` already argues is the
actionable one), with a test driving the smallest possible grant.

## Gates (on shanks)

Backend `./gradlew build` **BUILD SUCCESSFUL** (7m59s), frontend **1508 / 111** with `npm run
build` and 0 lint warnings, packages **336** (78 / 130 / 128). Eleven new backend tests, five new
frontend ones plus a new `PinReportButton.test.tsx`, and four new MCP conformance invocations.

## §65 queue

- `§234 AR-1` — register a report from `ReportViewPage`, confirm the row appears in the register
  with the report's title, and follow its link to the bytes.
- `§234 AR-2` — register a sign-off (`writeOnce`), then confirm Correct… and Remove… are greyed
  with the reason and that Archive is not.
- `§234 AR-3` — supersede that sign-off and confirm both rows are present and correctly marked.
- `§234 AR-4` — pin a report at PLAN scope and confirm it is inherited nowhere.
- **Backend mutation:** `§234 PM-1` (a `GENERATED_REPORT` row carries `report_artifact_id` and no
  other payload column), `§234 PM-2` (`write_once` is set at registration and unchanged by an
  edit). Neither is run.

## Not done

- **C3, C5 + C9, C2, C1, C4** — the remaining five C items, none started. C3 (docs search) is
  next in the §3.6 order.
- `Artifact.lockVersion` is still not on the wire, so the artifact edit has no optimistic
  concurrency on any surface — see the conformance entry above.
- The MCP `register` arm cannot upload a `FILE`, by the multipart limit.

# §177 — the milestone two columns had been waiting for, and the fallback that goes the other way

**Two phases: `backend/` + contract + both `schema.d.ts` copies, then `frontend/` + `packages/`. No
migration, no new table, no new column** — the columns have existed since `V1` and this is the slice
that gave them a writer.

§176's named next slice, taken from the top of its list: **Git traceability (M4)**, plan §3 phase 2 —
*"associate a run with commit SHA / branch"*. §6q's table lists `commitSha` / `branch` beside
`releaseVersion` as the field saying *which code ran*, and the ERD annotates both columns with the
milestone.

## What §170 left, and why the repair had to be one commit

§170 found `IngestionRequest` binding a `branch` and a `commitSha` that were **declared nowhere on
`IngestionInput` and read by nothing** — not by `IngestionService`, not by `IngestionEdit`, and
`TestRun` mapped neither of the columns `V1` created for them. A CI job that set them got a 2xx and a
run with no branch on it: *the worst of the three available behaviours, since refusing would have been
honest and storing would have been useful.* It deleted the two components, kept the columns, and
named the shape of the repair in advance — declare the properties, map the columns, pass them
through, **in one commit, at which point they are read**.

That prescription is the load-bearing part and it is worth saying why. Each third of it is
individually harmless and useless: a declared property nothing binds is unreachable, a bound property
nothing stores is §170's defect again, and a mapped column nothing writes is §154's. **Any two of the
three shipped without the last one reproduce the defect under this slice's name.** The mutation queue
is built around exactly that: `GT-M1` and `GT-M2` mutate the value's arrival in the *row* and in the
*response* and must redden **different** tests, because a field echoed back out of the request it
came in on is indistinguishable from a field that was stored.

## Three arguments re-read, and only one of them had expired

This repo's last five sections found a candidate whose stated obstacle had quietly become false, so
each argument this slice touched was re-read rather than trusted.

**`RunWriteRequest`'s refusal had expired, in the direction it predicted.** Its javadoc said
`commit_sha` and `branch` *"arrive with CI ingestion in M3, and accepting them here would be a field
read and dropped"* — correct while nothing stored the columns, and quietly ironic: the field
read-and-dropped was already happening, one module over, in the request this paragraph was written to
distinguish itself from. Struck through and superseded.

**A person may set them, and the alternative was considered rather than skipped.** Restricting them
to `IngestionInput` has a real argument — a git coordinate is a machine's observation, and
`source = MANUAL` means no machine observed anything. It loses on two counts: **there is no run edit
endpoint**, so a value not accepted at create can never be recorded at all, and *"a machine may say
what code ran and a person may not"* is a rule with no failure behind it. A lead running a regression
pass against `release/4.3` is exactly the caller who knows.

**`api`'s charter refused the shared regex, in as many words.** `documentation.ExternalLinkService`
has validated a git object name since §169; this makes it a second question, and the two modules may
not import each other (plan §3). The module that could hold it says *"a helper two modules happen to
share does not qualify — that is a copy, or an event"* — a regex is not a wire shape, and putting it
in the one module that declares no dependencies at all would widen it into the grab bag §125 refused.
So it is a copy, on `RunEdit.MAX_TAG`'s terms, and `GitObjectNameRuleTest` is
`IdempotencyKeyCapTest`'s shape applied to it: **both declarations compared as source text**, not as
compiled patterns, because `[0-9a-fA-F]{7,40}` and `(?i)[0-9a-f]{7,40}` accept the same strings and
are not the same rule to the next reader who edits one of them.

## THE DECISION: the clone inherits neither, and it is the one fallback that goes the other way

`RunCloneService.derive` builds a `RunWriteRequest` from the source run, and every override falls
back to the source — release, build, configuration — *"because a re-run is against the same release
until the caller says otherwise."* Consistency says the git coordinate joins them. It does not, and
the reason is arithmetic rather than taste:

**a release version is what you INTEND to test; a commit sha is what RAN.** A fresh clone has run
nothing. Inheriting the sha ships a run claiming a commit it never tested — and with no run edit
endpoint the only correction is deleting the run. Left null, the clone says it does not know what code
this pass covers, which is *true* of a manual re-run days later. So `TestRunCloneInput` declares no
override for either: there is nothing to override.

This is a **deliberate absence**, which is the kind a later consistency cleanup removes on sight, so
it is pinned from three sides — the schema comment, the `derive` comment, and
`RunCloneEndpointTest.theGitCoordinateIsNotInherited`, which also asserts the *source* keeps its own
(a clone is not a move).

## Refuse or store, never 2xx-and-discard — and on ingestion that costs the report

A malformed `commitSha` is a **field error**, on both paths, from the one rule in `RunEdit` — the
ingestion endpoint inherits it because the run it opens goes through `RunCreateService` like every
other, rather than keeping a second answer to what a commit id is.

On ingestion that means a bad value **refuses the whole post**, losing that build's report. Stated
where the decision is, because it is a real cost and the cheaper-looking option is the one §170
deleted: the three available behaviours are refuse, store, and 2xx-with-the-value-dropped, and only
the third is dishonest. It is also cheap in practice — a value out of `git rev-parse` is malformed on
the first build or never. Verified from the outside: the refusal leaves **no `ingestion_job` row and
no run**, because `RunEdit` refuses before `RunCreateService` burns a display key (§29's rule) and the
job row is written last.

Two smaller calls, both recorded at their site:

- **Trimmed, not lower-cased.** Two runs on one commit whose shas differ in case would not group
  together, and nothing groups by commit today. It stays uncased because
  `doc_link.external_commit_sha` stores what it was handed too: one value with two normalizations is
  the worse defect, and the day something *does* compare them they must already agree.
- **`branch` is length-bounded and otherwise unchecked**, at 255 rather than `MAX_RELEASE`'s 100.
  `dependabot/gradle/org.springframework.boot-3.5.6` is ordinary and already past 50. What a valid ref
  name is belongs to the team's git, and unlike a commit id, a wrong branch name is wrong in a way its
  reader can see.

## The read side, and where it deliberately is not

A value nothing renders is §172's family, so the read shipped in the same slice.

**The run detail header carries a third metadata line** — `release/4.3 at 4f9c2b1` — and it is **not
nested inside the release** the way `build` is. That nesting is an existing shape worth not copying: a
run with a build and no release renders neither today, and a CI run knows its commit whether or not
anybody set a release version, so a coordinate that only appeared beside a release would be invisible
on exactly the runs that always have one.

`describeRunCode` is in `lib/links.ts` rather than in the page, and the reason is the phrasing:
**`at <sha>` is `describeLinkTarget`'s wording**, and a run's commit and a doc link's pin are the same
kind of fact read on adjacent screens — a case's Traceability panel names a pinned file, and the run
that tested that case names a pinned build. It returns **null** when neither is set, so the caller
renders nothing rather than a permanently empty labelled field on the manual passes that are most
runs.

**The run list shows neither, on purpose**, and the browser queue carries a row saying so
(`§177 GT-5`) rather than leaving the next reader to file it. The value *is* on the wire for the list —
`TestRunView` serves it because `TestRunDetailView` is built from that record, so a field absent from
the list view cannot reach the screen that renders it — and a table column of shas is noise in the
cell the release already occupies.

**`packages/` got both clients**, which is most of why the CLI flags exist at all: `tcms run create
--commit "$(git rev-parse HEAD)"` is the pipeline path, the browser form is the other one, and an
`TestRunInput` property two of the three clients cannot set is the §172 shape from the other end. The
MCP tool describes the bound in its schema rather than leaving an agent to learn it from a 400, and
its `clone` arm accepts neither.

## Changed

**Phase 1** — `TestRun` (two mapped columns, factory, getters); `RunEdit` (two components, `SHA`,
`MAX_BRANCH`, two validations); `RunWriteRequest` (the struck-through paragraph);
`IngestionRequest` (two components restored, §170's comment rewritten into the arrival);
`IngestionService.openRun`; `RunCloneService.derive`; `TestRunView` · `TestRunDetailView` ·
`RunService.view`; `openapi.yaml` (`TestRun`, `TestRunInput`, `IngestionInput`, and the comment on
`TestRunCloneInput`) and both `schema.d.ts` copies; `GitObjectNameRuleTest` (new); +14 backend tests
across three endpoint suites.

**Phase 2** — `lib/links.ts` (`describeRunCode`); `RunDetailPage`; `RunCreateDialog` (two fields,
`MAX_BRANCH`, the schema and the `FIELDS` list); four run fixtures in `*.test.tsx`;
`packages/cli/src/writes.ts` (`--branch` / `--commit`); `packages/mcp/src/writes.ts`; +8 frontend
tests.

## Gates

**Run on `shanks`** per `CLAUDE.md`'s remote-gate section, which is where they belong and where the
first two of this session's runs did not start.

Backend `./gradlew build` on `shanks` **SUCCESSFUL in 7m10s — 2553 tests across 518 result classes,
0 failures, 0 errors** (+14 tests, +2 result classes: `GitObjectNameRuleTest` and
`RunCreateEndpointTest$GitTraceability`). Frontend `npm run test:run` on `shanks` **1259 across 96 files** (+8), with
`npm run build` and `npm run lint` clean locally. `packages` **229 tests, build and lint clean**, both
`schema.d.ts` copies regenerated.

Browser rows `§177 GT-1`…`GT-5` and mutations `§177 GT-M1`…`GT-M5` **recorded and not run** (§65 —
and this slice is the ordinary case for that policy rather than §170/§171/§176's exception, which
turned on those slices shipping a test method and nothing else). **Built and unverified.**

## The next slice

§176's list, minus the entry this one took. The browser queue is at the **bottom** at the user's
direction (2026-08-12).

- **`OUTDATED_SPEC_CHANGED` / `UNLINKED`**, derivable from `case_doc_link`;
- the **read-only-archive** decision §159 deliberately did not take;
- **serving the relabel audit** — §174's recorded absence, if a link history is ever worth a screen;
- **`CurrentUser.teams` has no reader** — §175 kept the array on a permission argument and nothing
  renders it; the question is which screen owes the person their own teams, not whether to delete it;
- **a run has no edit endpoint at all**, which this slice leaned on twice and did not build. It is a
  real gap rather than a rhetorical one: a run's name, release, build and now its git coordinate are
  set once and never correctable, and `PUT /runs/{id}/status` is the only write there is. Worth a
  slice on its own terms, and the two arguments above become weaker the day it exists — which is the
  kind of expiry the last five sections were spent finding;
- **the run list does not show the coordinate** (`§177 GT-5`) — a decision, not a gap, listed so the
  reason survives with it;
- **run the browser queue** — ~705 rows, thirteen lanes, one sitting, scoped in
  `frontend-browser-checks.md`.

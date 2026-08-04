# AREA-004 — Test Execution

**Created:** 2026-08-03 · **Owned by:** `be-shared-business-documentation`
**Derived from:** code by `be-documenter` @ `93444b2` on 2026-08-03

Covers the `execution` module: test plans, test runs and their executions (per-case results),
evidence (screenshots/attachments), and case/suite version history.

## What this area is for

The "doing the testing" half of the product: assembling a plan of cases to run, spinning up a
run of that plan, recording pass/fail results per case, attaching evidence, and closing the
run once it's done.

## Actors

- **Test Lead / Company Admin / Instance Admin** — full control, including closing/reopening
  runs and amending recorded results.
- **Test Engineer** — creates plans and runs, executes, records results, attaches evidence; may
  not close a run or amend someone else's recorded result.
- **Automation Service** — may create runs and submit results and evidence (CI ingestion), per
  the roles spec; not independently re-verified this session beyond the permission table.
- **Viewer** — read-only.

## Capabilities

1. **Create a plan** scoping a set of cases, with entry/exit criteria — `POST /plans`,
   `PlanController.java` (per orientation cache, §74 built the editor).
2. **Edit a plan's scope and criteria**, versioned — `PATCH /plans/{id}`. Absent/null/empty on
   list fields are three different instructions (leave alone / clear / clear), not
   interchangeable. `#inferred` from orientation cache (§74), not independently re-read.
3. **Move a plan through its own lifecycle** (`DRAFT → ACTIVE → CLOSED`, `IN_REVIEW`/`APPROVED`
   as an optional review ladder) and delete it — `PUT /plans/{id}/status`,
   `DELETE /plans/{id}`. See ENT-002.
4. **Create a run from a plan, generating one execution per case in scope.**
   `POST /runs` — `RunController.java:67-89` `#observed`. The response is the run itself, not
   its whole checklist; the client fetches the run detail separately for that.
   `RunController.java:58-66` `#observed`.
5. **List and filter runs**, by plan or release. `GET /runs?planId=&releaseVersion=` — a filter
   matching nothing returns an empty list, never a 404. `RunController.java:98-113` `#observed`.
6. **Move a run through its lifecycle**: not started → in progress → completed/aborted, and
   reopen a closed run. `PUT /runs/{id}/status` — `RunController.java:125-160` `#observed`.
   Ending or reopening a run is lead-only. `RunController.java:158-165` `#observed`. See
   ENT-003.
7. **Record or amend a result on one execution.** `PATCH
   /runs/{id}/executions/{id}` — concurrency-protected with `If-Match`/`lockVersion`, because a
   run is *designed* to be worked by several testers on different cases at once — a missing
   `If-Match` is treated as last-write-wins for an importer with no prior read.
   `RunController.java:203-247` `#observed`.
8. **Attach evidence (a file) to a result**, download it, or remove it. `POST/GET/DELETE
   .../evidence` — `EvidenceController.java`. Files are capped at 10MB by the container before
   the handler even runs. `EvidenceController.java:51-59` `#observed`.
9. **View a case's or suite's authored version history** — `CaseHistoryController.java`,
   `SuiteHistoryController.java` (not independently re-traced this session beyond what
   `CaseController.versions` already covers under AREA-003).

## Business rules

- **A closed run is frozen for both the verdict and its evidence, as one rule enforced at three
  write points** (record a result, attach evidence, delete evidence) — the same refusal message
  everywhere so the rule reads identically regardless of which screen hit it.
  `RunController.java:172-183` `#observed`. Business intent, stated directly in the code: a
  closed run's record must not quietly keep changing after the team has moved on. `#inferred`.
- **The run lifecycle enforces its own edge-shaped rule**: ending or reopening a run needs a
  test lead; ordinary execution (recording results) does not.
  `RunController.java:158-165` `#observed`.
- **A run's status change is absolute, not incremental** — the endpoint is `PUT .../status`
  naming the target state outright, mirroring the same shape used for a documentation page's
  status and (per cache) a plan's and a case/suite's status. `RunController.java:125-133`
  `#observed`. There is deliberately no `If-Match` on this write, because the target is an
  absolute instruction rather than a value read-and-compared. `RunController.java:135-136`
  `#observed`.
- **Recording a result is concurrency-checked but tolerant of no prior read** — an editor that
  read the execution sends `If-Match`; a script or import with nothing to compare against is
  last-write-wins. `RunController.java:203-210` `#observed`.
- **A malformed concurrency header is a 400, not a 409** — deliberately, so a client with a
  broken header doesn't get sent into an endless reload-and-retry loop that could never
  succeed. `RunController.java:214-221`, `CaseController.java:272-282` `#observed`.
- **Evidence upload computes its checksum and writes its database row before touching the
  filesystem** — so the one kind of inconsistency the system can produce is a row whose file is
  missing (discoverable, an operator can find and repair it), never an orphaned file no row
  names (unreachable, uncollectable). `#inferred` from orientation cache (be-java.md, "Rows
  First, Bytes Second"), not independently re-read this session — flagged rather than silently
  assumed.
- **A run and its executions are designed for concurrent, multi-person execution** — several
  testers can be working different cases in the same run at once by design, which is why the
  concurrency check is per-execution, not per-run. `RunController.java:203-210` `#observed`.

## Failure behavior

| Situation | Response | Evidence |
|---|---|---|
| Recording a result on a closed run | 409, "this run cannot change that way" | `RunController.java:172-193` `#observed` |
| Attaching/removing evidence on a closed run | Same 409 as above | `RunController.java:181-183` `#observed` (applies the rule to the evidence path per its own comment) |
| Ending/reopening a run without lead permission | 403, "needs a test lead" | `RunController.java:158-165` `#observed` |
| Stale `If-Match` on a result | 409 | `RunController.java:241-243` `#observed` |
| Unparseable `If-Match` header | 400, field error | `RunController.java:214-221` `#observed` |
| Filter matching no runs | 200, empty list (not 404) | `RunController.java:98-104` `#observed` |
| File over the upload size limit | 413, before the handler runs | `EvidenceController.java:51-59` `#observed`, per orientation cache |

## Boundaries / not built

- `/reports` — a reporting surface over execution facts — is designed in the plan but has **no
  server implementation** at all; it is dead contract. `#inferred` from orientation cache
  (be-java.md "Known unknowns"), not independently re-verified this session, but worth stating
  plainly rather than documenting it as a working capability.
- §6g's "gating rules" (require an approved case before it can join a run, or a plan to start)
  are named in the design as intentionally powerful and obstructive, and are **not built**.
  `#inferred` from orientation cache.

## Open questions for the business

1. Is the reporting module (`/reports`) still wanted as designed, or has the need moved
   elsewhere since it was scoped? `#inferred`
2. Should an approved case be required before it can be added to a run, or is that
   deliberately left as a soft convention rather than a hard gate? The design calls this
   "genuinely obstructive" and left it unbuilt — worth a business decision either way.
   `#inferred`

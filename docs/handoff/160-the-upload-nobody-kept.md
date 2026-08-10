# §160 — the upload nobody kept, and the blocker that expired two slices before it was restated

**One phase.** `backend/` + contract + `frontend/`. **No migration** — `raw_storage_key TEXT` has been
on `ingestion_job` since `V1` line 1342. Both `schema.d.ts` copies regenerated, so `packages/` was
built and its 229 tests run; no `packages/` source changed.

The last of §156's three named items, and the one §154 called *"the most expensive member"* of its
population: **the verbatim file a CI job posts has never been stored, and until now could not be.**
`raw_payload` keeps the normalized parse — what the server *understood* — and nothing kept what CI
*sent*.

## The finding is not the missing feature. It is that the reason for it expired in §125

Four places said the column stays null because there is no adapter, all citing §67's port argument:

- `IngestionJob`'s class header — *"there is no adapter (§67's port argument,
  `storage.AttachmentStore`) — so that column stays null and says so"*
- `IngestionService.payload`'s javadoc — the same sentence, in the method that writes the *other*
  column
- `UnmatchedTests`' header, in passing
- **both** decision maps in `ArchitectureRulesTest` — `READERLESS_BY_DECISION` (§154) and
  `WRITERLESS_BY_DECISION` (§156)

**§125 built the port.** `storage` became a foundation module with `FilesystemBlobStore` behind two
ports, and `execution` has depended on it for evidence ever since. So when §154 recorded this column
as a standing decision, the argument it recorded had already been false for twenty-nine slices — and
§156 restated it, and §159 named the item while noting the expiry in one clause of the handoff index
that nothing in the code reflected.

This is the fourth slice running where the prose about a seam stood in for the seam. The difference
here is the direction: §152–§156 found comments that described a state the code was not in. This one
found a comment that described a *constraint that no longer existed*, which no rule in the family
checks — a by-decision entry is only ever re-read when somebody proposes deleting it.

## What was actually missing, and what was already there

**The write was cheap and always would have been.** `IngestionRequest.report` is a `String`, already
in memory, already capped at 1 MB by `IngestionEdit.MAX_REPORT_BYTES`. No multipart, no streaming,
no size question. What was missing was a decision about *where*, and an operation to get it back.

## Three decisions

### 1. A third port and a third root, not a reuse of either sibling

`RawReportStore` + `FilesystemRawReportStore` + `tcms.storage.raw-reports.root`, delegating to the
same `FilesystemBlobStore` body the other two share.

The module's header states when a root is separate: **retention and loss consequences differ**. This
profile matches neither sibling.

| | evidence | reports | raw reports |
|---|---|---|---|
| Written when | a person attaches | somebody asks | **every CI run of every project** |
| Reproducible | no — it is the proof | yes, from `execution_fact` | **no, and never** |
| Losing it costs | the proof behind a verdict | re-running a job | the answer to *"is that what CI sent?"* |

So it sits between them: not regenerable like a report, not the proof behind a human verdict like
evidence. It is also **the highest write rate in the product** and the one root an operator will
plausibly prune first — which is precisely why it must not be a subdirectory of a root that is
backup-critical.

**Naming.** `RawReportStore` rather than `CiReportStore`, to match the column vocabulary already in
the entity (`raw_storage_key`, `raw_payload`). It reads one letter from `ReportStore` and that is
the cost of the pairing; what makes it safe is the module's own rule — two *types*, so handing over
the wrong one is a compile error rather than bytes silently written into the wrong root.

### 2. The gate is membership. No `ingestion:read` was invented

`ProjectService.readVisible`, exactly as `IngestionReadService.list` and `findOne` are gated, and for
that class's already-written reason: there is no `run:read` or `result:read` in the catalogue either.
A new key would be **§67's refused shape for the thirteenth time** — a key seeded to nobody is a 403
no administrator can grant their way out of.

What settles it beyond consistency: the upload is the evidence behind verdicts a project member may
already read in full, and it is the same bytes that member's own CI job posted.

### 3. Nothing prunes it, and that is a decision

Exactly as nothing deletes the other two roots. A retention sweep is a scheduled job with a
company-scoped context and a policy nobody has set, and shipping one here would make the **first
retention rule in the product a side effect of adding a download.** §6n is where it will eventually
be governed. The `application.yml` comment says so at the root it governs.

## The ordering follows evidence, not the report runner — and it is I/O inside a transaction

`IngestionService` writes the job row (`saveAndFlush`) and then the bytes, inside the transaction
`TransactionTemplate` already opened. That is `EvidenceService`'s ordering and its reason: **a row
whose blob is missing downloads as a 404 and is findable; a blob no row names is unreachable and
uncollectable.** `ReportJobRunner`'s inverse does not apply, because there is no prior row naming
this work — the job row *is* the thing being created.

**This is external I/O inside a transaction boundary, which the general rule is against**, and it is
deliberate: the payload is capped at 1 MB, the write is local, and the only other `execution` caller
of a blob store does exactly this. Deviating would have put ingestion's ordering at odds with
evidence's for no gain. `RawReportStore`'s header carries the argument so the next reader does not
have to reconstruct it.

## Two traps met on the way, both silent when wrong

**The key needed the job's id before the row existed.** `BlobUpload.key(companyId, ownerId)`'s middle
segment is the owner, and the owner here is the *job* — not the run, because a run can be posted into
many times and a run-keyed directory would hold several builds' uploads with nothing but a random
tail to tell them apart. But `IngestionJob.completed` generated its own id internally, so the key
could not be built before the row. **The id is now the caller's**, which is the one factory in this
module that works that way; the alternative was a second mutator on an entity whose header says
nothing edits an ingestion job — a mutation seam bought to avoid a parameter.

**`locate` was very nearly a self-invocation.** The first draft annotated it
`@Transactional(readOnly = true)` and called it from `openRawReport` in the same class. Spring's
proxy does not intercept that, so the annotation would have done nothing — and under
`CompanyAwareTransactionManager` that is not a missing rollback but a missing `app.current_company`,
with every RLS-protected read **silently returning zero rows**. It is the same defect
`IngestionService`'s `TransactionTemplate` field documents from the write side, where it cost §116 a
red run and every valid credential. `ReportReadService.open` has the correct shape: annotate neither,
and let `ProjectService` and the repositories carry their own boundaries. Fixed before it ran; it
would have passed compilation and failed as an empty download.

## `reportUrl` — a server-composed URL, and the null is the point

`IngestionJobView` gains `reportUrl`, **required-and-nullable**, composed by the server exactly as
`ReportJob.downloadUrl` is, so the client holds no copy of this server's routing.

The nullability is doing real work: **null is the only thing on the wire that distinguishes a job
posted before uploads were kept from one posted since**, and no migration can repair those rows.
`raw_storage_key` itself is deliberately *not* served — a storage key on the wire is an
implementation detail and an invitation to build the URL client-side.

That makes the SPA control key on the URL and nothing else, which is `DownloadReportButton`'s rule
for its reason: the field that cannot produce a broken link is the URL. A control keyed on
`status: COMPLETED` would render a button that 404s on every historical row in an upgraded instance.

## The SPA half

`DownloadRawReportButton`, on the ingestion job page's **header** — not in the panel, because the
panel is a `DataRegion` that renders nothing when every test matched, and **a report where everything
matched is exactly when somebody wants to check what was posted.** A test that never ran is absent
from `unmatched` and absent from the run, and looks identical to one that was never written;
`reportUrl` is the only way to tell those apart, which is the argument for the control existing at
all.

It is a sibling of `DownloadReportButton` rather than a reuse: that one names a *format a person
chose*, this one a *dialect CI chose*. What `ui-rules` §8 forbids writing twice is the
**authenticated download**, which is `fetchArtifact` + `saveBlob` — both reused. A plain `<a href>`
would send no credential and answer 401 while rendering as a working link.

## Served `inline`, and the media type cannot carry the dialect

`application/xml` for JUnit and TestNG, `application/json` for Allure and Cucumber — and **that is
`IngestionInput.format`'s opening argument read backwards.** A `Content-Type` could not tell JUnit
from TestNG on the way in and cannot on the way out, so the *filename* carries the dialect
(`ingestion-{id}-junit.xml`) and the media type carries only enough for a browser not to mangle it.

`inline` rather than `attachment` — the opposite of a generated report, which is a file somebody
keeps. This is forty lines of XML read beside the binder to check one name.

Every segment of the filename is server-generated. Nothing client-supplied reaches that header, and
here that is free rather than earned: `IngestionRequest` carries the report as a string and **has no
filename field**, so CI never named the file in the first place.

## Both rules lose the same member, in one slice

`READERLESS_BY_DECISION` goes **thirteen → twelve**; `WRITERLESS_BY_DECISION` goes **eleven → ten**.
`IngestionJob.rawStorageKey` is the first member ever to leave *both* in one slice, and that is not a
coincidence: each entry argued for the other half's absence, so writing one alone would have produced
exactly the two-states-at-once shape the field's own javadoc warned about. It is the fourth member to
leave the writerless rule **by being written** rather than deleted (after §157, §158, §159).

Three hand-kept counts in that file needed correcting alongside — the two javadoc tallies and one
inline comment — which is the file whose subject is tallies kept by hand, for the third slice
running.

## Documents corrected rather than added to

- `IngestionJob` class header + the field's javadoc — the §67 paragraph struck through
- `IngestionService.payload` — the same sentence, in the method that writes the other column
- `application.yml` — the third root, with the retention decision at it
- `deploy/compose.prod.yaml` — the env var **and** the bind mount. A root without a mount is the
  failure the runbook records happening to both of the first two: the paths defaulted into the
  container's writable layer and every byte went with the next `docker compose up`. The comment now
  says a fourth store means a line in each place.
- `IntegrationTestBase` — a third temp root, for the reason the second one is separate

## Gates

| Stack | Result |
|---|---|
| `backend/` | `./gradlew build` **SUCCESSFUL — 2445 tests · 505 classes · 0 failures** (+7, +1 class: `IngestionEndpointTest$RawReport`) |
| `frontend/` | `npm run build` green · `npm run lint` 0 warnings · **1214 tests across 93 files** (+3) |
| `packages/` | `npm run build` green · lint 0 warnings · **229 tests** (schema regenerated, so run) |

Backend test count and the endpoint suite's new group are recorded in the index entry.

## What is next

Two of §156's three named items are now closed (`rawStorageKey`, the archive operation) and the
third — the three remaining `case_health` derivations — is the blocked one: two need `documentation`,
which `repository` may not depend on, and `orphaned` needs a product decision before it needs code.

Standing candidates: the browser queue, `TeamRef` (two copies, which §147 judged as not yet
`UserRef`'s weight of evidence), the read-only-archive decision §159 deliberately did not take, or
another re-derivation.

**A re-derivation this slice suggests, and does not take:** every entry in a `*_BY_DECISION` map
records an argument, and nothing re-checks whether the argument still holds. This one had been false
for twenty-nine slices behind a rule that runs green every build. The question — *which by-decision
entries cite a constraint the code no longer has* — is the same question §154 and §156 asked of
columns, asked of their own justifications.

<!-- orientation-cache v1 -->

# be-java — orientation cache (TCMS)

**Last verified:** 2026-08-08 (**§131 phases 1–2** — the outbox; **§129 phases 1–2** — clone case / plan / run; **§128** — the case
draft buffer; **§127 phase 1** — the table and image block payloads; **§126 phases 1–4** —
attachment owners; **§125** — the `storage` module extraction; **§124** — the `BEST` removal and the
two unwired permission keys; §122's grant-instance-admin, §121's four phases, §120's automation
drift, §119's ingestion history, §117's CI ingestion, §116's API tokens before it)
**Baseline commit:** `e7326e3` (§131 phase 1) (per `CLAUDE.md`, trust the body over the header and
re-grep what a later commit could have moved).

**SCHEMA HEAD IS STILL `V39__attachment_owner_types.sql`; `V40` is next free.** The policy count is
unchanged at **62**, and **neither §129 nor §131 carried a migration** — a clone reuses the version
snapshot (which is why #59 dropped plan *templates*: they need a table), and the outbox's table,
`CHECK` and partial index have all been in `V1` since M0.

**The nightly Linux gate is LIVE** (`tests.yml` on `[self-hosted, tcms-lab]`, 01:17 UTC, green since
2026-08-06) — four documents described it as inert until 2026-08-08. `gh run list --workflow=tests.yml`
before repeating any claim about it. `ci.yml` still runs **no** backend tests.

---

## §131 — THE OUTBOX IS ALSO THE ONLY CROSS-MODULE SHAPE CHANNEL

Round 3's step 9 (#52), two phases, **no migration** — `V1` has carried every column, `CHECK` and
index since M0. New package content in `tenancy`: `OutboxEntry` · `OutboxStatus` · `OutboxMessage`
(the **first `CompanyScopedEvent` implementation in `src/main`**) · `OutboxHandler` ·
`OutboxRepository` · `Outbox` (writer) · `OutboxLedger` (row-state transitions) · `OutboxDispatcher`
· `OutboxScheduler` · `OutboxProperties` · `OutboxEvents`.

- **A typed cross-module Spring event is unbuildable here.** `ArchitectureRulesTest`'s foundation
  rule reads `getDirectDependenciesFromSelf` and has **no exemption for an event type**, so
  `reporting` cannot import a class from `execution`. `event_type TEXT` + `payload JSONB` is
  therefore the mechanism and not a storage detail. Before proposing "just publish a Spring event",
  check that rule.
- **`Outbox.record` / `recordCoalesced` throw unless a transaction is active**, and
  `OutboxDispatcher.dispatchDue` throws if one *is*. Both guard defects with no symptom.
- **Three transactions per event; the handler runs in none of them.** `OutboxLedger` is a separate
  bean because a handler's exception marks the surrounding transaction rollback-only (the failure
  record would be discarded) and because a self-invoked `@Transactional` is not intercepted at all.
- **`PENDING` is the retry status**, forced by `outbox_pending_idx` being partial on it. `FAILED` =
  ceiling reached, replayable; `DEAD` = no handler / unreadable payload. **Unclaimed type ⇒ DEAD,
  never SENT.**
- **The lease, not a lock, is the concurrency mechanism**: `claim` bumps `attempts` and pushes
  `next_attempt_at`. Delivery is **at least once** and every handler must be idempotent.
- **Several handlers may claim one event type** (fan-out); they share one row, so one failure
  retries all. Ordering between them is undefined — co-dependent effects go in one handler
  (`reporting`'s does refresh-then-rollups for §101).
- **`recordCoalesced` takes no aggregate and no payload**, and folds only into a row with
  `attempts = 0`. Use it for *state moved*; use `record` for *a thing happened*.
- Phase 2: `execution.ExecutionService.record` and `IngestionService` produce
  `OutboxEvents.RESULTS_CHANGED`; `reporting.FactRefreshOnResultsChanged` and
  `repository.HealthRecomputeOnResultsChanged` consume it (**named apart on purpose: two
  `@Component`s with one simple name collide on the default bean name and fail every
  `@SpringBootTest`**), each under its own module's existing
  `enabled` property. The schedulers **stay** — they are the rebuild path.
- `tcms.outbox.*` in `application.yml`; `IntegrationTestBase` sets `tcms.outbox.enabled=false` for
  the suite's third scheduled writer. Fixture prefix **`0b`**, slugs `obx-`.
- **`ArchitectureRulesTest.springStereotypesHaveDistinctSimpleNames`** is new and is the check for a
  defect class the suite cannot otherwise see: two `@Component`s with one simple name share a
  default bean name and the context refuses to start — but if either carries
  `@ConditionalOnProperty` and the suite disables it, **no integration test registers the bean and
  nothing reproduces it until a real boot**. Reads bytecode, not a context, for that reason.
- **The invitation email cannot use the outbox**: only a token digest is stored, so the payload
  would be a live credential at rest and a retry could not reconstruct it.

---

## §129 — A CLONE IS A CREATE, EXCEPT WHERE THE CREATE CONTRACT IS NARROWER THAN THE AGGREGATE

Decision round 3's step 8, second half (#59). Three endpoints, all `POST …/{id}/clone`.

- **The shape: read the source, build the request the create endpoint already takes, delegate.** It
  holds for the **case** and the **run** and gives six of §6v's rules for free (new display key,
  DRAFT/OPEN status, own version 1, no shared history, no execution history, idempotency). It does
  **not** hold for the **plan**: `PlanWriteRequest` has six fields where `PlanUpdateRequest` has
  twelve, so a delegated clone drops the tag arm, every §97 section, both prose fields and both
  criteria lists — and answers 201.
- **`PlanCreateService.write` is the shared orchestration** (validated `PlanEdit` + the two criteria
  JSON columns, instead of a request). Create-then-edit was refused: it gives every cloned plan a
  v1 describing a plan that never existed and a v2 one second later.
- **THE RED RUN WAS THE AUTOMATION STATUS.** §6v's "copy the status" and "never copy the key" are
  jointly unsatisfiable — `CaseEdit.validate` refuses `AUTOMATED` with no key. `AUTOMATED` becomes
  `TO_BE_AUTOMATED`; everything else copies. Relaxing the validator instead leaves a case reporting
  as automated that §117's matcher can never match.
- **`AttachmentStore.copy` is a fifth port method**, and `ReportStore` deliberately does not get it
  — the two ports are two types precisely so they can differ, and this is the first divergence.
  §6v's "attachments by reference" is **unimplementable**: the store holds no reference count, so
  two rows over one key means either delete destroys the other's bytes.
  `Attachment.copyForCase` **carries** the source checksum rather than recomputing it, so a corrupt
  blob cannot launder itself through a clone.
- **`PlanScopeItem.rowsOf` is the one scope→rows mapping.** It existed twice — five arms in
  `PlanUpdateService`, two in `PlanCreateService` — correct only while the create request could not
  express the other three. §127's `BlockContent.of` finding, one aggregate over.
- **A run stores no scope and no assignee.** §99's three arms are resolved once into
  `test_execution`; the declaration is discarded. So a cloned run carries the **resolved** case set
  (a suite-scoped clone does *not* pick up cases added since) and is **unassigned** unless the
  request says otherwise.
- **§6g's approval gate is re-evaluated on a cloned run and the override is never inherited** —
  nothing stores it, which is what keeps that unexpressible rather than merely unwritten.
- **Provenance is a SECOND audit row** (`case.cloned` / `plan.cloned` / `run.cloned`) beside the
  created one, naming both display keys.
- Fixture prefixes **`1a`** (`CaseCloneEndpointTest`), **`2a`** (`PlanCloneEndpointTest`), **`3a`**
  (`RunCloneEndpointTest`); slugs `clone-`, `pcl-`, `rcl-`.

---

## §128 — THE AUTOSAVE BUFFER, AND THE VERSION THE STALE-EDITOR CHECK CANNOT SEE

Decision round 3's step 8 (#57). `test_case_draft` had a `V1` comment describing its whole flow and
no writer; three verbs on `/projects/{p}/cases/{c}/draft` now serve it.

- **`CaseDraftService` is the only writer, and there is NO promote endpoint.** `V1`'s comment says a
  save "promotes this into `test_case`", which reads like a method this class should have. It would
  be a second write path into `test_case` whose input is by construction unvalidated — either a
  duplicate of `CaseEdit` (two definitions of a legal case, free to disagree) or no validation at
  all. Promotion is the client restoring into the form and the user pressing the ordinary `PATCH`.
- **`CaseUpdateService.apply` clears the buffer on every successful save**, in the same transaction
  as the version row, via `TestCaseDraftRepository.deleteForCase` (a `@Modifying` bulk delete —
  usually zero rows, on the hottest write path). Placed **after** validation: a clear at the top of
  the method destroys the work a refused save was protecting, and passes every other test.
- **`CaseDraftInput.lockVersion` is the field the existing concurrency machinery is blind to.** A
  restored draft's save carries an `If-Match` from the *fresh* read, so it is correct and the
  stale-editor check passes — silently overwriting the edit made while the draft sat there. The
  buffer is the only thing that knows what the draft was written *against*. `stale` is computed in
  `render` and is advisory; the content is always served.
- **`case_id` IS the primary key, so the buffer is per case and not per person.** `updatedBy` is on
  the wire for that reason, not as an audit column. `TestCaseDraft`'s header records the per-person
  key as the option **not** taken.
- **Nothing in `content` is validated and there is no `@Version`** — both deliberate, both with the
  failure they prevent in the javadoc. `MAX_CONTENT_BYTES` is 256 KB measured in UTF-8 **bytes**.
- **`Outcome.Empty` is a 204 and is not `NotFound`.** Almost every case has no draft almost always.
- **`case:edit` gates all three verbs including the GET** — the one gate here that looks harmless to
  relax; `CD-3` is its mutation.
- **FIXTURE COLLISIONS ARE NOT ONLY UUIDs.** `CaseDraftEndpointTest` died 26/26 in `@BeforeEach` on
  the company **slug** `cd-acme` and its email domain, both already `CaseDeleteEndpointTest`'s and
  both globally unique. §19's family, sixth instance, through fields the convention does not name.
  **Check the slug and the email domain as well as the UUID prefix.**

---

## §127 phase 1 — A BLOCK'S PAYLOAD IS PART OF THE VERSION SNAPSHOT, OR ITS HISTORY LIES

Decision round 3's step 7 (#48): `DocBlockType` had six values and `BlockContent` modelled the two
fields four of them share. `table` and `image` now carry their own payload.

- **`BlockContent` is `(text, level, rows, attachmentId)` and `BlockContent.of(input)` is the ONE
  mapping.** `DocPageWriteService.applyCreate` and `PageContent.reconcile` each spelled the
  constructor out — two copies of one mapping, where a field wired into the edit path and forgotten
  on the create path gives a page whose table renders on save and comes back empty when it is first
  created, with nothing failing at either end.
- **`PageVersions.SnapshotBlock` HAD TO GROW WITH IT, and forgetting that is silent in the direction
  that looks like success.** The hash decides whether a version row is written, and it is computed
  over that record — so a content field the snapshot does not carry is a field somebody can rewrite
  all day while the history says nothing happened. Every other assertion about tables passes without
  it. `TableBlocks.aCellEditIsAuthorship` is the guard.
- **`@JsonInclude(NON_NULL)` on the two NEW snapshot components and deliberately not on the older
  two.** Without it every stored `content_hash` in every deployment changes on deploy and the first
  save of each untouched page writes an empty-diff version — history noise delivered once per page
  as a migration artefact. Nothing in a test run can see this (every hash in a run is computed by
  one build), so the assertion is that a paragraph's stored snapshot has **no `rows` key**.
- **Three payload fields, one rule: refused on the types that do not have it.** `level` (heading /
  list), `rows` (table), `attachmentId` (image). A field on the wrong type means the client believes
  it is sending something else.
- **An `image` names an attachment ON THIS PAGE — `parentId`'s rule one field over.** So **a create
  can never carry an image block** (a page has no files until it exists), and the refusal says
  *"create the page, attach the image, then add the block"* rather than reporting a valid id as
  unknown. `PageAttachments.idsOf(files)` derives the set from the **same read** the response
  renders, so the validator and the client cannot be shown different worlds.
- **`text` is the alt text on an `image` and blank is REFUSED** — the one block type where empty
  text is not legal, one `else if` away from the empty-paragraph rule that must stay legal.
- **A ragged table row is refused, not padded**, and **row 0 decides the width** because row 0 is the
  header. Taking the widest row lets a stray cell silently widen the table.
- **`DocBlockRepository.countBlocksReferencing` is a new guard with no foreign key behind it** — the
  reference lives inside a JSONB document, so `DELETE /attachments/{id}` is a **409** while a block
  names the file, checked *before* the bytes, which do not come back. Native, `content ->>
  'attachmentId'` compared as **text** (a `uuid`-to-`text` comparison is a 500, not a wrong answer).
- **The V2 search trigger needed no change**: `jsonb_to_tsvector(…, '["string"]')` walks the whole
  document, so a table's cells were searchable the moment they could be stored. `SearchVectorTest`
  pins it, because narrowing that trigger to `content ->> 'text'` reads as a tidy-up.

---

## §126 phase 3 — `ArtifactKind.FILE` IS REGISTRABLE, AND AN ARTIFACT IS NOT A LIST

**`attachment.owner_type = 'ARTIFACT'` has a writer, and it is the last of the five surviving owner
types to get one.** `V1`'s polymorphic table is now fully claimed.

- **AN ARTIFACT DOES NOT HAVE ATTACHMENTS — IT *IS* ONE.** Read this before assuming the
  `CASE`/`DOC_PAGE` shape carries over; the slice plan assumed it and was wrong. `V1`'s
  `artifact.attachment_id` is a **single nullable FK** and sits among four payload columns, one per
  kind: `attachment_id` (`FILE`) · `doc_page_id` (`DOC_PAGE_REF`) · `external_url`
  (`EXTERNAL_URL`) · `report_artifact_id` (`GENERATED_REPORT`). So there is **no attachment family
  under `/artifacts/{id}`**, no list, no attach-afterwards and no detach.
- **`artifact.attachment_id` and `attachment.owner_type='ARTIFACT'` are BOTH written and are not
  rivals.** `owner_id`/`owner_type` are `NOT NULL` (no blob is unowned); the FK is the kind's
  payload. A reader who assumes the schema declared one relationship twice will delete the wrong
  half.
- **`POST /projects/{projectId}/artifacts/files`** — multipart, `artifact` (JSON, `ArtifactFileInput`)
  + `file`. **A separate path, not a media type on `POST /artifacts`**: sharing the path means
  narrowing that operation to `consumes: application/json`, which turns every client posting a
  reference with no `Content-Type` into a 415. Own controller (`ArtifactFileController`) for the
  `MaxUploadSizeExceededException` scoping reason — **fourth instance** of that argument.
- **`ArtifactFileInput` has NO `kind`, `url` or `docPageId`.** `FILE` by construction, so a
  contradictory request is unconstructable from the generated types — `ArtifactInput.kind`'s
  narrowing argument taken one step further. `asRegistration()` widens it so **both create paths
  share one `validate`**; the only difference is a `hasUpload` boolean.
- **THE GATE IS `artifact:edit`, SCOPE-QUALIFIED — the same `writeGrantedByRowScope` the reference
  create uses, at a SECOND call site.** A plain `writeGranted` here compiles and passes everything
  but `Gates.anEngineerCannotUploadAtProjectScope` (mutation `FA-1`). Delete is
  `artifact:delete` (§124's key) and now guards the last copy of a file. Download costs nothing
  beyond the register read — no `attachment:read` was invented.
- **`Artifact.register` takes the id as a PARAMETER now.** The two rows reference each other, so one
  id must exist before either object does. The alternative — setting the column on the transient
  instance — is a trap: the same call on a *managed* instance compiles, runs and persists nothing
  (`updatable = false`), giving a `FILE` artifact with no file and no error.
- **Write order: attachment row → artifact row → bytes. Delete order: artifact row → attachment row
  → bytes.** The first two are the FK's direction (checked at statement time, so the other order is
  a 500 on every upload); the bytes are last in both for §67's non-rollback reason.
- **`ArtifactFiles`** is the assembly seam — `forArtifacts(rows)` batched for the register
  (**plural**, opposite call from `PageAttachments`, because a register renders dozens of rows at
  once) and `forArtifact(one)` for the download. It keys on the **artifact's own `attachment_id`**,
  not the owner side, so a second owner row could never be picked at random.
- **`ArtifactView.url` now carries a THIRD meaning** — the external link for `EXTERNAL_URL` and the
  **download path** for `FILE`, `null` for the other two. Server-built, so the SPA never composes
  the route. `filename`/`sizeBytes` were on the wire with no writer since the register shipped, so
  neither side changed shape.
- **The download is `Content-Disposition: attachment`, not `inline` — the only one in the product.**
  A page's image renders beside its paragraphs; an artifact is a held document reached from a list
  that names it.
- `Artifact`'s "four deliberately unmapped columns" is **three** now. The rule ("do not map a field
  this slice cannot set") stands; its condition expired — **fifth** blocker note to outlive its
  blocker.
- Fixture prefix **`4f`** (`ArtifactFileEndpointTest`, 16). **NO RED RUN** — stated because it is
  unusual, and the likely reason is that the two-rows-referencing-each-other problem was solved at
  design time rather than at compile time.

## §126 phase 2 — `documentation` DECLARES `storage`, AND THE WIRE SCHEMA IS NO LONGER CASE-SHAPED

**`documentation`'s `allowedDependencies` is `{"tenancy", "project", "identity", "storage"}`** — the
**second** module to declare `storage` after `repository`. What it asks of it is *hold these bytes
and give them back*, and the download is why `storage` is a foundation module rather than an event
peer: a request has to answer with the bytes.

- **The contract's `CaseAttachment` schema IS NOW `Attachment`**, serving `CASE`, `DOC_PAGE` and
  (phase 3) `ARTIFACT`. One schema because the shapes are identical field for field *and* meaning
  for meaning — only `url`'s value differs — and three identical generated types would re-create by
  hand, on the wire, the polymorphism-by-duplication `storage` removed in the code. The rename was
  free: nothing in `frontend/src/` named the old type, only the generated `schema.d.ts`.
  **`Evidence` stays separate** — it has a caption and it is a record rather than authored content,
  which is what decides the `OWN` arm on its delete.
- **ONE wire schema, TWO Java records** — `repository.CaseAttachmentView` and
  `documentation.DocPageAttachmentView`. Not shared, and there is nowhere to share it: the two
  modules cannot import each other, and `storage` (which both reach) declares `tenancy` alone and
  must not learn what a route or a `UserRef` is. `UserRefView` is already duplicated four ways for
  this reason. **A field added to one and not the other is a response that omits it on one owner,
  and nothing but the contract test observes that** — mutation `DA-9`.
- **`documentation.PageAttachments.forPage(projectId, pageId)`** is the assembly seam —
  `BlockLinks.forPage`'s shape, and the one place a page's file list is built. **Singular where
  `CaseRenderer.attachmentsFor` is plural, deliberately**: pages are rendered one at a time (the
  tree serves `DocPageSummaryView`, which has no file list), so a batch form would be a seam ahead
  of its consumer. Uploaders resolve through the existing `ArtifactPeople`.
- **`DocPageAttachmentService` + `DocPageAttachmentController`**, its own controller because
  multipart brings a `MaxUploadSizeExceededException` handler and an `@ExceptionHandler` is scoped
  to its controller — on `DocPageController` an oversized upload's 413 would answer a JSON `PATCH`.
  **`DocPageController.validationProblem` / `forbiddenProblem` went package-private** so the second
  controller reuses them rather than becoming the fourth hand-rolled problem envelope.
- **THE GATE IS `doc:edit` ON BOTH WRITES, NOT `doc:publish`.** An attachment is *content*, so
  whoever may rewrite the paragraphs may attach the diagram they describe; the other choice leaves a
  Test Engineer able to author a specification they cannot illustrate, and **every other doc write
  they make would keep working**, so nothing else in the suite would catch the drift. No `OWN` arm
  on the delete, for `CaseAttachmentService`'s reason. Download costs nothing beyond the page read.
- **NO `If-Match`, NO version row, NO status arm — three deliberate non-copies from the page's own
  writes.** Blocks are reconciled by id so a stale client carries stale ids; an attachment is
  addressed by its own id and never reconciled. `doc_page_version` snapshots blocks, so a version
  row here would be a history entry whose diff says nothing changed. And **an `ARCHIVED` page still
  accepts files** — `ARCHIVED` keeps links resolvable, and making it a retention control is a
  different decision (mutation `DA-5`).
- **`DocPage.attachments` is required and never null**, detail-only. `DocPageView.of` now has a
  4-arg form; the 2-arg create form passes `List.of()` because a page cannot have a file before it
  has an id. **`DocPageWriteService.edit` and `setStatus` RE-READ the attachments** — the `PATCH`
  response is what the SPA renders, so serving `[]` makes a save appear to delete every file until
  the next `GET` (mutation `DA-8`).
- **Two red runs, both the test's fault and both worth keeping:** (1) an `UMBRA`-owned
  `role_assignment` inserted inside the `ACME` context block — **RLS refused it**, 22/22 failed;
  seed each company's rows under its own context. (2) `"\211PNG"` is a Java **octal escape**
  (U+0089, two bytes in UTF-8), so a 4-character literal is 5 bytes. Also: the `DOC-n` counter is
  **per company** and every page any test in the class creates advances it, so a literal `DOC-1`
  was right on the first run and wrong on the second — read the key off the row.
- Fixture prefix **`df`** (`DocPageAttachmentEndpointTest`, 22), checked against every prefix in
  `src/test/java` first. It has a fifth persona, `OTHER_LEAD`, purely so the ungranted sibling
  project's page can be *authored* — a page in a project nobody holds cannot be created through the
  endpoint, and hand-seeding one produces a page shaped unlike every real one.
- **`ArchitectureRulesTest` does NOT guard a consumer's declaration.** `storageDependsOnTenancyAlone`
  checks `storage`'s own; widening `documentation`'s would pass every rule in the file. Recorded as
  gap `DA-14`, not as a check.

## §126 phase 1 — THE ATTACHMENT OWNER VOCABULARY IS A TYPE, AND THE PORT WAS RENAMED

**`EvidenceStore` is now `AttachmentStore`; `FilesystemEvidenceStore` is `FilesystemAttachmentStore`.**
It backs four owner types, not one. **The property key `tcms.storage.evidence.root` was deliberately
NOT renamed** — renaming it relocates the root on the next deploy and orphans every existing blob
(§107's evidence destruction, in config form). Do not "finish" the rename.

- **`storage.AttachmentOwner` is a public enum** — `CASE · EXECUTION · STEP_RESULT · DOC_PAGE ·
  ARTIFACT`. `name()` **is** the column value, and `CaseAttachmentEndpointTest.OwnerTypes` reads the
  `CHECK` out of the live database and asserts the two sets equal. Adding a constant without a
  migration fails the build.
- **`V39` dropped `STEP` from the `CHECK`** (#50). It is not a gap: a step's file belongs to the
  recorded result (`STEP_RESULT`), not to the case definition.
- **`Attachment` has five factories and they are still the only place an owner type is chosen** —
  `forExecution · forStepResult · forCase · forDocPage · forArtifact`. All five take a
  `storage.BlobUpload`, which is where the filename sanitizer, the SHA-256 and **`BlobUpload.key`**
  (the one place a storage key is built) now live. `execution.EvidenceUpload` is a thin
  `record EvidenceUpload(BlobUpload file, String caption)`.
- **`AttachmentRepository.findOwnedBy` is PLURAL and has no singular sibling, on purpose** — a
  per-owner read is an N+1 inside `CaseBulkService`'s 500-case loop. `findOwnedById(owner, ownerId,
  id)` is the scoped point lookup; both halves of its predicate are load-bearing.
- **`repository` declares `{"tenancy", "project", "identity", "storage"}`.** ~~`documentation` does
  **not** yet — phases 2 and 3 add it.~~ → **phase 2 added it**; see the section above.
- **The `CASE` family is `CaseAttachmentService` + `CaseAttachmentController`**, its own controller
  because multipart brings a `MaxUploadSizeExceededException` handler. Gate is **`case:edit` on both
  writes with no `OWN` arm** (the delete deliberately does not copy `evidence:delete`); the download
  costs nothing beyond the case read gate. **There is no `GET` collection** — the list is
  `TestCase.attachments`, resolved by `CaseRenderer.attachmentsFor` in two queries per batch.

## §125 — THERE IS AN ELEVENTH MODULE AND A FOURTH FOUNDATION MODULE

**`com.tcms.storage`.** Read this before assuming where a type lives: `Attachment`,
`AttachmentRepository`, `EvidenceStore`, `StoredBlob`, `FilesystemEvidenceStore`, `ReportStore`,
`FilesystemReportStore` and the new `FilesystemBlobStore` are **all in `storage`** — they were in
`execution` and `reporting` until 2026-08-07. `ReportBlob` **is deleted**; `ReportStore.open` returns
`StoredBlob`.

- **`ArchitectureRules.FOUNDATION_MODULES` is `{tenancy, identity, project, storage}`.** A
  cross-module edge may point at any of the four. `execution` and `reporting` declare
  `{"tenancy", "project", "identity", "storage"}`.
- **`storage` declares `{"tenancy"}` and there is an ArchUnit rule enforcing it from the code** —
  `ArchitectureRulesTest.storageDependsOnTenancyAlone`. Do not widen that declaration: it is legal
  under every other rule in the file (identity and project are foundation modules), and the rule is
  the only observer. The three properties that make a non-domain foundation module defensible are in
  the constant's javadoc; **a fifth foundation module is a decision, not an import.**
- **`Attachment`'s factories and getters are `public` now**, and the factories are the only place an
  `owner_type` string is ever spelled. #49 adds `forCase` / `forDocPage` / `forArtifact` **there**,
  not at call sites.
- **The two ports stay two types deliberately.** One `BlobStore` with two qualified beans was
  considered and refused: the wrong qualifier writes evidence under the reports root with nothing
  failing, where the wrong type does not compile. The shared **body** is `FilesystemBlobStore`,
  constructed (never injected) by each adapter with its own root and a `kind` label that must survive
  — it is what tells an operator which root failed.
- **The row/bytes ordering belongs to the caller, not to the port.** `execution.EvidenceService`
  writes rows first; `reporting.ReportJobRunner` writes bytes first; both arguments are in their own
  headers, and neither port has a method that would decide it for both.
- Plan §3's `shared — storage, audit, attachments, search` is **struck through**: `shared` is now
  `audit, search`. A foundation module may not be a grab bag.
- **Found by the move:** `FilesystemReportStore`'s traversal guard had never been asserted —
  `FilesystemEvidenceStoreTest` (now `FilesystemBlobStoreTest`) was the only test either adapter had.
  Two files in two packages are never read side by side.

**§124's three changes, all of them removals or first-consumers rather than new machinery:**

- **`AggregationPolicy` has FOUR values.** `BEST` is gone from **both** copies —
  `execution.AggregationPolicy` and `reporting.AggregationPolicy` — and
  `AggregateExecutionPolicyTest` pins the two name sets equal, which is what makes removing from one
  file impossible to land. An unrecognised policy is a **400 naming the four**, from
  `PlanController`'s message derived off `values()`; there is no fallback to `LATEST`.
- **`ExecutionService.record` asks `run:execute` INSIDE the verdict gate**, not before it —
  `projects.holds(userId, projectId, EXECUTE_RUN)` in the `writeGranted` callback, answering
  `Outcome.NotExecuting` (a **new sealed arm**, so `RunController`'s switch is exhaustive). Order is
  load-bearing: in front of the gate a Viewer gets advice to use an endpoint they also may not use.
  Automation Service is the only role `V3` withholds `run:execute` from, and `ExecutionEndpointTest`
  now carries a `ROBOT` persona (`e2003000-…-06`) for exactly that.
- **`ArtifactWriteService.delete` + `DELETE /projects/{id}/artifacts/{artifactId}`** — gated on
  `artifact:delete` (**not** `PERMISSION`, which is `artifact:edit`), unqualified per `V19`'s own
  comment. Two 409s via `Outcome.Undeletable(message)`: `write_once`, and
  `ArtifactRepository.isSupersedingSomething` (**native**, because `superseded_by_id` is deliberately
  unmapped on the entity). `findInProject(projectId, artifactId)` is the new lookup — `findById`
  would delete a sibling project's row through a gate that legitimately passed.
- **`Artifact`'s javadoc claim that "nothing targets an artifact" is struck through and corrected.**
  It described a self-FK from memory and nothing could disprove it while nothing could delete.

**Contract changes §121 phase 4 made — both on shapes, neither a new operation:**
- **`TestCase.flake`** (nullable, required) — **`CaseFlake` had NO READ PATH.** It was served only
  as `PUT …/quarantine`'s response for three phases, so the SPA could set a state it could not
  show. A write path's response looks like a read, which is why nobody noticed.
- **`CaseFlake.quarantinedBy` is a `UserRef`, was a bare `UUID`** — the **only** actor field on this
  API that was one, against ~25 that are not, and `CaseFlakeView`'s own javadoc asserted it followed
  the convention it broke. §100's rule again: *a comment stating a fact about another file is not a
  check.*

**New seams §121 phase 4 created — `repository`:**
- **`CaseRenderer.flakesFor(companyId, rows)`** — two queries for any batch size, and the **first
  caller `CaseFlakeRepository.findForCases` has ever had.** That method was written in phase 1 with
  no consumer at all: the *second* consumerless seam in that file, missed by phase 3 because phase 3
  was hunting the one whose javadoc named a caller. `CaseBulkService` renders up to 500 cases, so
  the batched form is what keeps the third lookup in that class from being N+1; the batch's map is
  threaded through `Context.flakes()` and populated only for `authored` commands.
- **`CaseRenderer.render(…, Map<UUID, CaseFlakeView>)`** is the new low-level overload; the 4-arg
  form resolves its own batch of one.
- **`CaseQuarantineService.Outcome.Done` now carries a `CaseFlakeView`, not the entity.** The decider
  is resolved **inside the service's transaction** — `CaseOwners` reads `app_user` through the
  tenancy filter, so resolving in the controller would fail *closed* and answer null on every
  successful quarantine, with nothing failing anywhere.
- `TestCaseDetailView` gained `flake`. **It therefore lands in the version snapshot**
  (`CaseVersions` serializes this whole view) — accepted, not unnoticed: `healthSignals` is derived
  and has been in that snapshot since M1, and the content **hash is over `CaseEdit`**, so a nightly
  recompute still cannot make a no-op save write a version. Making the snapshot authored-only is a
  separate slice; doing it here would break the response-is-the-snapshot invariant `CaseRenderer`
  exists to hold.

> **⚠ `Map.of().get(null)` THROWS NPE BY CONTRACT, AND THIS IS THE SECOND TIME IT HAS BITTEN (§38).**
> `flakesFor` short-circuited to `Map.of()` when no row in a batch named a decider, then looked up
> on a **nullable** column — so **every case detail 500'd whenever no case in the batch was
> quarantined**, which is the common path, while the batch that *did* contain one worked perfectly.
> `CaseOwners` carries a comment about exactly this and the new code copied its words, not its
> behaviour. Use `new HashMap<>()` on **both** arms whenever the lookup key can be null.

**New seams §121 phase 3 created — `work`:**
- `work.BoardFacts.quarantinedCases(projectIds)` — the **fourth** generator's fact, native SQL over
  `case_flake` joined to `test_case`. `BoardFacts` is still the one file in this module allowed to
  name a foreign table.
- `work.BoardService.PREFIX_QUARANTINE` (`quarantine:`) + `cardFor(QuarantinedCase…)`. **Not
  `case:`** — a case both flagged and quarantined would otherwise be two cards with one id.
- **`repository.CaseFlakeRepository.findQuarantined` IS DELETED**, and the reason generalises: its
  javadoc named §6l's fourth generator as its caller, and that generator lives in `work`, whose
  `allowedDependencies` are `tenancy`/`project`/`identity` — ArchUnit refuses the import. **A seam
  written ahead of its consumer is a guess about the consumer.** Its `ORDER BY flake_score` was
  wrong for the board too: `BoardService.sorted` re-sorts every card by (overdue, priority, id).
- **`QUARANTINE` is the seventh `WorkItem.kind` and the FIRST that needed a contract change.** §53
  declared six ahead of their generators; this is not one of them. It is deliberately not
  `CASE_MAINTENANCE`, because `BoardPage.describeCard` splits *that* kind's description on commas
  and looks each fragment up in the `HealthSignal` label map — a typed sentence renders as its own
  fragments joined by a middot, with nothing failing on either side.
- Card decisions worth not re-litigating: assignee is the **case owner** (the decider is named in
  the sentence, or the debt lands on whoever quarantined somebody else's test); `TODO`, computed;
  `allowedTransitions` empty; **this is the one derived card that does not clear itself**, because a
  quarantine is authored and a health signal self-heals.
- Fixture: `BoardEndpointTest.Quarantine` (5). **The phase's one red run was an id collision** —
  `…000c` was already `TEAM_ELSEWHERE` eighty lines away, every case helper is
  `ON CONFLICT DO NOTHING`, and the only red test was §84's team-board bound. Sixth instance of
  §19's family; **a collision fails somewhere else.**

**§122 CARRIED NO MIGRATION.** Schema head is still `V38`, `V39` next free, policy count 62 —
every table it touches (`app_user`, `instance_role_assignment`) has existed since `V1`.

**New seams §122 created — `provisioning`:**
- `provisioning.GrantInstanceAdminService.grant(email, actorUserId)` — **the only writer of
  `instance_role_assignment` in `src/main`.** Before this slice there were none: `V3` seeds
  `Role.INSTANCE_ADMIN_ID`, `PermissionEvaluator` resolves the SYSTEM term, the table has existed
  since `V1`, and only three *test* classes had ever called `InstanceRoleAssignment.forUser`.
  **Runs with NO `CompanyContext` open, deliberately** — both tables are on the ERD §0.1 exception
  list, `CompanyAwareTransactionManager.doBegin` treats a context-less transaction as legitimate,
  and opening one would imply an instance-wide power belongs to one client. Contrast
  `IssueInvitationService`, which must resolve its company id before opening the context.
- `provisioning.GrantInstanceAdminResult` — four outcomes. **`ALREADY_HELD` exits 0 where
  `IssueInvitationResult.ALREADY_ACTIVE` exits non-zero**; a grant is a *state*, an invitation is
  an *artifact*. `INVITED` is grantable (`provision-company` leaves its first admin `INVITED`, so
  refusing it makes the first operator uncreatable); `DEACTIVATED` is refused (inert today, widest
  power in the product on reactivation).
- `provisioning.GrantInstanceAdminCommand` — `ApplicationRunner`. **Its verb MUST be in
  `OperatorCommands`**: `TcmsApplication.main:36` reads that set before the context exists to pick
  `WebApplicationType.NONE`, and an unregistered verb boots a web server, so the command succeeds,
  prints, and then hangs for ever. One line, in a second file, invisible to a service-level test.

**§121 MOVED THE SCHEMA HEAD AND THE POLICY COUNT — `V38__case_flake.sql`, `V39` next free, and
policy count 61 → 62.** The first count move since `V31`: `V38` adds a **new company-owned table**,
where `V36`'s was instance-level and `V37`'s was a column on an already-policed one.
`SchemaConventionTest`'s pinned count (`SchemaConventionTest.java:270`) is what turns that into a
decision — it failed on arrival and had to be moved deliberately.

~~**§121 IS HALF SHIPPED. Phases 3 and 4 are NOT BUILT**~~ → **phase 3 shipped (above); only phase 4,
any frontend at all, is left.** One seam still has no caller and that is the state, not an
oversight: `CaseFlakeView` / the `CaseQuarantineInput` contract schema, for the SPA.

**New seams §121 created, in dependency order:**
- `repository.CaseFlake` — entity over `case_flake`. **The only half-derived, half-authored row in
  the product.** Two writers, kept apart by their `ON CONFLICT` / setter surfaces rather than by two
  tables. `unscored()` is the factory the quarantine path uses when the recompute has not run yet.
- `repository.CaseFlakeRepository` — **`@Transactional` WITHOUT `readOnly`**, unlike
  `CaseHealthRepository`: its owner writes through it, and `readOnly = true` would skip the flush so
  the write would silently do nothing while every read assertion still passed. `findScopedById`
  exists because `findById` bypasses the Hibernate company filter (`TestCaseRepository:45`).
- `repository.CaseQuarantineService` — the authored writer. Gated on **`case:edit`**, deliberately
  not `result:submit`; Automation Service does not hold it. `CaseController.quarantine` is the
  `PUT …/cases/{caseId}/quarantine` handler.
- `reporting.ExecutionSource.recomputeFlakeScores` — the derived writer, a **third** statement in
  `ExecutionFactRefreshService.refresh()`, in one transaction after the projection and
  `recomputeFailingRepeatedly`. Reads `execution_fact`, not `test_execution`.
- `reporting.ReportingProperties` gained `flakeWindow` (P90D) and `flakeMinimumTransitions` (4),
  both with compact-constructor bounds; `application.yml` carries the documented defaults.

**⚠ THERE ARE THREE IMPLEMENTATIONS OF §6l's FLAKINESS SENTENCE, AND ONLY TWO MAY AGREE.**
`execution.CaseHistoryService.flakiness` (§49, per-case timeline, Java over rows) and
`reporting.ExecutionSource.recomputeFlakeScores` (§121, SQL over the fact log) both answer *"how
flaky is this case"* and **must** agree on the predicate: `status_kind IN ('PASSED','FAILED')`.
`AggregateExecutionReport.inconsistentCases` (R7) answers a different question and uses
`counts_as_pass` — correctly. **`case_status.kind` and `case_status.counts_as_pass` are independent
columns**, agreeing on all six seeded defaults and diverging on a custom status, so this is a
divergence that ships. Do not "unify" the three.

**§120 MOVED THE SCHEMA HEAD — `V37__case_health_undeclared_automation.sql`** — and **policy count
stayed 61**: it adds one column and two partial indexes to `case_health`, which has been in `V1`'s
`company_owned` array since M0, so the policy is inherited. Five files touched in `repository`, one
new test class, one additive contract enum member. No new endpoint, no new query hook, no screen.
**§119 CARRIED NO MIGRATION** — four new `execution` files, seven new tests inside
`IngestionEndpointTest`, one contract addition (`GET /ingestions`, `GET /ingestions/{jobId}`,
`IngestionJobSummary`).
**§117 CARRIED NO MIGRATION** — sixteen new `execution` files, two test classes, and `V1`'s three
writerless tables went to **zero**.
**§116 is the first M3 slice and it MOVED THE SCHEMA HEAD** — `V36__api_token_lookup.sql`; policy
count stayed 61 (the new table is instance-level, no RLS) and it is the **twelfth** entry on ERD
§0.1's exception list.
**§115 added two files and one test class** — `DocumentationSource`, `CoverageMatrixReport`,
`CoverageMatrixReportTest` — plus contract prose. No migration, entity, endpoint or key.
**⚠ THIS HEADER WAS WRONG IN TWO PLACES AT §115 AND BOTH WERE STALE IN THE SAME DIRECTION** — §95's
"still owed (phases 2–4)" entry below is **closed**. The body claimed work that was already done,
which is the failure mode opposite to the one the trust-the-body rule guards against. Re-grep before
believing a "still owed".
**§114 (R8) touched three files** — `AggregateExecutionReport`, `ExecutionSource`,
`AggregateVersionDivergenceTest` — plus contract prose. **§112 (R7) touched the same three.**
**§101's backend half is phases 1, 2 and 4** — `V34` + `V35`, both rollup writers, the read switch.
**§99's backend half is phase 2 alone** — two request fields, one union query, no migration.
**§98 phase 2 was the backend half of the flattening** — `V33`, the entity, the two services, the
membership seams and a 35-file test-fixture sweep.
**§97 phases 4, 5 and 6 touched NO backend file** — the backend half of that slice is phases 3a–3c.
**Fingerprint files:** `backend/build.gradle.kts`, `backend/gradle/libs.versions.toml`, `backend/gradle/wrapper/gradle-wrapper.properties`

> Authoritative docs win over this file. `CLAUDE.md`, `docs/HANDOFF.md` (an index — sections live in
> `docs/handoff/NN-slug.md`), and the specs listed in `CLAUDE.md`'s precedence table. This is a map
> for re-orienting fast, not a source of truth.

## Version lane

- **Java 21** — `build.gradle.kts:12`, `JavaLanguageVersion.of(21)`. Sealed types, records and
  pattern-matching switch are all in play and idiomatic here.
- **Spring Boot 3.5.6** / Hibernate 6 · **Gradle Kotlin DSL** with a version catalog
  (`gradle/libs.versions.toml`).
- **`-Werror`.** Suppressed lints in `build.gradle.kts` each carry a recorded reason. A new warning
  fails the build.

## Build, run & test

- **THE gate is `./gradlew build`** (compile with `-Werror` + all tests), from `backend/` — never the
  repo root. ~3 min locally. `test` alone is not the gate; `build` catches compile warnings.
- **Every test boots a real `postgres:17` via Testcontainers. Docker required, no H2 lane** — RLS,
  `ltree` and `citext` have no H2 equivalent. `systemProperty("api.version", …)` pins the Docker-29
  API floor (`build.gradle.kts:70`).
- Single class: `./gradlew test --tests 'com.tcms.tenancy.RlsIsolationTest'`.
- **`Clock.systemUTC()` on macOS yields only microsecond precision** (re-measured 2026-08-02:
  20000/20000 samples land on a whole microsecond), so any defect that depends on sub-microsecond
  nanoseconds is invisible to a local run and could only ever fail on Linux CI — which no longer runs
  tests. See `docs/handoff/58-*.md` and `60-*.md`. **A fix for this class is a no-op locally, so a
  green suite is consistent with both the fixed and the unfixed code**; assert the decision, not only
  the behaviour. `#confirmed`

## Layout

Spring Modulith **monolith**; modules are *packages* under `com.tcms`, not Gradle subprojects. **Ten
exist since §86**: `auth · documentation · execution · identity · project · provisioning · reporting ·
repository · tenancy · work`. **`shared` is the only one on plan §3's list that still does not
exist**, and the slice plan's decision E declines to create it for two storage ports and an enum.
`ModuleStructureTest` hard-codes the ten, so an eleventh is a deliberate edit. `#confirmed`

- Cross-module FKs allowed **only** toward `tenancy` / `identity` / `project`; everything else
  crosses via domain events. **Enforced by ArchUnit** (`src/test/java/com/tcms/architecture/`), so
  the boundary is machine-checked. `#confirmed`
- `tenancy` is depended on by everything and depends on nothing.
- No Domain/Application/Infrastructure layering — vertical slices per module, with
  `<X>Controller` / `<X>Service` / `<X>Repository` inside each.

## Seams to reuse

- **`${VAR:?msg}` IN SPRING CONFIG IS A DEFAULT, NOT A REQUIREMENT — §108, and it cost the
  production signing key.** This repo writes both Compose files and Spring config. In
  `compose*.yaml` the form means *"fail if unset"* and is used correctly on eight variables. In
  `application*.yml` **Spring has no such form**: it splits on the first `:` and takes the rest as
  the default value, so `${TCMS_JWT_SECRET:?a JWT signing secret of at least 32 characters is
  required}` silently resolved to the literal 59-character message — long enough to satisfy
  `@NotBlank @Size(min = 32)` on `JwtProperties` — and the deployed instance signed every session
  token with a string committed to this repository.

  **For a required property write `${VAR}` with no colon.** An unresolvable placeholder throws at
  bind time and the context does not start.

  Two things generalise beyond the one line. **`@DynamicPropertySource` hides shipped config**:
  `IntegrationTestBase` sets `tcms.auth.jwt.secret` directly, which replaces the placeholder, so
  1000+ green tests said nothing about the expression that actually ships — anything asserting a
  *resolved value* cannot see a defect in the *text*. And `ConfigConventionTest`
  (`src/test/java/com/tcms/config/`) is the answer: it parses `application*.yml` as source, boots
  no context, needs no database, strips comments first (the mail block legitimately quotes
  `${SMTP_HOST:-}` when explaining compose), and its negative case has been run. **Add config
  invariants there, not to a reading.** `#confirmed`

- **§120 — AUTOMATION DRIFT, AND `case_health`'s FOURTH AND FIFTH SIGNALS.**
  `V37` (**head; `V38` next free; policy count STILL 61**). Five files, all `repository`:
  `ExecutionFacts.recomputeAutomationDrift` (a **second statement**, not a second file — that class
  is the one place this module names an `execution` table), `HealthSignal.UNDECLARED_AUTOMATION`,
  `CaseHealth.undeclaredAutomation`, `CaseHealthProperties.staleAutomation`,
  `CaseHealthRecomputeService`. Tests: `CaseHealthRecomputeTest.AutomationDrift` (8, fixture `ce…`
  extended) and **`CaseHealthAttributesTest`** (2, **no database**).
  - **THE COLUMN WAS WRITERLESS FROM `V1` AND THE BLOCKER WAS REAL.** `stale_automation`'s predicate
    is *"no **ingested** result in N days"*, and until §117 nothing wrote
    `test_run.source = 'INGESTED'`. A computation over that selects the empty set in every
    deployment — §95's `usage_count` and §100's exit criteria. **Same family as §115: re-read a
    plan's "not covered" list against the code, third time.**
  - **BOTH SIGNALS READ ONE FACT ON TWO SIDES OF ONE CUTOFF**, so a case can **never** raise both —
    a property of the statement, not the schema, which is why they are one `INSERT` and not two
    jobs. `theTwoAreMutuallyExclusive` asserts it over the whole fixture.
  - **STALENESS REQUIRES A PREVIOUS INGESTED RESULT (`last_ci.at IS NOT NULL`).** "Stopped" presupposes
    started, and §6c makes `automationKey` optional *precisely so* a case can be `AUTOMATED` before
    its automation exists — the literal predicate fires an **ERROR** on every case the day a team
    declares intent. **The hole is stated**: `AUTOMATED` and never once reported raises nothing.
  - **THE MIRROR IS `<> 'AUTOMATED'`, NOT §6c's `MANUAL`.** `TO_BE_AUTOMATED` means the backlog is
    counting done work; `NOT_AUTOMATABLE` is the loudest, because coverage is
    `AUTOMATED / (total − NOT_AUTOMATABLE)` and a wrong one moves the **denominator**.
  - **THE MIRROR IS TIME-BOUNDED TOO, AND THAT WAS A REVERSAL.** Unbounded it can never go false —
    a case returned to manual after its automation was deleted stays flagged for ever, which is
    `HealthSignal`'s no-button-for-lying rule from the other side. One cutoff (#30's 14 days, and
    #34's "one figure to remember").
  - **NO `deleted_at` PREDICATE ON THE RUN, FOR AGREEMENT NOT CORRECTNESS.** The sibling statement
    counts every recorded execution; two neighbours disagreeing about what a result is surfaces as a
    case both `NEVER_EXECUTED` and `STALE_AUTOMATION`.
  - **`ON CONFLICT` NAMES THREE COLUMNS, AND THE RULE NOW BITES BOTH WAYS.**
    `reporting.ExecutionSource` writes `failing_repeatedly` into the **same rows** on its own
    schedule, so setting every column would blank a *live* sibling's work, not a hypothetical one's.
  - **A MUTATION ATTEMPTED THE WRONG WAY TAUGHT THE DURABLE THING.** Deleting `IS NOT NULL` outright
    reddens **all 19** — a NULL boolean into a `NOT NULL` column **aborts** the recompute, a crash
    that measures nothing. Mutating to the **rival** implementation (§6c read literally) reddens
    exactly one. **Mutate to the competing implementation, not to nothing.** `DR-1` (dropping
    `r.source`) reddens 5, and the extra four are the right four.
  - **`CaseHealthAttributesTest` DID NOT EXIST AND `HealthSignal`'s JAVADOC SAID IT DID** (§47 →
    §120). §100's rule exactly. `attribute()` is a **string** for Criteria, so a typo compiles,
    passes `-Werror` and `validate`, and throws only on the one path that filters — the chip, the
    tile and `signals()` all keep rendering off the `switch`.
  - **TWO STALE CLAIMS IN `HealthSignal`'s HEADER CORRECTED.** "Nothing populates it yet" (three
    computations had landed), and the `INCOMPLETE` paragraph: the **contract lists it and the SPA
    labels it**, so the two enums are not the same set — the wire is a superset by one member this
    server can never send. Left as it is; both tidyings are worse.
  - **FIVE SIGNALS STILL HAVE NO WRITER** — `OUTDATED_SPEC_CHANGED`, `STALE_APPROVAL`,
    `CONDITION_DRIFT`, `UNLINKED`, `ORPHANED`. Each is now the shape this slice was. `UNLINKED` is
    cheapest: `doc_link` has had a writer since §93.

- **§119 — THE INGESTION HISTORY READS.** `IngestionReadService` (`list`/`findOne`, both
  `readVisible` — membership, no new key), `IngestionJobSummary`, `IngestionJobRow`,
  `UnmatchedTests`. Two `GET`s on `IngestionController`; the second is the resource
  `POST /ingestions`' `Location` header has named since it shipped, at which there had never been a
  `GET`.

  **The list and the detail serve DIFFERENT shapes and must stay that way.**
  `IngestionJobRepository.findSummariesInProject` is a **JPQL constructor projection** into
  `IngestionJobRow`, so `raw_payload` — a `JSONB` blob, megabytes for a 5,000-test report — is never
  selected. `IngestionJobSummary` therefore has no `unmatched` field at all rather than an empty one:
  an empty array beside `unmatchedCount: 12` reads as *"the list was cut"*, which is the meaning that
  field is documented to carry. Reuniting them is a one-line temptation and it is mutation `IH-5`,
  which **no test can catch** — the check is the query log.

  `UnmatchedTests` re-reads the names out of the payload (`caseId` absent, or `recorded: false`) and
  is a **second implementation of `IngestionService.unmatchedIn`**, legal only because
  `IngestionEndpointTest.History.theDetailAgreesWithThePost` asserts the two against each other. It
  also repaired the **replay**, which served `unmatched: []` beside a non-zero count.
  `IngestionService.cap` is shared with it so the 50-name bound is not applied twice.

  `TestRunRepository.findRunKeys` is `findPlanKeys`' shape for run keys — one query per page, and
  `deletedAt IS NULL` so a deleted run yields a null key beside a non-null `runId`, which is what
  the summary tells "matched nothing" apart from "run deleted" by.

  **The `d7` fixture now seeds a SECOND PROJECT** (`SAVINGS`) and asserts that its job id is a 404
  through `findOneInProject`. That is the two-project fixture the project-predicate-over-RLS family
  has wanted since §115's CM-6; one of the seven files now has a test behind it, and the pattern to
  copy is `seedJobInSavings`.

- **§117 — CI RESULT INGESTION, AND `V1`'s LAST TWO WRITERLESS TABLES GET WRITERS.**
  **NO migration** (`V36` head, `V37` free, 61 policies). Sixteen files, all `execution`:
  `ReportFormat` · `IngestedOutcome` · `IngestedResult` · `ReportXml` · `ReportJson` ·
  `ReportParser` + four parsers + `ReportParsers` · `IngestionJob`/`IngestionStatus`/
  `IngestionJobRepository` · `AutomationKeyMapping`/`AutomationKeyMappingRepository` ·
  `AutomationMatcher` · `IngestionEdit` · `IngestionService` · `IngestionController` ·
  `IngestionJobView`/`UnmatchedTestView` · `AutomationMappingService`/`…Controller`/`…View`/
  `…Request`. Fixture prefix **`d7`** (`IngestionEndpointTest`, 21) plus **`ReportParserTest`**
  (17, **no database** — the parsers are pure).
  - **`ReportXml` IS THE ONLY `DocumentBuilderFactory` IN THE APPLICATION, and that is the whole
    defence.** This endpoint takes XML over the network from a credential holder; default JAXP
    inlines `file:///etc/tcms/tcms.env` into a test name, which is stored, matched and served back.
    A second construction site is the entire vulnerability and is what somebody adding a fifth
    format will write. **THE FLAG OVERLAP WAS MEASURED AND IT CORRECTED THE HEADER**: dropping
    `disallow-doctype-decl` leaves the external-entity test **GREEN** (the `external-*-entities`
    pair and `ACCESS_EXTERNAL_DTD` refuse the file read alone) and reddens only the
    billion-laughs test. Entity pair ⇒ disclosure; DOCTYPE ban ⇒ DoS. **Neither is redundant.**
  - **EACH PARSER HAS ONE SHAPE THAT READS A RED SUITE AS GREEN, and they are four different
    bugs.** JUnit: a pass says *nothing*, so the outcome is an ABSENCE — `getElementsByTagName`
    searches descendants, so a suite-level `<error>` lands on the first case. TestNG: an
    unrecognised `status` falling through to passed, and `is-config="true"` ingesting a
    `@BeforeSuite` as a test. Allure: `broken`/`unknown` are real statuses and neither is a pass.
    **Cucumber is the big one** — a scenario is the WORST of its steps, and "take the last step"
    reports a mid-way failure as skipped because Cucumber skips everything after a failure.
  - **`IngestedOutcome.severity()` HAS TWO CALLERS ON PURPOSE** — the Cucumber scenario reduction
    and `IngestionService`'s several-tests-one-case reduction. Same question at two altitudes; the
    copy is where one ranks a skip above a failure.
  - **THE LADDER IS THREE BATCHED PASSES, NEVER A CHAIN PER TEST.** 3 queries for a 5,000-test
    report, not 15,000 — the shape that works on every fixture and times out on the first customer.
    **Each rung is tested ALONE** (only case 1 has an `automation_key`, case 2 only a display key in
    its name, case 3 only a mapping): a ladder collapsed to rung 1 keeps its matched count and
    passes a report exercising all three. Mutation IN-3 confirms it.
  - **STEP 2's PATTERN IS FIXED — a stated departure from §6c's "configurable regex per
    project".** `\b([A-Z][A-Z0-9]{0,9}-\d+)\b`, looked up against **this project's**
    `test_case.key`. That bound is what makes a fixed pattern safe: a false extraction
    (`HTTP-404`) costs one `IN` entry and matches nothing, where a wrong *configurable* regex could
    match a real key. **A name yielding two distinct keys matches NEITHER.**
  - **MATCHING IS BOUNDED TO THE PROJECT though `automation_key` is company-unique.** A sibling
    project's case would put a `test_execution` and its `test_case` in two projects. Company scope
    still buys unambiguity, so the bound cannot silently pick the wrong one of two.
  - **NOTHING IS AUTO-CREATED FOR AN UNMATCHED TEST** (§6c's optional stub, declined): a CI job that
    can mint cases turns every test RENAME into a permanent duplicate — the identity problem
    `automationKey` exists to solve. **That is a design rather than a gap only because
    `automation_key_mapping` got its writer in the same slice**, and the response serves
    `normalizedName` so no client re-implements the one rule that must agree exactly.
  - **THE MAPPING WRITE COSTS `case:edit`, AND THAT IS THE FEATURE'S SECURITY PROPERTY.** §A6 gives
    Automation Service `result:submit` + `run:create` and NOT `case:edit`, so **a leaked token can
    post results and cannot re-point where anybody's results land** — otherwise it could bind every
    failing test to a throwaway case and launder a red suite green. No new key (§67, eleventh time).
    Re-confirming **MOVES** the row; a 409 makes correcting a mis-binding a delete-then-create with
    a silent non-matching window.
  - **`TestRun.create` GAINED A `source` PARAMETER AND ITS HEADER HAD ALREADY SPECIFIED THE RULE**:
    the DOOR the request came through, never the caller's role. `RunCreateService` passes `MANUAL`
    even for an Automation Service token; only `IngestionService` passes `INGESTED`.
    **`RunCreateService.createIngested` exists so ingestion grows NO second run-creation path** —
    a copy would be a second implementation of the pin.
  - **§116's SELF-INVOKED-`@Transactional` DEFECT WAS WRITTEN AND CORRECTED BEFORE IT RAN.** The
    parse must be outside the transaction (a megabyte of XML under a held connection), so the
    transaction starts mid-method — and a private `@Transactional` is not intercepted, which under
    `CompanyAwareTransactionManager` means no GUC and zero rows everywhere. Here the symptom is the
    idempotency lookup finding nothing, so **every** retry ingests twice. `TransactionTemplate`.
  - **A MALFORMED REPORT WRITES NO ROW**, because a `FAILED` row consumes the idempotency key and
    the corrected retry would replay the failure for ever. So `PENDING`/`PROCESSING`/`FAILED` have
    **no writer** in this lane — the async lane's, `CHECK` left wide (§93's reason, with the
    distinction noted: these name real states where `LinkSourceType`'s extra arms name nothing).
  - **A REPORT MATCHING NOTHING OPENS NO RUN** — a run of zero cases reads 0 of 0, 100%, complete
    (§99 one screen over), and it is the FIRST report a team posts that most often matches nothing.
  - **`CaseFacts` GREW THREE METHODS** (`caseIdsByAutomationKey`, `caseIdsByDisplayKey`,
    `caseKeys`) — the seam stays the one file naming a `repository` table. **The
    project-predicate-over-RLS gap is now SEVEN files** and is overdue; the fix is still one
    two-project fixture written once for the seam (§115's CM-6).
  - **EIGHT MUTATIONS WERE RUN — a stated §65 deviation, because both classes went green first
    time**, which is §114's own weakness. Each reddened its intended assertion and only that one,
    except IN-1, which corrected the code. Four more are queued. **No browser rows: there is no
    frontend surface**, and the two screens the slice names (ingestion history, unmatched-test
    binder) are recorded in the browser queue as unbuilt.

- **§116 — API TOKENS, AND THE FIRST CREDENTIAL RESOLVED BEFORE A COMPANY EXISTS.**
  `V36__api_token_lookup.sql` (**head; `V37` next free; policy count STILL 61** — the new table is
  instance-level). `ApiToken` · `ApiTokenLookup` · two repositories · `ApiTokenSecrets` ·
  `ApiTokenService` · `ApiTokenController` · `ApiTokenView` · `IssuedApiTokenView` ·
  `ApiTokenIssueRequest` · `TokenContext` (all `identity`), plus `ApiTokenAuthenticationService` /
  `ApiTokenAuthenticationFilter` and a `BearerTokenResolver` bean (`auth`). Fixture prefixes **`9d`**
  (`ApiTokenEndpointTest`, 26) and **`9e`** (`ApiTokenAuthenticationTest`, 13).
  - **`api_token` IS UNDER RLS AND THE CREDENTIAL ARRIVES BEFORE ANY CONTEXT.** That lookup returns
    zero rows — `V7`'s defect — so `api_token_lookup` is instance-level and holds a **digest, a
    token id and a company id and nothing else**. `V1`'s `api_token.token_hash` is **dropped**, not
    duplicated: one home for a credential digest. Lifecycle (`expires_at`, `revoked_at`) stays on
    the policed row and is checked **after** the context opens.
  - **THE CONTEXT MUST BE OPENED OUTSIDE THE TRANSACTION THAT READS THE POLICED ROW.**
    `CompanyAwareTransactionManager` sets the filter and the GUC in **`doBegin`**, so a transaction
    that *begins* without a context runs to completion without one however many `CompanyContext.open`
    calls happen inside it. The first draft was one `@Transactional` method and **every valid token
    401'd**; the fix is two `TransactionTemplate` calls (self-invocation would not have helped —
    `ReportJobRunner`'s argument). **This generalises to every future pre-auth lookup.**
  - **A TOKEN'S PERMISSIONS ARE AN INTERSECTION, IN `PermissionEvaluator` AND NOWHERE ELSE.**
    `cappedToToken` narrows the owner's answer by `RoleRepository.findHeldPermissionsForRole`, and
    `TokenContext`'s `projectId` is a hard bound applied **before** the project lookup. Never a
    union; never a second resolution beside the evaluator (`SessionTokens`' §6b argument).
    `PermissionQualifier.narrowedBy` is the **meet** to `strongest`'s join, and `OWN`∧`PLAN_SCOPE`
    answers `NONE` where the join **throws** — a narrowing that cannot be expressed has a safe
    answer and a widening does not.
  - **THE FILTER PRODUCES A `Jwt` VALUE**, carrying `sub` (the **owner**), `co`, and `tkn`. That is
    what lets every existing controller keep `@AuthenticationPrincipal Jwt` unchanged and lets
    `CompanyContextFilter` open the company by one path. It carries **no grant** — nothing signs it.
    `PermissionVersionFilter` branches on `tkn`: **no epoch** (the row is read every request), **yes
    account gate** (deactivation must kill issued credentials).
  - **WITHOUT THE `BearerTokenResolver` BEAN a valid token 401s before the filter is reached** — the
    resource server parses `tcms_…` as a JWT and fails. Right credential, wrong-credential symptom.
  - **`api_token.name`'s per-company uniqueness is named in ERD §0.1 point 3 and `V1` NEVER CREATED
    IT.** `SchemaConventionTest` asserts `company_id` leads every index *that exists*, which is
    silent about one that does not. `V36` adds it **partial on `revoked_at IS NULL`** so rotation can
    reuse the name. **Check for the missing index, not only the wrong one.**
  - `AdminReach` grew a fifth term, `issueTokens` — separate from `companyWide`/`manageTeams` for
    that record's own recorded reason. `token:issue`'s **first consumer**, the fifth permission to
    be correct-and-inert (§54, §62, §64, §69).
  - ~~**`ingestion_job` and `automation_key_mapping` STILL HAVE NO WRITER.** M3 slice 2.~~ →
    **closed by §117**, above. `V1`'s writerless tables are now zero.

- **§115 — THE COVERAGE MATRIX, AND `reporting`'s SECOND FOREIGN-MODULE SEAM.**
  `CoverageMatrixReport` (`type()` = `coverage_matrix`) + **`DocumentationSource`** (`doc_page` ·
  `doc_block` · `doc_link`). **No migration, no table, no column, no key**; contract change is prose
  (`gen:api` moved JSDoc, no type). `CoverageMatrixReportTest`, prefix **`c5`**, slug `cvm-`, 18
  tests. Requestable **by existing** — `ReportComputations` is a bean registry, so no controller,
  validation or list was edited.
  - **THE BLOCKER WAS GONE AND NOBODY HAD NOTICED.** M2.5's plan §8 lists the coverage matrix and
    doc-structured reports as blocked on *"`doc_link` having a writer, which it does not"*. §93's
    `BlockLinkService` is that writer, and `DocBlockView`'s header already carried the supersession.
    **Re-read a plan's own "not covered" list against the code before treating it as current.**
  - **ONE SEAM PER FOREIGN MODULE, NOT ONE PER MODULE.** `ExecutionSource` already names seven
    tables across three modules; a fourth module's three would make the file whose purpose is to keep
    the coupling reviewable unreviewable. **Case labels stayed on `ExecutionSource`** (`test_case` is
    `repository`'s and R7 already serves them) — a second copy would be one table read two ways in
    one module.
  - **THE PROJECT BOUND IS `findPage`'s ALONE.** `doc_block` and `doc_link` have no `project_id`, so
    the page is the only route to one. The other two methods take the id `findPage` returned and are
    package-private for that reason: **called with a page id from anywhere else they read a sibling
    project's document.** This is the **sixth** file layering a project predicate over RLS with
    **nothing checking any of them** (`PlanFacts`, `CatalogueFacts`, `ExecutionSource`×3). CM-6 is
    the third slice to record it as a gap; the fix is **one two-project fixture written once for the
    seam**, not a row per method.
  - **A STATEMENT IS A `list` BLOCK OR ANY BLOCK ALREADY CARRYING A `VERIFIES` LINK — never every
    block.** §93 made `level` a third arm on `list` so an outline is linkable per bullet, so the
    bullets are what a document intends to be verifiable. Every-block is `DocBlockView`'s
    zeroed-marker objection in an auditor's document: it marks a specification's prose as untested
    requirements. Consequence: **a page of prose reports 0 statements and an em-dash coverage**, not
    `0.0%`.
  - **`VERIFIES` ONLY.** `REFERENCES` is *"context, not coverage"* and `DERIVED_FROM` claims no
    proof; counting either is the exact false claim `LinkRelationship`'s header exists to prevent,
    and it is **one word** in the predicate. Also `source_type = 'TEST_CASE'`: the CHECK permits
    `SUITE`, which is a claim about a collection.
  - **THE LADDER IS PESSIMISTIC AND ITS TWO ABSENCES ARE DIFFERENT PROBLEMS.** Not covered →
    **Failing** (any linked case whose latest result is not a pass) → Not run → Verified. Failing
    outranks verified per §46; *nobody tested this* and *this fails* have different owners.
  - **THE POLICY IS FIXED TO `LATEST` AND SAID SO IN THE METADATA** (decision D). A coverage matrix
    asks *"is this verified now"*, which has one answer; `WORST` would print a failure fixed three
    weeks ago against a requirement. The `ORDER BY` is `resolvedCte`'s `LATEST` arm clause for clause
    (`NULLS LAST`, `execution_id` tiebreak) — **check both when either moves.**
  - **FIVE MUTATIONS WERE RUN — a stated §65 deviation, because the class went 18/18 GREEN FIRST
    TIME**, which is §114's own weakness. Each reddened the test written for it, three of them
    *alone*. `openDefects` (M4) and the doc **subtree** are named as not built. `DocBlockView.coverage`
    is **still absent and its reason has not moved.**

- **R8 (§114) — VERSION DIVERGENCE, AND M2.5's REPORTING PLAN IS NOW COMPLETE (R1–R8, F1–F2).**
  A sixth section on `AggregateExecutionReport`, over `selectedCte` for R7's reason exactly. **No
  migration, no key, contract change is prose** (`gen:api` moved 14 lines of JSDoc, no type);
  `AggregateVersionDivergenceTest`, prefix **`c4`**, slug `avd-`, 13 tests.
  - **THE PREDICATE IS `count(DISTINCT case_version_id) > 1` AND SAYS NOTHING ABOUT OUTCOMES**, so
    this section is strictly **wider** than R7's *Across versions* rows. A case that failed at v1 and
    again at v2 disagrees with nothing and is still two tests averaged as one — §6e's harm is the
    blending, not the disagreement.
  - **WHAT DIVERGENCE COSTS IS A PROPERTY OF THE POLICY, AND IT IS COMPUTED, NEVER ASSERTED.** Four
    different consequences (`ALL` blends · `LATEST` discards · `WORST`/`BEST` can keep a failure
    against rewritten text · `LATEST_PER_CONFIG` does both), so each row carries a `Counted in the
    totals` column — an `EXISTS` against `resolved` — and the narrative's closing sentence follows
    **`blendsVersions(rows)`, never `policy == ALL`**. A sentence keyed on the policy's name is a
    second implementation of the policy in English, where no reader can check it.
  - `versionDivergence` is **the one query in the class reading BOTH CTEs**: what diverged from
    `selected`, what the totals did with it from `resolved`.
  - **`ExecutionSource.caseVersionNumbers(projectId, versionIds)`** — the seam's third method,
    taking its table count to **seven**. `execution_fact` grows **no `case_version_no` column**:
    R7's mutability argument does not apply (the number is immutable), it is refused because a star
    schema duplicates the dimensions it aggregates *by* and nothing groups by version number.
  - **THIS SLICE HAS NO RED RUN** — tests written after the implementation, all 13 green first time.
    The substitute is structural (`theFixtureHolds` proves the resolved set is one row per case), and
    **R8-1 in the mutation queue is the only route to a measurement**. Seven mutation rows, four
    browser rows (`M6-30`…`M6-33`), none run.

- **R7 (§112) — WHICH CTE A SECTION READS IS THE WHOLE SLICE, AND `selectedCte` IS THE NEW SEAM.**
  `AggregateExecutionReport` grew three sections — *Inconsistent results*, *Manual and automated*,
  *First attempt and retries* — and `resolvedCte` was split so **`selectedCte`** (narrowed, **no
  policy applied**) is a named method. Three sections read each. **No migration, no key, no contract
  change beyond prose** (`gen:api` moved 19 lines of JSDoc and no type); `AggregateVariabilityTest`,
  prefix **`c3`**, slug `avr-`, 15 tests.
  - **A SECTION READING `resolvedCte` HERE IS PERMANENTLY EMPTY AND LOOKS FINE.** Under `LATEST`
    (the default) each case is one row: it cannot disagree with itself, its first attempt is its
    last, and its retries are already discarded. **The policy exists to hide exactly what these
    sections are about.** Grep for `selectedCte` before adding a fourth section and decide
    deliberately; the wrong choice is one word and silently ships a reassuring sentence.
  - **The inconsistency predicate is `passed > 0 AND failed > 0`, NOT the plan's
    `count(DISTINCT counts_as_pass) > 1`** — a recorded departure. The plan's shape lists a
    pass-then-BLOCKED case as flaky (§105's rule, one report over).
  - **A cell is (case, configuration, case version)** — §6p: alternation across configuration or
    version *"is just change, and reporting it as flake is a false signal"*. Used twice: the
    callout's `bool_or` per cell (the *Disagreement* column) and the retry section's partition. The
    retry counts therefore **do not sum to the distinct-case count**, which the column heading says.
  - **The split is a CROSS-TAB of two columns that both look like the answer** —
    `automation_status` is the **case's** (frozen, §86), `source` is the **result's**. Rows are
    **alphabetical**, because `AutomationStatus` (`repository`) and `RunSource` (`execution`) are
    both unimportable here; `statuses`' declaration-order rule is unavailable and alphabetical is
    what its argument actually needs.
  - **`ExecutionSource.caseLabels(projectId, ids)` closes the gap `RunSummaryReport`'s header has
    named since §87** (*"the per-case list waits for the slice that decides where case labels come
    from"*). In the seam beside `findRun`, same argument; **no `deleted_at` predicate**, bounded by
    `project_id` (the fourth file to layer that over RLS — and **untested here**, R7-6), and
    `execution_fact` deliberately grows **no `case_key` column**: both labels are mutable, and an
    append-only log would print names that no longer exist.

- **R6's AGGREGATE REPORT SHIPS THE AGGREGATION POLICY A SECOND TIME, IN SQL (phase 1).**
  `reporting.AggregateExecutionReport` (`type()` = `aggregate_execution`) + `AggregateSelection` +
  a duplicated `reporting.AggregationPolicy`. **No migration — `V35` still head, `V36` still free**;
  policy count **61**; no contract change in phase 1; no new key. It became requestable **by
  existing**: `ReportComputations` is a registry over every `ReportComputation` bean, so nothing in
  the controller, the validation or a list was edited — which is what R3 built that seam for.
  - **The enum is duplicated and the ALGORITHM is not** (slice-plan decision D). ERD §0.2 forbids
    importing `execution`'s, and decision E declines a `shared` module for one enum. The policy is
    resolved in **one window function over `execution_fact`**; `PlanStatisticsService.resolve` stays
    the only Java copy. The `ORDER BY` is written to line up with `resolve`'s comparator clause by
    clause and the class header carries the mapping table — **check it whenever either side moves.**
  - **`AggregateExecutionCrossCheckTest` is what makes the duplication legal**, asserting the two
    against **each other** over five policies. **Prefix `c1`, slug `aer-`.**
  - **A MUTANT FOUND THE FIXTURE VACUOUS AND THE REPAIR IS LOAD-BEARING.** Inverting `LATEST` to
    ASC — each case resolved to its *oldest* result — passed the whole class, because the fixture's
    two multi-result cases were exact mirrors (fail→pass, pass→fail) and reversing time swapped
    *which* case passed without moving a single total. `UNBLOCKED` (blocked→passed) is the row that
    breaks the symmetry. **Do not trim it.**
  - **THREE ASYMMETRIES WITH `PlanStatisticsService`, ALL DELIBERATE.** (1) the fact log has no
    *unrecorded* rows and `resolve` does, so the two agree on **recorded** results only; (2) `notRun`
    is scope-minus-touched and a star schema has no scope, so this report's denominator is
    *contributing executions*, named in the label and the narrative; (3) **`UUID` orders differently
    in Java (signed halves) and Postgres (unsigned bytes)**, so the `execution_id` tiebreak can pick
    different rows — reachable as a *count* only under `LATEST`/`LATEST_PER_CONFIG` with tied
    timestamps and differing statuses. Fixture timestamps are distinct for exactly that reason.
  - **`LEFT JOIN LATERAL unnest(...)` with an explicit empty-array arm** for failures-by-feature: a
    plain `unnest` drops the unclassified row and the section then reads as a project that
    classified everything — §96's `JOIN suite` defect in a second shape. Labels come from
    `ClassificationValueRepository` (`project` is an allowed dependency), **not** a SQL join, so
    `execution_fact` stays the only table the class names; `findAllById` on purpose, because a
    retired feature still named last quarter's failures.

- **THE M2 ROLLUPS HAVE WRITERS SINCE §101, AND THE TWO ARE NOT THE SAME SHAPE.**
  `reporting.RollupRefreshService` (a sibling of `ExecutionFactRefreshService`, driven by the same
  15-minute company loop in `ExecutionFactScheduler`, fact log **first**) runs four statements in
  `ExecutionSource`. **Migrations now run to `V35`; `V36` is the next free number.** Policy count
  still **61** — both tables were already in `V1`'s `company_owned` array.
  - `suite_run_summary` is **HISTORY**: `insertNewSuiteRunSummaries` (`ON CONFLICT DO NOTHING`,
    capturing `membership_snapshot` + `suite_version_no`) and `correctSuiteRunCounts` (tallying over
    **the row's own stored snapshot**). **The freeze is structural, not a set-list** — the correcting
    statement cannot reach the frozen columns. **Nothing deletes.** Re-deriving membership would
    rewrite a past run's pass rate the day somebody adds a tag (§6p open question #32).
  - `plan_snapshot` is **CURRENT STATE**: `refreshPlanSnapshot` (`DISTINCT ON` = the conflict target,
    `e.id` tiebreak, **every column moves** on conflict) **plus `prunePlanSnapshot`, which its sibling
    must never grow** — written as the insert's scope predicate negated, so the pair is total by
    construction. Without it a plan's totals stay too high for ever with no row that looks wrong.
  - **`ExecutionSource` holds a THIRD copy of `memberOf`'s three-arm union.** Held to
    `SuiteMembership` by `SuiteRunSummaryRefreshTest.Agreement`, through the `membership_snapshot`
    column the shipping statement writes — not through a resolution method added for the test.
- **`execution.RollupFacts` IS THE MODULE'S SECOND SEAM (§101)** — `suiteRunSummaries`,
  `runsWithoutSummary`, `planSnapshot`, `planTrend`. **The rule was never one seam per module but one
  per FOREIGN module**: `CaseFacts` reads `repository`, this reads `reporting`. **The `reporting` edge
  runs both ways at the TABLE level and in neither direction at the Java level**, which is the only
  level ArchUnit sees; `RollupAgreementTest` is what holds the two sides to each other.
  - **A ROLLUP IS USED ONLY WHEN IT IS COMPLETE, AND THAT IS A DEFECT REPAIR.** `servedFromRollup`
    (the policy) is **not sufficient** — it says what the rollup *can* serve, not what it *has* been
    written for. `snapshotIsComplete` is the second condition; without it a cold `plan_snapshot`
    reported **every case as not-run**, rendering as a plan nobody had started. Falling back to the
    live path is the safe direction: the failure it replaces is a wrong number, the cost is a slow
    correct one.
  - **The suite history is rollup ∪ TAIL.** `runsWithoutSummary` is not an optimisation — the rollup
    alone would **drop** a run recorded since the last tick, which is an absence rather than a stale
    number and something the live path never did.
  - **`plan_snapshot` serves 2 of 5 policies.** `LATEST` / `LATEST_PER_CONFIG` only; `WORST`, `BEST`
    and `ALL` need every execution and stay live, saying so with `computedAt: null`.
  - **Neither rollup may answer a denominator** — coverage, not-run and the scope size still come
    from `CaseFacts.casesInPlanScope` (§46, §6p).
  - **The unrecorded half stays live** (`TestExecutionRepository.findUnrecordedForPlan`). Bounded by
    (cases × configurations); dropping it makes a configuration with an opened-but-unrecorded run
    **vanish from `byConfiguration`**.
  - **The trend is written twice on purpose** — `plan_snapshot` is per case, a trend is per run, so
    the rollup path reads `execution_fact`. Held by the agreement test.
- **`computedAt` IS THE OLDEST `updated_at`, NEVER THE NEWEST (§101).** Both responses mix rolled-up
  rows with a live tail, so the newest is a claim true of one row and false of the others — false in
  the reassuring direction. Null on the wire means **computed live**, which is a positive statement;
  the contract marks it required for that reason. **Distinct from `PlanStatistics.stale`**: that is
  about the EXECUTIONS, this is about the ARITHMETIC.
- **A RUN'S SCOPE IS THREE ARMS SINCE §99 — `caseIds` ∪ `suiteIds` ∪ `tags`, RESOLVED ONCE.**
  `CaseFacts.caseIdsInRunScope(projectId, caseIds, suiteIds, tags)` is the whole resolution: one
  query, three `OR`s over one `test_case` scan, ordered by `key_seq`, **each arm appended to the SQL
  only when non-empty** (`IN ()` is a syntax error; `IN (NULL)` compiles and is never true). No
  migration — ~~`V33` is still the head, `V34` still free~~ → **`V35` is the head since §101**; policy count still 61.
  **THE SUITE ARM IS `memberOf` AND NEVER `c.suite_id IN (…)`** — a suite defined by a tag rule files
  nothing, so the column reading resolves it to the empty set and produces a run of zero cases that
  renders as 0 of 0, 100%, complete. Same class as §98's grid-vs-count defect, one screen over.
  **§82's approval gate needed no edit**: it binds on `edit.caseIds()`, the resolved union, so a new
  arm is gated by construction. Keep it that way — the moment anything gates a *request field*
  instead, every arm needs its own remembering.
  **The arms SUBSTITUTE for the plan's scope, never union with it** (`RunEdit`'s rule since M2).
  A run resolves **once**, at create; a plan's scope is references re-resolved on every read — the
  two are described in the same words and are not the same thing.
  **Unknown case/suite id = field error; unknown TAG = accepted** (no vocabulary to check against —
  `SuiteEdit`'s rule). The emptiness check is what catches a typo, and its message lands on **every
  arm the caller used**, `caseIds` only as the fallback for a body naming no scope at all.
  `RunEdit.MAX_TAG` is a third copy of `CaseEdit`'s cap, spelled out for `APPROVE_CASE`'s reason.
- **A SUITE IS FLAT SINCE §98 — `parent_id`, `path` and `suite_path_idx` ARE GONE (`V33`; ~~the
  head; `V34` next free~~ → **`V35` is the head since §101, `V36` next free**; policy count still 61).** A suite is a named collection, not a folder: no
  `Suite.getParentId()`/`getPath()`, no `parentId` on `SuiteView`/`SuiteWriteRequest`,
  `SuiteEdit.validateCreate`+`validateRename` collapsed to one `validate`, and
  `SuiteRepository.findLiveForProject` orders by **`keySeq`** (it ordered by `path` to make the flat
  payload constructible into a tree; what an order must still be is *total and stable*, which a name
  is not). `countLiveChildren` + `countLiveCasesInSubtree` collapsed to **`countLiveCasesFiledIn`**.
  **THE DELETE GATE IS THE FILED ARM ALONE AND THAT IS THE NARROWER OF TWO READINGS** — a case
  merely added or tag-matched lives elsewhere, so deleting the collection takes a claim and never a
  case.
  **THE DEFECT §98 FOUND PREDATES IT BY TWO SLICES: `caseCount` and the grid's `?suiteId=` filter
  answered different questions.** The count has been the §96 union since §96 while
  `CaseSpecifications.suiteScope` matched `suite_id IN (subtree)` — the FILED arm — so a tag-defined
  suite showed a count of twelve over a grid of none. `CaseScope` now carries **`memberCaseIds`**,
  resolved once by `CaseService` through `SuiteMembership.caseIdsIn`, and the predicate is on the
  case's own id. **Check both numbers together when either moves.**
  **`execution_fact.suite_path` OUTLIVED `suite.path` DELIBERATELY.** It is frozen history — where a
  case was filed *when it ran* — so `V33` touches no fact row and old rows keep dotted paths that
  describe a hierarchy that no longer exists. `ExecutionSource` writes **`('s' || s.key_seq)::ltree`**
  for new rows, which is exactly the path a *root* suite always had, and every suite is a root now.
  Do not "fix" a report that prints both shapes. The **`ltree` extension stays** — `doc_page.path`,
  `classification_value.path` and this column still use it.
  **`CaseFacts.inSubtreeOf` → `memberOf`, and `SuiteScopeAgreementTest` SURVIVES** — the §98 plan
  expected it retired, but the duplicated predicate is caused by ERD §0.2's module boundary, not by
  the subtree, so the two are now supposed to be *identical* rather than merely equivalent.
  `CaseConditions.ancestry` is a lookup rather than a walk: a suite's conditions reach the cases
  **filed** in it and deliberately **not** the other two arms.
- **A SUITE IS A RESOLVABLE COLLECTION SINCE §96 phase 1, AND A CASE MAY BE FILED NOWHERE.**
  ~~`V31__suite_membership.sql` (**head**, `V32` next free)~~ → ~~`V32` is the head since §97 phase
  3a~~ → ~~**`V33` is the head since §98; `V34` next free**~~ → **`V35` is the head since §101; `V36` next free. Policy count stays 61** — neither adds a table. `V31` (**policy count 59 →
  61**) creates
  `suite_case` (added individually) and `suite_tag_rule` (matched by tag, **unioned never
  intersected**), and relaxes `test_case.suite_id` to nullable — so membership is three arms:
  filed ∪ added ∪ tagged. `TestCase.suiteId`'s mapping lost its `nullable = false`.
  **THE LINE THAT IS SILENT WHEN WRONG IS `ExecutionSource`'s SUITE JOIN.** It is a `LEFT JOIN`
  since this phase, and `execution_fact.suite_id`/`suite_path` are nullable to match. With the inner
  spelling an unfiled case's **whole fact row** — not its suite columns — is never inserted: no
  error, a tick that logs success, and every report over the log quietly short. `RunSummaryReport`
  prints the NULL group as `RunSummaryReport.NOT_FILED` (`"(not filed)"`) rather than dropping it,
  which would be the same defect one layer up.
  `SuiteRepository.countLiveCasesBySuite` gained `AND tc.suite_id IS NOT NULL`: it counts what is
  **filed**, and the other two arms are resolved separately rather than summed into that number.
  ~~Still true after this phase: nothing can yet create an unfiled case through the API~~ →
  **phase 2 opened it.**
- **THE CASE SIDE OF §96 (phase 2) — `suiteId` IS OPTIONAL AND `tags` ARE WRITABLE.**
  `TestCaseInput.suiteId` is nullable (absent leaves the case where it is, **null unfiles it**);
  `TestCaseSummary.suiteId`/`suiteName` are nullable **together**, as are `PlanCaseRef`'s and
  `PlanStatistics.bySuite`'s three. `CaseUpdateRequest`'s refused list is down to **two** —
  `ownerId` and `automationTags` — and `automationTags` staying is deliberate: `case_automation_tag`
  is CI selection (§6c), a different table and a different job.
  New: `CaseTags` (reconcile, `CaseClassifications`' shape **without** the vocabulary bound, because
  a tag is free text and every tag a client omits is one it was shown), `CaseTagRepository.findForCase`,
  `CaseEdit.normalizeTags`, `CaseQuery.tags`/`unfiled`, `CaseSpecifications.tags`,
  `CaseScope.includeUnfiled`.
  **TAGS ARE NOT ON `CaseEdit` AND NOT IN `CaseVersions#hash`** — the slice's recorded departure
  from its plan line. `CaseEdit.Outcome.Valid` carries them **beside** the edit (null = "leave them
  alone"). Labelling a case `sprint-42` must not write a version whose diff says nothing about the
  test changing (§6j's empty-diff noise), and `CaseBulkService.AddTags` already said so by refusing
  to stamp `updatedAt`. It also makes §95's bulk-hash trap structurally unavailable.
  **TWO PLACES WHERE A NULLABLE `suite_id` SILENTLY DROPS ROWS, both fixed here and both found by a
  test rather than by reading**: `CaseSpecifications.suiteScope` (`IN (…)` is never true of NULL, so
  every unfiled case vanished from the grid) and `ProjectRepository.countLiveCasesByProject` (an
  inner `JOIN suite`, so the picker said 6 over a list of 7 — the very defect that method was
  written to fix, from the other side). `CaseFacts.casesInPlanScope` is a `LEFT JOIN` for the same
  reason. **Grep for `JOIN suite` before adding one.**
  Filters: `?tag=` repeats and **ANDs** (a grid narrows), which is deliberately the opposite of
  `suite_tag_rule`'s union (a collection widens); `?unfiled=true` is **ignored** beside `suiteId`
  rather than refused.
- **THE SUITE SIDE OF §96 (phase 3) — `SuiteMembership` IS THE ONE UNION.**
  `SuiteMembership.countsBySuite` / `caseIdsInSubtree` / `suitesByCase` (native SQL, explicit
  `company_id` **and** `project_id` predicates) resolve **filed ∪ added ∪ tagged**, deduped with
  `COUNT(DISTINCT c.id)` — a case reachable through two arms is one member.
  **`Suite.caseCount` is now resolved membership, not filed cases**, and the counts **no longer sum
  to the project's case count**: a case can be in three suites at once, so the client must say so
  rather than let a reader find it by subtraction.
  Contract: `Suite` gains required `tagRules` and `caseIds` (the **explicitly added** cases only —
  not the filed or tag-matched ones, because that list is what an editor reconciles); `SuiteInput`
  takes both, absent = "leave alone", `[]` = clear.
  New: `SuiteCase`/`SuiteTagRule` (+ `…Id`, `…Repository`), `SuiteMembershipWriter`,
  `SuiteEdit.normalizeTagRules`/`normalizeCaseIds`, `TestCaseRepository.liveIdsForProject`.
  **A suite may CLAIM a case and may never MOVE one** — filing is `test_case.suite_id` and belongs
  to the case's own write path; a suite edit that could re-file would let two screens write one
  column. `SuiteMembershipWriter` therefore touches two arms and never the third.
  ~~Still open after this phase: run scoping does not resolve the new arms~~ → **phase 4 closed it.**
- **RUN SCOPING RESOLVES THE UNION TOO (phase 4) — AND THE PREDICATE EXISTS TWICE ON PURPOSE.**
  `CaseFacts.inSubtreeOf(alias)` is the {@code execution} module's copy of the union, used by all
  four of its suite-expanding queries (`caseCountsByPlan`, `caseIdsInPlanScope`,
  `casesInPlanScope`, `caseIdsInSuiteSubtree`) via `"…%s…".formatted(inSubtreeOf("s"))`.
  ~~**This is what makes "run everything tagged smoke" work with no new mechanism** — such a run is
  a suite whose tag rule is `smoke`, scoped into a plan the way suites already are; there is no tag
  option on `plan_scope_item`.~~ → **superseded by §97 phase 3b (D8)**: `V32` gave
  `plan_scope_item` a `tag` arm, so there are now **two** ways to say "everything tagged smoke", and
  that cost was accepted rather than force a plan section through a whole reusable suite. The
  paragraph's other half stands — without `inSubtreeOf` a suite arm resolves to what is *filed*,
  which for a tag-defined suite is usually nothing.
  It duplicates `repository.SuiteMembership` because ERD §0.2 forbids the call, so the two are kept
  honest by **`SuiteScopeAgreementTest`** (prefix `5c`), which also pins the ONE intended
  disagreement: the seam **includes soft-deleted cases** (history must not shrink), the repository's
  union excludes them (the tree and grid must not show them).
  **Two traps that class paid for**: only one live member is *filed* in its fixture, so a
  filed-arm-only resolution reddens it — and calling a `CaseFacts` method straight from a test runs
  it **outside any transaction**, so `app.current_company` is unset and RLS answers zero rows. The
  first draft compared an empty list to an empty list and reported agreement; the fix is a
  `TransactionTemplate`, not just a `CompanyContext`.
- **A PLAN SCOPE LINE HAS THREE ARMS AND TWO LEVELS SINCE §97 phase 3b — AND THE PREDICATE IS ONE
  STRING.** `CaseFacts.scopeMatch()` is the case-or-suite-or-**tag** branch, interpolated into all
  **four** plan expansions (`caseCountsByPlan`, `caseIdsInPlanScope`, `casesInPlanScope`,
  `caseCountsBySection`). It wraps `inSubtreeOf("s")`, so the two predicates nest rather than
  compete. Before §97 the branch was two arms copied three times; the third arm is where copying
  stops being survivable, which is why it moved into a method.
  **Every arm is guarded by its own `IS NOT NULL`** — an empty section is stored as a row naming the
  section with all three targets null, and an unguarded `i.tag = pt.tag` on a NULL tag is *null*,
  not false. Same three-valued trap §96 found on the suite side, from the other direction.
  `PlanScope` is now `(suiteIds, caseIds, tags, sections)`; `PlanScope.Section` is
  `(name, position, suiteIds, caseIds, tags)`, plan-local and **id-less**, so `sections` present
  replaces the whole list. `allSuiteIds()`/`allCaseIds()` flatten both levels and are what
  `PlanEdit` live-id-checks — checking only the unsectioned group is §97's own version of the
  sibling-project hole.
  **The section name IS its identity** (storage keys membership on `section_name`), so duplicates
  are a 400: saved as two, they read back as one, silently merged.
  **`ck_plan_scope_target` is deliberately NOT a three-way exactly-one** — an empty section needs a
  targetless row that names it — so the rule is *at most one target, AND a targetless row must name
  a section*. `PlanScopeConstraintTest` (prefix **`5d`**) asserts both halves over plain JDBC, not
  through `PlanScopeItem`'s factories, which would pass with the constraint dropped outright.
- **`GET /projects/{projectId}/tags` — the picker list §96 shipped without.**
  `TagController`/`TagService`/`TagUsageView` + `CaseTagRepository.findTagUsage`, in `repository`,
  no new key (project visibility only, the eighth time that argument is made rather than a key
  invented), `StepSuggestionController`'s shape exactly.
  **`case_tag` has a `company_id` and NO `project_id`**, so the join to `TestCase` is the only thing
  scoping this — without it the endpoint serves a client's whole tag vocabulary across every project
  in their instance while every cross-company test in the suite stays green. `TagEndpointTest`
  (prefix **`6c`**) asserts it in both directions: the absent foreign tag *and* the uninflated count.
  **A picker's list, never a validator's** — a client may send a tag this never returned, which is
  how the first case to carry a new tag gets it.
- **THE CONDITION LIBRARY HAS WRITERS SINCE §95 phase 1, AND THE USAGE COUNT IS NOW TWO NUMBERS.**
  `ConditionService.create`/`update` + `POST`/`PUT /projects/{projectId}/conditions` +
  `ConditionEdit`/`ConditionWriteRequest`/`ConditionUsage` + `ConditionRepository.countCaseUsage`/
  `countSuiteUsage` + `ConditionWriteEndpointTest` (fixture prefix **`c9`**).
  **NO migration, NO table, NO column, NO new key** — policy count stays **59**, `V30` still head
  with `V31` free. `lock_version`, `created_by` and `updated_by` were all `V1` columns nobody had
  mapped.
  **This is the object §94 declined to make steps into.** A step is text *copied* from a corpus and
  carries no id; a condition is attached **by id** and an edit propagates everywhere. Both libraries
  now have writers and they are deliberately opposite in kind.
  **`Condition.usage_count` is UNMAPPED and both counts are DERIVED**, `SharedStepService.rank`'s
  shape with two count queries. The column read its `DEFAULT 0` on every row in every deployment,
  which was defensible while nothing consumed it and became indefensible the moment §95 made it an
  **impact warning**: a warning computed from a permanent zero is a confident wrong answer, not a
  cautious one.
  **THE decision is that it is TWO numbers that must never be added.** `usageCount` = distinct live
  cases attaching directly; `suiteUsageCount` = live suites attaching. A suite attachment inherits
  down the **whole ancestor chain** (`CaseConditions`' walk over `suite.path`), so its case reach is
  not computable here — the walk is `ltree`, HQL cannot express it, and a native query would drop
  the Hibernate company filter, the constraint every library read in this module records. Blending
  them would be a guess presented as a measurement **and would understate**, which is the direction
  an impact warning must never fail in. The contract had already posed this exact question on
  `/shared-steps` ("a design question rather than a join") and §95 answers it.
  **`lock_version` is mapped WITH `@Version`, from the entity's first writer** — §74's trap in its
  preventable form. Its two tests are a **pair**: `theVersionMovesOnEveryEdit` is what makes
  `aStaleIfMatchIsRefused` able to fail, since a version that never moves makes every `If-Match`
  match. `ApiProblem.stale("condition")`, not `conflict(title, detail)` — a lost update, whose advice
  ("reload and reapply") is the opposite of a state refusal's.
  **`findLiveInProject`, never `findById`** — the sibling-project row is in the same company, so RLS
  and the company filter both pass it; one test stands between a caller and editing a library they
  hold no grant on. Validation is **one method for create and edit** on purpose: a rule relaxed on
  the edit lets an entry reach, across every attached case, a state the create refused.
  **Live trap in the tests: these WRITE, and nothing rolls back a MockMvc request.** `ON CONFLICT DO
  NOTHING` left `lock_version` climbing across the class and six tests failed on legitimate 409s
  unrelated to their assertions; the seed is `DO UPDATE SET … lock_version = 0`.
  **Still owed (phases 2–4):** `CaseUpdateRequest` continues to **400 on `preconditions`/
  `postconditions`**, so nothing can attach a condition through the API yet; there is **no delete**;
  and the frontend has **no call site for the GET, POST or PUT**. `#confirmed`

- **THE STEP CORPUS (§94 phase 1).** `V30__test_step_action_trgm.sql` (**head; `V31` next free; NO
  new table, so the policy count stays 59**) + `StepSuggestionController`/`StepSuggestionService`/
  `StepSuggestionQuery`/`StepSuggestionView` + `TestStepRepository.findSuggestions` +
  `StepSuggestionEndpointTest` (fixture prefix **`c4`**).
  **`GET /projects/{projectId}/step-suggestions` is the editor's autocomplete corpus, and it is
  DERIVED, not curated** — a `GROUP BY (action, test_data, expected_result)` over the project's live
  cases, ranked by `COUNT(DISTINCT case_id)`. That is the deliberate correction to `shared_step`,
  which stayed empty because somebody had to populate it. **The two libraries now differ in kind:**
  a suggestion is **text copied** into the author's step and carries **no id at all**, so editing it
  later touches one case; a condition is **attached by id** and an edit propagates everywhere. The
  missing id is load-bearing and `StepSuggestionEndpointTest` asserts the exact field set to keep it
  missing.
  **`V30`'s index is on the EXPRESSION `lower(action)`, not on the column.** Postgres consults a
  functional index only for the expression it was built on, so `GIN (action gin_trgm_ops)` would be
  built, maintained on every write, and **never used** by the `LOWER(action) LIKE` predicate.
  **`shared_step_title_trgm_idx` and `condition_title_trgm_idx` (both V1) have exactly that gap
  today** — plain-column indexes under `LOWER(title)` queries — and were deliberately left alone;
  they are bounded to hundreds of curated rows, this corpus is not. `#confirmed`
  **Reuses rather than duplicates:** `ProjectService.readVisible` for the two-gate 404-never-403
  read (the corpus leaks *more* than a curated library, because nobody chose what went in), and
  `SharedStepRepository.countUsage`'s distinct-case aggregate shape. **HQL, never native** — a
  native query drops the Hibernate company filter, the constraint every library read here records.
  **`StepSuggestionQuery` is the FOURTH copy of `escapeLike`** (`ConditionQuery`, `SharedStepQuery`,
  `CaseSpecifications`); the fifth should extract it. `#confirmed`

- **THE PLAN'S DOCUMENT, AND `doc_link`'S FIRST WRITER (§93; adoption since §97 phase 3c).**
  `V29__doc_page_subject.sql` (**NO new table, so the policy count is unaffected**) +
  `DocSubject`/`DocSubjectType` + `PlanDocumentService`/`PlanDocumentController`/
  `PlanDocumentLinkInput` + `DocLink`/`DocLinkRepository`
  + `LinkSourceType`/`LinkRelationship` + `BlockLinks`/`BlockLinkService`/`BlockLinkView`/`BlockLinkInput`
  + `CatalogueFacts`. ~~**NO TESTS — the largest gap in the module**~~ → **closed**:
  `PlanDocumentEndpointTest` covers adoption, links, the two traceability 409s and `level`'s three
  arms.
  **A PLAN ADOPTS A PAGE; NOTHING HERE CREATES ONE (§97).** `PlanDocumentTemplate` and its `POST`
  are **deleted** — four of its five headings acquired real homes (this page, the `artifact`
  register, `scope_text`/`out_of_scope_text`, the plan's sectioned scope items), which left it
  seeding a document whose every section is authored elsewhere. `PUT` writes the page's
  `subject_type`/`subject_id` and **nothing on the plan**, hence `doc:edit`; `DELETE` clears them and
  **keeps the page**. So `DocPage.subjectType`/`subjectId` are **no longer `updatable = false`** —
  restore that annotation and every link answers **200 with a rendered page while the row never
  changes**, because Hibernate omits the columns from the UPDATE and the view is built from the
  in-memory entity. The guarantee it used to buy lives in `DocPage.linkTo`, which refuses a page
  that already has a subject: **re-pointing is unlink-then-link, two requests, two audit rows**
  (`doc_page.subject_linked` / `.subject_unlinked`, each carrying the subject, read *before* the
  columns are cleared on the unlink).
  **TWO 409s AND THEY ARE TWO THINGS** — *this plan already has one* (resolve on your plan) and
  *that page is another plan's* (resolve on the page) — checked in that order, because when both
  hold the caller can only act on the first. The page-side one **has no index behind it**:
  `uq_doc_page_subject` is on the *subject*, so a second plan adopting a taken page is a different
  key and the database permits it. The service check is the whole protection.
  `PlanFacts.findAnyPlanKeyInProject` is the one lookup here that does **not** filter `deleted_at`,
  because a soft-deleted plan still holds its page's slot and the refusal has to be able to name it.
  **The reference is on `doc_page`, opaque, because NEITHER side may hold a key**: `execution` and
  `documentation` may each depend only on tenancy/identity/project, so `test_plan.doc_page_id` is not
  a column anybody may add. §76's Review subject a second time. **The unique index is PARTIAL on
  `deleted_at IS NULL`** — a page's delete is soft, so a plain one makes a deleted document block its
  plan for ever, as a duplicate-key 500 naming an invisible row.
  **`doc_link` had no writer since `V1`, and three specified reads were waiting on it** —
  `DocBlockView`'s header said so in as many words, and `DocPageWriteService`'s two traceability
  `409`s **could not fire on any real request**. Both are live now. `DocBlock.links` is served;
  **`coverage` is still absent and its reason MOVED** (nothing here can *price* a link:
  `passed`/`failed`/`notRun` are two modules away and all five fields are required).
  **`CatalogueFacts` is `PlanFacts`' sibling** — one file per foreign module's tables, named after
  what it reads (`repository`: `test_case`, `suite`). Both layer a **project predicate over RLS**,
  which only scopes to the company; without it a sibling project's case links successfully.
  **`level` grew a THIRD ARM** — required on `heading`, optional on `list` (indent depth), refused on
  the rest. Forced by the outline: a `doc_link` targets a *block*, so a scenario a case can verify
  must BE a block, so one bullet is one block, so depth is a field.
  **`LinkSourceType` is narrower than the column on purpose** (`DEFECT`/`DOC_PAGE` have no writer);
  the `CHECK` stays wide, because one tightened and loosened again is a constraint nobody trusts.
  `#confirmed`

- **SAVED REPORT DEFINITIONS SINCE §92 (R5).** `ReportDefinitionRepository` +
  `ReportDefinitionService`/`ReportDefinitionController`/`ReportDefinitionView` + `ReportTypes`,
  and `ReportDefinition` gained a `create` factory. **`V28__report_artifact_definition_on_delete.sql`
  is the head migration, `V29` next free**; policy count unchanged at **59** (no new table).
  **Owner-only rows, and the repository is the enforcement**: it has *no* method able to find a
  definition the caller does not own (no `findById`, no `findByIdAndProjectId`), because
  resolve-then-check-the-owner is the shape that passes an access-control test one call site over.
  Somebody else's definition ⇒ **404** on the delete, **400 field error** on generate. All three
  operations cost **`report:view`** — including the two writes, because the row is the caller's own
  workspace and `report:export` would stop §6e's read-on-screen persona saving its own selection.
  `definitionId` on generate supplies the **type** and the **base** for `parameters`;
  `ReportParameters.merge` is **shallow** and the merged result is what the job stores.
  `ReportTypes` is the single "this server has no report called that" refusal, shared by save and
  generate.
- **THE REPORT PIPELINE IS END TO END SINCE §87 (R3).** `V27__report_job.sql` (policy count
  **58 → 59**, ~~head migration, `V28` next free~~ → **`V28` is the head since §92**) + `ReportJob`/`ReportArtifact`/`ReportDefinition` +
  `ReportJobRepository`/`ReportArtifactRepository` + `ReportStore`/`FilesystemReportStore` +
  `ReportModel` + `MarkdownReportRenderer`/`JsonReportRenderer` + `ReportRenderers`/`ReportComputations`
  + `RunSummaryReport` + `ReportJobService`/`ReportJobRunner`/`ReportJobScheduler`/`ReportReadService`
  + `ReportController`. **`reporting` now declares `{"tenancy", "project", "identity"}`** — the
  widening §86 named rather than pre-approved, made in **three places none of which the compiler
  checks**: the annotation, the package comment and `ModuleStructureTest`'s `@DisplayName` rationale.
  **A TABLE, NOT AN `@Async` DISPATCH** (decision B): an in-JVM dispatch loses every in-flight job on
  a restart with no symptom but a spinner, which gives back exactly what §6e's 202 bought.
  **The company loop is the only way the worker sees a row**: `report_job` is under
  `FORCE ROW LEVEL SECURITY`, so a global "find all pending jobs" returns **zero rows** and the tick
  logs success having done nothing, for ever. Three transactions per job (`TransactionTemplate`, not
  three `@Transactional` methods — those are self-invocations the proxy never sees); `attempts` moves
  on the **claim**, not on failure, or a process dying mid-run reads 0 for ever.
  **BYTES FIRST, ROW SECOND — the inverse of `EvidenceService`/§67, argued in `ReportJobRunner`'s
  header**: the job row already names the work so an orphan blob *is* findable, while a `COMPLETED`
  job whose download 404s is a client already told the report is ready.
  ~~**`ReportStore` is `EvidenceStore` copied, with the debt named at BOTH ends** (decision C / §58 E1)
  — `reporting` may not import `execution`, and a `shared` module for two interfaces and a record was
  refused (decision E, §76's argument).~~ → **§125: both ports moved to `storage`, the body is one
  `FilesystemBlobStore`, `ReportBlob` is gone. The ports stayed two types on purpose.** Its root is a separate property: reports are the first
  storage in the product that is **not backup-critical**, because every one is regenerable from
  `execution_fact`.
  **Every job writes TWO artifacts** (decision F) — what was asked for, and the JSON model the
  on-screen view will read, so screen and export are one model rendered twice; a JSON request writes
  **one** artifact with both columns naming it.
  **Request-time refusals vs late ones**: an unknown type and an unrenderable format are **400s**
  derived from the two registries (never a literal list); PDF says *"not installed on this server"*.
  A report's **own** parameters stay late — an unknown `runId` is a `FAILED` job carrying the
  computation's sentence. `#confirmed`
- **THE REPORT HEADER, AND THE THIRD RENDERER (§88 / R4).** `ReportHeader` + `ReportDocument` +
  `ReportHeaders` + `HtmlReportRenderer`; `ReportRenderer.render` now takes a **`ReportDocument`**,
  never a bare model. **No migration (`V27` still head, `V28` free), no table, no column, no key,
  policy count still 59; the contract change is prose only.** **The header is the JOB's knowledge**,
  so a computation cannot be asked for it — a report type that forgot to hand it back would produce a
  **headerless document that renders perfectly**; the pair is one argument so that "render the report
  without saying what it is of" is not a call anybody can make. The `JSON` artifact stores the
  document, so F2 renders the header the export printed. **`ReportDocument.headerBlock()` is the ONE
  assembly** — provenance, then selection, then the report's own metadata — and **the aggregation
  policy stays in the metadata deliberately**: only the report can answer it, and a header field
  would be a second home. **The selection is read off the STORED parameters**, never off what the
  computation used, or a filter the report ignored vanishes from the document; entries are prefixed,
  because a client's parameter could be called `Company`. **§6u branding is a display name only** —
  `logo_storage_key` and the two colour columns are unmapped `V1` columns with no writer (M6) and
  **no per-project override column exists**. **`HtmlReportRenderer` is hand-written, NOT Thymeleaf**
  (a recorded deviation from the plan): `text()` is the only path a string takes to the output and
  the test counts **19** escaped positions, which is stronger than a template engine and avoids
  installing view resolution into a REST API that has never had any. **Self-contained is asserted** —
  no script, link, img or absolute URL; charts are CSS bars scaled against the series' own max, and
  an all-zero series draws **no** bar (`width: NaN%` renders full-width). **HTML became requestable
  because a bean exists** — nothing in the controller, service or validation was edited. `#confirmed`
- **`ProjectService.readGranted` (§87) — the product's first read gated on a permission key beyond
  visibility.** Visibility still answers **404**; what answers **403** is a feature of a project the
  caller is demonstrably looking at. A distinct method rather than a flag on `writeGranted`, which
  would run a read in a read-write transaction and read as a write in the diff. `#confirmed`
- **`report:view` / `report:export` got their FIRST consumer in §87 — no new key**, the eighth
  instance of that shape. **And until §87 phase 6 both gates were DECORATIVE**: `V3` grants the two
  together to four of the six built-ins, and Automation Service (the fifth) holds no `project:read`,
  so it 404s on visibility one step earlier — **no persona the suite could build lacked either key**.
  `ReportEndpointTest` therefore inserts the suite's **first custom roles, and there are two**: one
  holding `report:view` and **not** `report:export` (which is what makes the two keys
  *distinguishable*), one holding **neither** (which is what keeps the *poll's* gate non-vacuous).
  Both hold `project:read`, so their refusals are 403 where a no-grant member gets 404.
  **The `@AfterAll` that deletes them is load-bearing**: `SeedDataTest.sixBuiltInRoles` asserts
  `SELECT name FROM role WHERE NOT built_in` is **empty**, so a leaked fixture role reddens an
  unrelated class in the full suite only, at a moving position — §19's class through a mechanism **no
  UUID prefix protects against**. Assignments delete first (`role_assignment.role_id` has no
  `ON DELETE CASCADE`; `role_permission` does). `#confirmed`
- **`execution_fact` HAS A WRITER since §86, and `reporting` is the module that had to exist for it.**
  `reporting/package-info.java` + `ExecutionSource` (the seam) + `ExecutionFactRefreshService` +
  `ExecutionFactScheduler` + `ReportingProperties`. **No migration (`V27` still free), no contract
  change, no key, no table, no column** — policy count stays **58**; `execution_fact` and
  `case_health` are both `V1` tables already on `V1`'s `company_owned` array.
  **The module had to be created before anything could be written**: the projection names
  `test_execution`/`test_run` (`execution`), `test_case`/`suite` (`repository`) and
  `case_status`/`case_classification` — three modules, and no existing module may write it, which is
  §81's argument arriving at a module that did not exist yet. `ExecutionSource` is the **fourth**
  `*Facts`-shaped seam (`CaseFacts` · `ExecutionFacts` · `BoardFacts` · this); nothing else in the
  package may name a foreign table. **The dashboard stays in `repository`** —
  `DashboardController`'s header already argued why and §86 does not overturn it.
  **`allowedDependencies = {"tenancy"}` ALONE**, narrower than the plan proposed and than every
  sibling: nothing here is reached through an endpoint, so there is no project gate and no person to
  name. `documentation`'s precedent in both directions — declaring the three it will eventually want
  makes the declaration a **forecast**, and a forecast cannot fail the build when it stops being
  true. ~~**R3 widens it**~~ → **widened in §87 to `{"tenancy", "project", "identity"}`**, and `V27`
  is no longer free: see the §87 entry at the top of this list.
  **THE decision is the `ON CONFLICT` set-list: seven columns move, fifteen do not.** Move =
  `status_kind`, `counts_as_pass`, `executed_at`, `executed_by`, `duration_ms`, `release_version`,
  `release_sort_key` (an amended verdict is corrected). **Frozen** = `automation_status`,
  `test_type_id`, `test_level_id`, `feature_ids`, **and `suite_id`/`suite_path`, which the plan did
  not name** — same argument, and freezing is the safe direction since a frozen dimension that should
  have moved is one statement to change while the reverse has already destroyed the history it
  rewrote. `EXCLUDED.*` everywhere is shorter, is what anybody would write, and re-files last
  quarter's results under this quarter's taxonomy every fifteen minutes. Star schema: a fact's
  dimensions are as-of the fact.
  **Idempotent recompute, NOT events** — zero `CompanyScopedEvent` implementations and no outbox
  consumer means an event-only log has **no rebuild path**, and a short log renders identically to a
  correct one. Deliberately the shape an event handler would call, so landing `ResultRecorded` adds a
  handler and changes nothing.
  **"Recorded" is the INNER JOIN to `case_status`** — `execution_fact.status_kind` is `NOT NULL`
  while `test_execution.status_id` is nullable, so the log structurally holds only recorded rows.
  **Consequence for every report downstream: coverage / not-run denominators can NEVER come from this
  table** (§46's finding arriving from the schema).
  **Soft deletes: the fact log KEEPS them, `case_health` does not.** Opposite calls, consistent —
  `V1` calls the log *append-only* and this writer never deletes, so filtering on insert would make
  its contents a function of when the job ran relative to the delete. History versus a worklist.
  **Live traps.** `max(uuid)` **does not exist in Postgres** (17 or otherwise) — the lateral uses
  `(array_agg(… ORDER BY …) FILTER (…))[1]`, with the `ORDER BY` so a relaxed SINGLE-cardinality
  trigger cannot make the statement non-idempotent. And **a fixture UUID prefix must be HEX**: `rf…`
  threw in the static initialiser and failed all eleven tests before one ran (§19's family, new
  mechanism). `#confirmed`
- **`case_health.failing_repeatedly` has a writer since §86 — signal 3 of 9, and the FIRST column of
  that table written by a module other than `repository`.** `ExecutionSource.recomputeFailingRepeatedly`,
  wired into the same scheduler tick. **`ExecutionFacts`' rule finally has a test**: *"a signal's
  owner writes its own column and no others; the row is shared, the columns are not"* — prescribed
  since §53 and unexercised until a second writer existed. The clobber would surface an hour after
  the slice ships, as a bug in `CaseHealthRecomputeService`, which would be innocent. `computed_at`
  is shared by both writers deliberately: it means *somebody looked*.
  **`status_kind = 'FAILED'`, NEVER `NOT counts_as_pass`** — `BLOCKED`/`SKIPPED`/`RETEST`/`UNTESTED`
  all satisfy the tempting spelling, so a case nobody could *run* would be reported as a broken test.
  **The window must be FULL** — compared to N, not to the rows found; the *all of what we have*
  reading lights the tile on the first failure a young project records. **N = 3**
  (`tcms.reporting.failing-repeatedly-window`), a COUNT not a `Duration`, and `ReportingProperties`
  **refuses to start below 2**. **Found and NOT fixed: `case_health` has no index on
  `failing_repeatedly`** while `V1` ships four partial indexes for other signals — the first tile
  that scans; an index is a migration and §86 has none. `#confirmed`
- **`ExecutionFactScheduler` is `CaseHealthScheduler`'s TWIN, and the company loop is not an
  optimisation.** Two schedulers rather than one because they write read models owned by different
  modules that may not call each other. A scheduled task starts with **no company**, so a global
  "project every pending execution" query sees **zero rows** under `V4`'s `nullif` and **logs a
  successful tick having done nothing** — invisible. `company` is not under RLS precisely so it can
  be read first. One transaction per company; **facts before signals in that order**, since the
  signal reads the log and reversing them makes it permanently one tick stale (and a stale boolean
  renders like a fresh one). `PT3M`/`PT15M` rather than case-health's `PT2M`/`PT1H` — a fact is stale
  the moment a result is recorded, and the offset keeps the two schedulers out of lockstep.
  **`tcms.reporting.enabled=false` in `IntegrationTestBase`**, and it matters more than case-health's:
  the log is append-only with frozen dimensions, so a row written early is **not** corrected by a
  later refresh. `#confirmed`
- **Tenancy is the invariant everything rests on.** `CompanyContext` is a `ThreadLocal` exposed only
  as a scope guard — `try (var ignored = CompanyContext.open(id))`, never a setter. Deliberately
  **not** `InheritableThreadLocal`. `#confirmed`
- **`CompanyAwareTransactionManager` hooks `doBegin`**, so the context must be opened **around** the
  transaction, never inside it. A context opened after the transaction began leaves the RLS GUC
  unset and every read returns **zero rows**. Canonical ordering:
  `CompanyProvisioningService.provision():154-161` — resolve the id, open the context, then
  `transactions.execute(...)`. `#confirmed`
- **A repository interface must carry `@Transactional(readOnly = true)`.** Spring Data only gives a
  transaction to the CRUD methods *inherited* from `SimpleJpaRepository`, never to derived or
  `@Query` methods *declared on the interface* — without it those run with no company context and
  return zero rows, fail-closed and silent. `RepositoryTransactionTest` derives the repository list
  from the live context, so a new repository that forgets fails the build. `#confirmed`
- **Flyway owns all DDL**; `hibernate.ddl-auto: validate`, `baseline-on-migrate: false`. Migrations
  at `src/main/resources/db/migration`. ~~**Head is `V27__report_job.sql` (§87)**~~ ~~→ head is
  `V29__doc_page_subject.sql` (§93); `V30` is the next free number~~ → **head is
  `V31__suite_membership.sql` (§96 phase 1); `V32` is the next free number**, and
  `SchemaConventionTest`'s policy count is **61** (`SchemaConventionTest.java:262`) — `V31` adds
  `suite_case` and `suite_tag_rule`.
  Booting the
  real context in a test is itself a schema-drift gate. `#confirmed`
  **`V23` is the first migration since `V1` to CREATE A TABLE**, which makes two things live that no
  previous migration had to think about: a new table's RLS policy must be written with **`V4`'s
  `nullif(current_setting(...), '')` predicate and not `V1`'s bare one** (a table born with the old
  spelling reintroduces the reset-GUC 500 on exactly that table), and `SchemaConventionTest`'s policy
  **count moves 56 → 57** — the count is what makes the predicate check non-vacuous, so a new
  company-owned table is meant to move it. No `GRANT` is needed: `deploy/postgres/initdb/10-tcms-roles.sql`
  sets `ALTER DEFAULT PRIVILEGES` before Flyway runs. `#confirmed`
- **Privilege split** — Flyway migrates as `tcms_owner`, the pool connects as `tcms_app`, neither is
  a superuser and neither holds `BYPASSRLS`. Mirrored in three places that must agree:
  `deploy/postgres/initdb/10-tcms-roles.sql` (deployment),
  `src/test/resources/db/testinit/tcms-roles.sql` (suite), and the Hetzner runbook (which now
  *runs* the first file rather than carrying a copy — a copy drifted, see E1). `#confirmed`
- **One `Clock` bean**, `TcmsApplication.java:52`, injected into ~10 services rather than
  `Instant.now()` at call sites. **Truncated to microseconds** —
  `Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000))` — because that is what a Postgres
  `timestamptz` stores; an untruncated instant makes a value that is returned in memory and read back
  two different values (§60). **`ArchitectureRulesTest.nothingCallsNowDirectly` forbids
  `Instant.now()` and friends in production code**, so a new call site fails the build; the
  `Clock`-taking overloads are allowed. `ClockResolutionTest` (`com.tcms.ops`) pins the bean's
  identity, deliberately, because the behavioural assertion cannot fail on macOS. `#confirmed`
- **Permission qualifiers** — `role_permission.qualifier` restricts WHICH ROWS a grant covers:
  `ALL` · `OWN` (V12, by `created_by`) · `PLAN_SCOPE` (V19, by the row's scope). The evaluator answers
  the qualifier; the **gate** applies it to the row, through `project.AuthorshipGate` /
  `project.RowScopeGate` — the row's fact crosses into `identity` and a verdict comes back, so no
  module holds a second reading of what a qualifier means.
  **Since §65 `PermissionSet` IS a `key → qualifier` map and `PermissionEvaluator.qualifierOf` is a
  one-line delegation to `evaluate(...).qualifierOf(key)`** — one traversal, so the strongest-wins
  merge and the replace-don't-union boundary exist in exactly one place (`PermissionEvaluator.merge`).
  The three grant projections return `HeldPermissionRow` (key + qualifier) with `DISTINCT` over
  **both** columns; the four per-key qualifier queries were deleted and must not come back, since a
  second resolution of "which grant wins" is the defect that removed them. `PermissionSet.of` THROWS
  on a key qualified `NONE` — absent, never present-and-covering-nothing. `#confirmed`
- **`/me/permissions` is `[{key, qualifier}]`, not `[key]`** (§65), sorted by key. That is what lets
  the SPA disable a control a qualified grant cannot use, instead of letting the submit 403 —
  `useCan.canOnScope` mirrors `IdentityProjectAccess.canDoOnScope` arm for arm. `#confirmed` `PermissionQualifier.strongest` throws on
  `OWN`+`PLAN_SCOPE` (incomparable axes); `SeedDataTest` keeps that unreachable by asserting no key
  carries two different restrictive qualifiers. **A new qualifier value is a compile error at every
  consumer** — the switches are exhaustive under `-Werror`, deliberately. `#confirmed`
- **The bulk gate is the one permission resolved from the REQUEST, not fixed at the class.**
  `BulkOperations.permission` returns `case:approve` when `SET_STATUS` targets `APPROVED` and
  `case:edit` otherwise (roles spec's Review table; plan §6g's single gate). Sound only while
  approvers are a subset of editors, which `SeedDataTest.everyApproverIsAlsoAnEditor` derives from
  the catalogue rather than assuming. **`lifecycle_status` has exactly one writer**, `CaseBulkService`
  — `CaseUpdateRequest` omits it deliberately. `#confirmed`
- **`CaseEdit.of` reconstructs a stored case's authored content by round-tripping through
  `CaseEdit.validate`**, never by reading getters: a second normalizer makes every `CaseVersions`
  hash disagree and fills the version list with empty diffs. Used only by the two bulk actions whose
  fields the hash digests (`MOVE_TO_SUITE`, `SET_AUTOMATION_STATUS`), and pinned by a round-trip test
  in `CaseBulkEndpointTest`. `#confirmed`
- **`CaseRenderer`** is the one assembler of a case's shape — extracted from the byte-identical
  private copies in `CaseCreateService` and `CaseUpdateService`. A fourth writer uses it, never a
  fourth copy. `#confirmed`
- **Evidence bytes live outside Postgres — the first product state that does.** `EvidenceStore`
  (port, ~~`execution`~~ → **`storage` since §125**) + `FilesystemEvidenceStore` (adapter,
  `tcms.storage.evidence.root`, delegating to `FilesystemBlobStore`), per open question #28. **`EvidenceService` writes ROWS FIRST, BYTES SECOND** in both directions, because a
  filesystem does not roll back with the transaction: the surviving inconsistency must be a row
  whose blob is missing (a 404 an operator can find by joining), never a blob no row names
  (unreachable, uncollectable). **No test asserts the ordering** — it is held by a comment, and the
  mutation queue records that reversing it reddens nothing. `#confirmed`
- **The storage key is three server-generated UUIDs** (`company/execution/random`) and never the
  uploaded filename, which lives in `attachment.filename` — a column, not a path. The adapter
  re-checks the resolved path against the root anyway. `#confirmed`
- **`Attachment` and `Evidence` are mapped in `execution`, deliberately, not in a `storage`
  module.** `ModuleStructureTest` hard-codes the module list AND the tenancy/identity/project FK
  rule, so a new module needs plan §3 amended plus two architecture tests changed — for one caller.
  `Attachment.forExecution`/`forStepResult` mean no call site spells an `owner_type`, so the
  writers that must move on the day it does are the ones that file names. `#confirmed`
- **`spring.servlet.multipart` is now set (10MB/12MB)** — uploads proxy through the API because a
  filesystem adapter cannot sign a URL, so this is the only bound between one caller and the shared
  disk. Boot's default (1MB) would refuse an ordinary screenshot. Over the limit is a **413** from
  the container, before the handler — hence `EvidenceController#onTooLarge`, not an `Outcome`.
  `#confirmed`
- **The run has a LIFECYCLE since §72, and it is the only writer `test_run.status` has ever had.**
  `RunLifecycle` (pure, static — the transition table, `isClosed`, and the ONE copy of the
  frozen-run sentence) + `RunStatusService` + `PUT /runs/{runId}/status`, gated on **`run:close`**
  at PROJECT scope. **"Closed" is a status PAIR** (`COMPLETED` ∨ `ABORTED`), never a fifth value —
  so no migration, and every reader keeps one notion of "over". `TestRun.begin` is called on EVERY
  result and answers whether it was the first (self-guarding, so the caller holds no second copy of
  the test); `applyStatus` derives `completedAt` from the target **in both directions**, clearing it
  on a reopen. Reopen normalizes both live spellings by `startedAt`, so an `IN_PROGRESS` run with a
  null `started_at` is unreachable through the API. **No `If-Match`** — the target state is
  absolute, so there is no stale-read-derived value to lose (`DocPageWriteService#setStatus`'s
  call). `updated_by` is mapped for the first time. `#confirmed`
- **THE PLAN LADDER IS THREE STATES SINCE §97 (M7): `DRAFT → IN_REVIEW → APPROVED`, and that is the
  whole of it.** `PlanLifecycle` (pure, static — the transition table, `permissionFor`,
  `standingOf`) + `PlanStatusService` + `PlanDeleteService` + `PUT /plans/{planId}/status` +
  `DELETE /plans/{planId}`. **The gate is decided by the EDGE, not the target**: `plan:approve`
  grants the sign-off and **takes it back**, `plan:edit` carries the plan forward through the states
  nobody signed off — a target-keyed rule lets a Test Engineer rescind a lead's approval by asking
  for `DRAFT`, and reddens nothing else in the suite. Hence
  `ProjectService.writeGrantedOnTarget` (§69's seam, second consumer): the deciding fact is the
  STORED status. `APPROVED` is reachable only from `IN_REVIEW` (§6g's one gate) and
  `APPROVED → IN_REVIEW` is refused — a withdrawn sign-off returns the plan to its author.
  **The delete refuses 409 + count while a live run names the plan** (`findPlanKeys` is not filtered
  on `deleted_at`, so nothing breaks loudly), and such a plan now has **no terminal state**: it
  stays `APPROVED` for ever, because a plan is an outline and its execution state lives on its runs.
  `applyStatus` deliberately does **not** bump `currentVersionNo`. `plan:review` stays
  unconsumed on purpose — §6g puts peer review inside `IN_REVIEW` without moving the item, like
  `case:review` and `suite:review`. `#confirmed`

  **What §97 removed, because a reader will find the arguments elsewhere and think they still hold:**
  ~~`ACTIVE`~~ and ~~`CLOSED`~~ (they described a plan being *run*, which `test_run` already does),
  ~~`test_plan.closed_at`~~, ~~`PlanLifecycle.isClosed`~~ and the closed-plan freeze on
  `PATCH /plans/{planId}`, ~~`PlanLifecycle.startsWithoutApproval`~~ and §82's whole plan gate with
  its `project_settings.require_approved_plan_to_start` column and
  ~~`PlanStatusInput.overrideApprovalGate`~~, ~~`PlanDeleteService`'s delete-or-close partition~~,
  and the audit verbs ~~`plan.started`~~ / ~~`plan.reopened`~~ / ~~`plan.closed`~~ — which survive in
  `audit_event` on any deployment that wrote them and are now the **only** record of which releases
  were executed. `V32` maps every `ACTIVE`/`CLOSED` row to `APPROVED` **inside a per-company loop**,
  because `test_plan` is under `FORCE ROW LEVEL SECURITY` and a bare `UPDATE` in a migration touches
  zero rows and reports success (V7's shape). §6g's **case** gate, `requireApprovedCaseForRun`, is
  untouched and still binds on the resolved set at run-create time. `#confirmed`
- **The plan has an EDITOR since §74, and it is the first writer of `entry_criteria`,
  `exit_criteria` and `test_plan_version` alike.** `PlanUpdateService` + `PlanUpdateRequest` +
  `CriterionInput` + `PlanEdit.validateUpdate` + `PATCH /plans/{planId}`, gated on **`plan:edit`** at
  PROJECT scope with `If-Match` on `lockVersion`. Four seams to reuse rather than re-derive:
  **`PlanScope`** is the ONE normalizer of "the same scope" (sorted, deduped — `plan_scope_item` has
  no ordinal column, so any hash over insertion order can move without the plan changing);
  **`PlanSnapshot`** is what a version records, and it is *scope* per `V1`'s column comment and §6j —
  milestone, both criteria lists, both id sets, and deliberately **not** name/description (content),
  status (§73 already declined), `caseCount` (derived) or `configurations` (a *project's* list);
  **`PlanVersions`** is `CaseVersions`' shape with a real `decide` (a suite's could never take its
  other branch), so **a rename writes no version**; and **one value serves as both snapshot and
  hash**, unlike `CaseVersions`, because nothing reachable from `PlanSnapshot` is derived. **Absent /
  null / empty are three instructions split by KIND** — a scalar `null` clears, a list `null` leaves
  alone and `[]` clears (§68's `normalizeClassification` rule, now stated generally) — which is what
  keeps `TestPlan.create`'s *nobody has decided* apart from *deliberately none*. `PlanCriteria` owns
  the closed `METRICS` set and `isKnownOperator`, the latter **calling** `satisfies` rather than
  listing the operators twice. ~~A CLOSED plan refuses the edit (409), placed after the permission,
  before the `If-Match`, before validation (§72's order).~~ → **gone with the state in §97**: no plan
  is frozen against editing, and what protects a sign-off is §6g's edit-after-approval demotion.
  This endpoint has **one** 409 left, the stale `If-Match`. `#confirmed`
- **The REVIEW RECORD lives in `project`, and the placement is the design (§76).** `ReviewService` +
  `Review`/`ReviewParticipation` + `ReviewController` + `GET /projects/{id}/reviews?subjectType&subjectId`
  + `POST …/reviews/{id}/participations`. **A review's subject is an opaque triple** (`subject_type`,
  `subject_id`, `subject_version_no`, no FK), which is what lets ONE record serve cases, suites and
  plans — and three writers in TWO modules must reach it (`SuiteStatusService`, `CaseBulkService`,
  `PlanStatusService`), so it can only live where both may call. A `review` module is **refused**:
  `ArchitectureRules.FOUNDATION_MODULES` forbids naming anything but tenancy/identity/project in an
  `allowedDependencies`, so it would need the product's first `CompanyScopedEvent` (still zero
  implementations) with the event type in `tenancy`. **`ReviewService.moved` takes a
  `ReviewStanding`** (OUTSIDE_REVIEW · IN_REVIEW · APPROVED), never a status — each ladder maps its
  own enum (`LifecycleLadder.standingOf`, `PlanLifecycle.standingOf`) — and deliberately **not** where
  the artifact came FROM: the open review row is that fact and is the better one. Called INSIDE each
  ladder's transaction, so the status and the record commit together. **`V22`: a fourth state
  `WITHDRAWN`**, because the shipped ladders put `IN_REVIEW → DRAFT` on the EDIT key, so the submitter
  taking their own submission back walks the same edge as a colleague sending it back — derived from
  `actorId.equals(createdBy)`, never sent — plus a **partial** unique index (one OPEN review per
  subject; closed rows are the history). **The participation endpoint cannot write an `APPROVAL` row**:
  `APPROVED`/`REJECTED` are field errors, or the advisory key becomes a second door to the sign-off.
  Gate via `ProjectService.writeGrantedOnTarget` (§69's seam, **5th** consumer) on
  `ReviewSubjectType.reviewPermission()`, resolving through `findInProject` — `findById` is
  RLS-scoped to the COMPANY and accepts a sibling project's review. `#confirmed`
- **`review_comment` has a writer since §79, and `case:comment` its first consumer — asked for ALL
  THREE subject types.** `ReviewComment` + `ReviewCommentEdit` + `ReviewCommentRepository` +
  `ReviewCommentView`/`Request`/`ResolutionRequest`, `ReviewService.comment`/`resolveComment`,
  `POST …/reviews/{id}/comments` and `PUT …/comments/{id}/resolution`, **`V24`**.
  **The catalogue has ONE comment key where it has three review keys**, so
  `ReviewSubjectType.COMMENT_PERMISSION` is a static field rather than a third column on the
  constants — the asymmetry is the finding. Adding `suite:comment`/`plan:comment` was refused: a new
  key is seeded to nobody, so it is a 403 no administrator can grant their way out of (§67's
  `evidence:read` argument). The roles spec's Review table already calls it a tier — *"read an ITEM
  in full and leave threaded comments"*. `SeedDataTest.everyReviewerIsAlsoACommenter` **derives** the
  ladder that makes it sound (`everyApproverIsAlsoAnEditor`'s shape); when it fails, grant
  `case:comment`, do not delete the check.
  **RESOLVING costs `*:review`, not `case:comment`** — a statement about the review's progress
  belongs to the tier running it; *"the reviewing tier OR the comment's author"* (`evidence:delete`'s
  shape) was refused because its only new caller is a Viewer resolving their own feedback. The two
  gate mutations are a **pair**: each reddens exactly 1, and they are different tests.
  **Already-in-the-requested-state writes NOTHING** — the unconditional version re-stamps
  `resolved_at` and hands `resolved_by` to whoever clicked last, which was a live defect in this
  slice's first draft and is the only test of sixteen that failed on the first run.
  **Threads are ONE level deep**, the parent resolves through `ReviewCommentRepository.findInReview`
  (never `findById`, RLS-scoped to the company — §64/§68/§69's family), and both parent refusals
  carry the same sentence. `ReviewCommentView` is nested, so assembly lives on the server (§68's
  `buildValueTree`) and an orphaned reply surfaces as a root.
  **`anchor_type`/`anchor_id` stay NULL and unmapped**: validating a step id needs
  `repository.test_step`, which this module may not import, so it is a `PeopleDirectory`-shaped port
  or §64's unvalidated reference. **`refuseIfClosed` is the ONE spelling of "this review has
  ended"**, read by all FIVE write paths since §80 — the verdict endpoint's inline copy moved into it.

- **The reviewer's INBOX is a board generator, and its placement is the §81 finding.**
  `BoardFacts.pendingReviews` + `BoardService.cardFor(PendingReview…)` + `PREFIX_REVIEW`, in
  **`work`** — §6l's third derived-item generator, absent since §53 with the reason in the package
  comment. **No other module may write the query**: it names `review` + `review_assignment`
  (`project`), `team_membership` (`identity`) and `test_case`/`suite`/`test_plan` (`repository`,
  `execution`), and `project` — which owns the record — may depend on `tenancy` alone, which is why
  `ReviewController` refuses the queue in its own header. So §80's three options (fourth port / read
  model elsewhere / the domain event §76 refused) collapse to the module that already concentrates
  every foreign-table statement in one file. **A queue endpoint on `ReviewController` would have had
  to invent one of the three and would then serve a second "what is on my plate" list.**
  **A team is expanded at READ time**, the mirror of §80's write-time refusal and for its stated
  reason (*membership moves*): recomputed every load, so joining the team tomorrow finds the request
  waiting. `UNION` not `UNION ALL` (asked by name **and** through a team is asked once), and **one
  card per REVIEWER not per review** — `mine=true` filters by assignee, so a shared card would appear
  on nobody's board. **Three predicates, each removing a card that would never leave**: `state =
  'IN_REVIEW'` (unassigning is the only delete, so every request ever made is still in the table), no
  participation row by that person (the card leaves when they have **spoken**, not when they agreed),
  and `COALESCE(c.id, s.id, p.id) IS NOT NULL` — whose broken form yields a card with **no key and no
  title**, invisible to any `doesNotContain` assertion, hence the exact-set test.
  **`sourceType`/`sourceId` name the ARTIFACT, not the review**: those fields are *what the card
  opens*, and a review has no URL — §6g's panel renders on the artifact's page. The card id is
  `review:{reviewId}:{userId}`, the one derived id that is not prefix-plus-one-UUID, so `isDerived`'s
  prefix check is the **only** guard on it. **No contract change** — `kind: REVIEW` has been in
  `openapi.yaml` since §53, ahead of its generator, deliberately. `#confirmed`
- **The board is CROSS-PROJECT since §83, and `GET /work-items` with no `projectId` is the personal
  one.** `ProjectService.readAllVisible(userId, Function<List<Project>,T>)` + private
  `visibleProjects` (shared with `listVisible`), `WorkItemRepository.findLiveInProjects`, all three
  `BoardFacts` generators taking `Collection<UUID> projectIds`, `BoardService.personalBoard` +
  shared `present`. **No migration, no new table, no new permission key** — the gate is project
  visibility, which the caller already had.
  **`readAllVisible` is `readVisible`'s cross-project sibling and the product's first read that is
  not about *a* project**: no `Optional`, so no 404 — the caller named nothing that could fail to
  resolve, and a member with no grants gets `[]`. The read takes the **whole list** rather than being
  applied per project, which is what lets the board answer in one statement per source instead of one
  per project.
  **The empty collection is the ordinary path, not an edge**: `IN ()` is a syntax error rather than a
  predicate matching nothing, so `BoardService.assemble` returns before any query — the same guard
  `ProjectService.listVisible` states at its grouped query, and a member holding no grants is exactly
  who opens a personal board first. Every generator takes a **set** and there is deliberately **no
  single-project overload** (§58's E1: the copy that drifts is whichever board does not use it).
  **Each card is attributed by the ROW's `projectId`**, through a `Map<UUID,Project>` — the four
  sources have carried their own project since §53 and only now have a second reader; a generator
  reading the request's project would label every card with whichever project came first.
  **Omitting `projectId` with `mine=false` is a 400, never a silent narrowing** — §6l's cross-project
  board is the *personal* one, and forcing `mine` on would answer a request nobody made with a list
  the client cannot tell apart from a company holding only its own work.
  **Live trap for the next reader: the ANSWER is person-sized, the READ is not.** `present` filters
  after `assemble`, so a caller visible on every project loads that company's whole live work set —
  tasks, outstanding executions, flagged cases, open review requests, plus the people resolution —
  and keeps their own. §6l's *"cap derived generation with sensible limits"* therefore still has
  somewhere to land, on the generators rather than on the response. Not fixed in §83: pushing the
  filter down is what `present` refuses by name, and it would change the project board too.
  `#confirmed`
- **The board is §6l's THIRD one since §84, and `GET /work-items?teamId=…` is a third SELECTOR on
  one operation.** `BoardFacts.teamMembers`, `BoardService.teamBoard` + `forAssignees` +
  `present(cards, Set<UUID>)`, `WorkItemController.twoBoardsProblem`. **No migration (`V27` still
  free), no table, no key** — derived for the seventh time, and there is nothing for a key to protect
  since every card is one the caller could already see on a project board. §6l's three boards differ
  in *which cards are kept* and in nothing else, which is why a `/teams/{id}/work-items` route was
  refused.
  **THE decision: the bound is the CALLER's project visibility, never the team's.** Projects come
  from `readAllVisible` exactly as the personal board's do and the team contributes **only a set of
  assignees** — so *two people asking for the same team's board receive different boards*, stated in
  the contract as the design. *"Cards assigned to members of team T"* is one query, reads correctly,
  and renders another client's case key, title and project name to a reader who need not be on the
  team, need hold no grant, and need only guess a UUID. **§83's fixture cannot prove this** (its
  assignee is always the caller, so both bounds agree on every row); §84 adds `TEAMMATE`, granted
  where LEAD is not, and asserts a **pair** — LEAD's view is exactly `{TC-1,TC-10}`, TEAMMATE's view
  of the *same team* exactly `{TC-9,TC-11}` — the second half being what stops the first from passing
  because a fixture row silently failed to insert.
  **The expansion is at READ time and `TeamDirectory` was NOT widened.** §81's precedent, §80's
  reason (membership moves). The port's header forbids it answering *who may*; this is not that
  question, because visibility has already bounded the board before the set is applied. Its **`find`
  gets a second consumer** for existence — which is what makes an unknown team and another company's
  team **one 404** instead of a company-wide team-existence oracle (§6t). **Direct membership only,
  no hierarchy**, matching `pendingReviews`' flat join.
  **Four answers, two of them deliberately indistinguishable**: unknown team 404 · unreachable
  project 404 · no members `200 []` · members with no visible work `200 []`. **Their ordering is a
  trap with a test**: the empty-team shortcut lives INSIDE `forAssignees` (after the project gate),
  because hoisting it turns "empty team + unreachable project" into a 200 confirming the project
  exists. **`teamId`+`mine` is a 400** and **`teamId`+`projectId` is legal** (two axes, who and
  where — §6l's swimlanes-by-member). `present` filters over a **set** with the personal board as the
  one-element case, still **after** `assemble`, so §83's cost note is inherited unchanged.
  **`work_item.assignee_id` REFERENCES `app_user`** — §6l's team assignee for the unclaimed pool does
  not exist and is named not-built. **Live trap: the two boards are not protected by the same line** —
  dropping the grant filter from `ProjectService.visibleProjects` leaves every `Gates` test green,
  because a *named* project goes through `visibleProject`'s own copy of it. `#confirmed`
- **`review_assignment` is NEW in §80 (`V25`) — the first review table not already in `V1`.** That
  absence is itself the finding: `V1` designed the record of what happened and never the worklist of
  what is outstanding. `ReviewAssignment` + `ReviewAssigneeEdit` + `ReviewAssignmentRepository` +
  `ReviewAssigneeView`/`ReviewAssignmentRequest`, `ReviewService.assign`/`unassign`/`store`,
  `POST …/reviews/{id}/assignees` and `DELETE …/assignees/{assignmentId}`.
  **A worklist entry, NOT part of the audit record** — that is `review` + `review_participation` —
  and the three consequences ARE the design: unassigning is a **hard delete** (no tombstone; the
  opposite call from `ReviewComment`, for the opposite reason — a remark is something somebody said,
  a request is something somebody was asked), a deleted **team CASCADES** (§71's 409 deliberately NOT
  extended: what is lost is a request, not authority, and refusing would make a team undeletable
  until every review it was asked to look at had closed), and **re-asking writes nothing** (§79's
  re-stamp defect in the one place it recurs; the partial unique indexes are the backstop, the
  `findForUser`/`findForTeam` read is the check).
  Gate is `*:review` via `writeGrantedOnTarget` (§69's seam, **6th** consumer) — **no new key
  invented**, because one seeded to nobody is a 403 nobody can grant out of (§67's `evidence:read`,
  §79's `suite:comment`); `case:comment` would let a Viewer fill somebody
  else's queue and `*:approve` would confine the request to the tier it exists to prepare.
  **The assignee is validated on `ProjectAccess.canView`, NOT company membership** — §71's bar one
  table over and the wrong one here, because a colleague with no grant cannot open the artifact.
  `ReviewService` holds `ProjectAccess` directly for this, and only this: it asks about the
  **assignee**, never the caller. Both refusals carry one sentence (§6t). **The submitter is refused,
  derived from `standingLgtms` already skipping their own LGTM** rather than invented.
  Uniqueness is **two partial indexes**, because a plain four-column UNIQUE enforces nothing —
  every team row has a NULL user and NULL ≠ NULL. Policy count **57 → 58**;
  `ix_review_assignment_assignee_user` is added **before its query**, for §6l's queue. `#confirmed`
- **§6g's GATING RULE is the last part of the review workflow, it lives in `execution` (§82), and
  since §97 there is ONE of it.** `V26` added `require_approved_case_for_run` /
  ~~`require_approved_plan_to_start`~~ to `project_settings`; `V32` **drops the second column**, so
  `ReviewSettings` has one gating component and the one reader is `RunCreateService.gate`.
  ~~The plan rule binds on ONE EDGE, `DRAFT → ACTIVE` (`PlanLifecycle.startsWithoutApproval`), and
  the target-keyed reading is a live trap: refusing a reopen makes every closed plan on a gated
  project permanently un-reopenable.~~ → **moot rather than wrong since M7 removed the edge**, and
  kept here because it cost a slice to find: *a rule about a transition binds on the edge, not on
  the target*, and the naive reading passes every obvious test. **The case rule binds on the
  RESOLVED set** (`edit.caseIds()`, after the plan's
  scope is expanded) or naming a plan is a bypass costing no permission and leaving no trace, and
  **the whole run is refused, never trimmed** (this class's own partial-materialisation argument).
  **Neither is ANDed with `reviewEnabledFor`** — §77's "off is narrow" applies: gating reads the
  status ladder, which a project with review off still has, so it is its own opt-in and turning
  review off does not turn gating off. **The override invents NO KEY**: `case:approve`/`plan:approve`
  are *derived*, since their holder can reach the same state by approving first — so it widens
  nobody's reach and only changes the record (`approvalGateOverridden` on the audit row, written
  **only when the gate fired**). Explicit flag on the request, consulted only on the refusal path;
  a caller without the key gets the **same 409, not a 403**. `ApprovalGating.Verdict` shares the
  vocabulary and deliberately not the rule. **`CaseFacts.unapprovedCases` is the 11th crossing** and
  uses `<> 'APPROVED'`, not an enumeration — with **no test covering `DEPRECATED`**, which the
  mutation queue records. `#confirmed`
- **`ProjectService.holds` is the ONE "does this caller hold key K here" question (§82)** — not a
  gate, for the callers that ask a *second* permission after their own gate has passed, to shape a
  response rather than to authorise it. Three call sites: `CaseBulkService` (which refusal sentence)
  and the two §82 gates (may this request override). It was §75's private helper in
  `CaseBulkService`; §82 moved it up rather than let it become three byte-identical copies — §58's
  E1, prevented instead of mourned as it was for `PeopleDirectory` in §76. `false` for an
  unreachable project as well as a held-nothing caller, which is correct at all three. `#confirmed`
- **`TeamDirectory` is the THIRD port in `project` (§80)**, after `ProjectAccess` and
  `PeopleDirectory`, implemented by `identity.IdentityTeamDirectory`. Not a method on
  `PeopleDirectory`: that port's one rule is §6u's company-membership naming, for which a team has no
  analogue, so one interface would hold two unrelated resolution rules. **Two methods on purpose** —
  `resolve` tolerates a missing team (a render does), `find` is what a WRITE calls, because a write
  must refuse one or the product stores an unchecked `team_id` (§64), and "ask `resolve` with one id
  and check the map" is how a validation stops looking like one. It must never answer **membership**:
  §6g's "any member can action it" is `PermissionEvaluator`'s question (§71), and a team is
  deliberately not expanded into per-member rows at assignment time, because membership moves.
  **`find` has a SECOND consumer since §84** — `work.BoardService.teamBoard`, for existence, which is
  what makes an unknown team and another company's team one 404 rather than an existence oracle. The
  membership rule held under pressure: §84's team board expands in `BoardFacts.teamMembers`, not in a
  third method here.
  `#confirmed`
- **`project.PeopleDirectory` is the ONE resolution of "what is this person called" (§76).** Port in
  `project` (which may depend on `tenancy` only — its package comment prescribes exactly this),
  implemented by `identity.IdentityPeopleDirectory`; `ProjectAccess` is the first instance of that
  inversion. **`repository.CaseOwners` and `execution.ExecutionPeople` were byte-identical copies** of
  plan §6u's rule under a comment saying they must not disagree (§58 E1) and now delegate — what is
  left in each is the mapping onto its own module's `UserRefView`. The two null-key tests
  (`CaseOwnersNullKeyTest`, `PeopleResolutionNullKeyTest`) now pass a stub returning `Map.of()`, the
  worst case, and assert the wrapper still answers null-tolerantly (§38's 500). `#confirmed`
- **§6g's authoring ladder is ONE class since §75 — `repository.LifecycleLadder`** — read by
  `SuiteStatusService` *and* `CaseBulkService`, because `LifecycleStatus` is the status of a suite
  **or** a case (`V1` spells the identical four-value constraint on both tables) and §6g draws one
  diagram. Pure static: `decide(current, requested, noun)` → sealed `Decision`,
  `permissionFor(current, requested, Keys)` where `Keys` is a record so two adjacent key strings
  cannot be swapped unnoticed, and a written-out `rank`. **The plan's ladder is deliberately NOT
  folded in** — since §97 the two differ the other way round: `PlanStatus` has three values and
  this one has `DEPRECATED`, which a plan does not. Table:
  `IN_REVIEW` only from `DRAFT`; `APPROVED` only from `IN_REVIEW`; `DRAFT` and `DEPRECATED` from
  anywhere (a folder that cannot be deleted must still be retirable, or ordinary use reaches a dead
  end); a `DEPRECATED` artifact returns only via `DRAFT`. **`permissionFor` is decided by the EDGE**:
  the approve key covers granting the sign-off, retiring, and *taking either back*. `#confirmed`
- **`suite.lifecycle_status` has a writer since §75, and `suite:approve` its first Java consumer.**
  `SuiteStatusService` + `SuiteStatusRequest` + `Suite.applyStatus` + `PUT /suites/{suiteId}/status`,
  gated through `ProjectService.writeGrantedOnTarget` (§69's seam, 4th consumer) on
  `Keys("suite:manage", "suite:approve")` — and that pair **discriminates**, unlike §71's, because
  `V3` withholds approve from Test Engineer while granting manage. No `If-Match` (absolute target),
  parse before the gate with an unreadable status falling back to the LOWER key, already-there is a
  200 writing nothing, and **a status change writes no `suite_version` row and no bump** (a suite
  version is composition — §73's identical call on the plan). Six distinct audit verbs, because
  `DRAFT` is reached by three different acts. `SuiteWriteService`'s private `view` moved to
  **`SuiteService.viewOf`** so this service reuses it instead of becoming `CaseRenderer`'s fourth
  copy. `#confirmed`
- **`BulkOperations.permission` is the FLOOR, not the rule — the per-row edge check lives in
  `CaseBulkService.evaluateStatus`.** Until §75 the case gate was keyed on the *target*, so
  `case:approve` was asked only for a move INTO `APPROVED` and a Test Engineer could bulk-move two
  hundred approved cases to `DRAFT`, rescinding a lead's sign-off. `PlanLifecycle`'s §73 header named
  this class as the weaker shape and did not come back for it. Per-row rather than a higher floor,
  because one end of every edge is the row's stored status and a filtered selection mixes them;
  refused rows are `BulkVerdict.Skipped` with `leadOnly(...)`, never a 403 over the whole request.
  `holdsEdit` generalised to `holds(userId, projectId, key)`. **Three case transitions changed**:
  `APPROVED → IN_REVIEW` no longer exists, `DEPRECATED` returns only to `DRAFT`, and deprecating now
  costs `case:approve`. The **`OWN`-qualified deprecate** (an author retiring their own draft, via
  `AuthorshipGate`'s term) is named and not built. `#confirmed`
- **`lock_version` is mapped WITHOUT `@Version` on `TestRun` and `work.WorkItem`, and that is a live
  trap for the next writer.** §74 found it on `TestPlan`, where the column had read `0` on every plan
  since M2 slice 2 while the contract declared `lockVersion` *required* — so an `If-Match` check
  against it **could not fail**, there being no such thing as a stale 0. Ten other entities have the
  annotation. The two remaining are **latent** (neither serves `lockVersion`, nothing sends an
  `If-Match` for either) and the run one is **not a free repair**: `TestRun.begin` touches the run on
  every result recorded, so adding it would make two testers working one run collide on the product's
  most-used write. Sweep with: for each file mapping `lock_version`, check the 3 lines above it.
  `#confirmed`
- **The frozen-run rule is ONE rule over THREE writes** — result, evidence attach, evidence delete —
  which is exactly the condition §67 stated when it declined to implement `V1`'s "immutable once the
  run closes". Placement carries the reasoning and a mock or a sibling must match it: **after** the
  permission (a Viewer on a closed run still gets 403, not 409), **before** the `If-Match` (a stale
  header is fixable and this is not), and **before** authorship on the delete (a closed run refuses
  everybody including the author, so "this is not yours" would name a fix that does not exist).
  `Outcome.RunClosed` on both sealed interfaces made three switch arms a compile error. `#confirmed`
- **`ExecutionTargets` is the ONE resolution of the run/execution path** (§72), returning
  `(run, execution)`. It replaced byte-identical private `resolve` copies in `ExecutionService` and
  `EvidenceService` — a hand-maintained pair under a javadoc saying they were the same, which became
  a defect the moment both needed the run. Deliberately **not `@Transactional`**: `EvidenceService.open`
  calls it from a method that must not be in one, because it returns an open stream the controller
  writes to the response afterwards. `#confirmed`
- **`evidence:delete` is the second `OWN`-qualified consumer**, after `CaseDeleteService`, and the
  same shape: authorship applied AFTER the lookup, two 403s with different sentences, and the
  null-author arm refusing. Delete is **hard**, unlike a case's soft delete — a screenshot removed
  on request must actually leave the disk. `#confirmed`
- **`CaseRenderer` is the ONE assembler of a case's shape and now every path uses it.** It was
  extracted from the two write paths in §62; §68 found the **read** path
  (`CaseDetailService.detail`) was a fourth byte-identical copy that had never been moved onto it —
  invisible while the copies agreed, visible the moment a field was added to the view. A fifth
  writer delegates, never copies. `#confirmed`
- **The classification VOCABULARY has a writer since §69** — `ClassificationValueWriteService` +
  `ClassificationValueEdit` (`project`), and `V20`'s partial per-parent unique index behind it.
  **The gate is decided by the dimension row, in KEY and in SCOPE**: `project_id IS NULL` ⇒
  `company:vocabulary` at COMPANY scope, otherwise `project:settings` on the project. Hence
  `ProjectService.RequiredPermission` (key + scope) and **`ProjectService.writeGrantedOnTarget`**,
  the third write gate on that class — it resolves the target BEFORE asking any permission, because
  the permission to ask is a property of the target. The dimension resolves through
  `findForProject`, **never `findById`** (RLS scopes the latter to the company, not the project, so
  it accepts a sibling project's Feature dimension and every cross-company test still passes).
  `locked` is **not** read-only — it says whose the dimension is; three documents disagreed and the
  view's javadoc was the outlier. `ClassificationValue.create` derives the `ltree` label from the
  **id** (`v` + uuid hex), like `Suite.create` and for one more reason: `tcms_ltree_label` answers
  the empty string for a name with no ASCII alphanumerics, which an `ltree` refuses. `#confirmed`
- **The org chart has a writer since §71** — `TeamWriteService` + `TeamEdit` + `TeamController`
  (which also took `GET /teams` off `AdminController`, so one path family lives in one class), and
  `V21` behind it. **`company:teams` at COMPANY scope**, via a new `AdminReach.manageTeams()` term
  read off the same already-resolved company-wide `PermissionSet` — a separate term from
  `companyWide`, deliberately, though the seeded catalogue grants both to the same roles.
  **A team member must be a COMPANY member**: `team_membership.user_id` references instance-level
  `app_user` while `company_id` comes from the session, so a sibling company's account satisfies the
  FK, the company column and RLS — and the evaluator's five projections resolve teams through
  `team_membership` ALONE, never joining `company_membership`, while `AdminDirectoryService.refs`
  drops an id it cannot place. Unchecked, that is an **invisible grant**; hence a 404 and a test that
  asserts *no row was written*. `INVITED` counts, `REMOVED` does not.
  **Delete is refused (409 + counts) while grants or child teams exist** — `role_assignment.team_id`
  is `ON DELETE CASCADE`, so the delete would revoke silently; members do NOT block it.
  A team **may be reparented**, unlike a classification value, because the parent edge is org-chart
  structure and never a permission edge (§6k) — `TeamEdit.descends` walks upward with a visited set
  so a pre-existing cycle refuses rather than hangs. V2's `tcms_enforce_team_hierarchy` stays the
  backstop, never the answer. `#confirmed`
- **`SeedDataTest.teamManagementAndMemberManagementAreStillHeldTogether` records a COINCIDENCE, not
  an invariant.** `V3` grants `company:teams` and `company:members` to the same roles, so no persona
  the suite can build can tell the org-chart gate's key from its neighbour's — a mutation swapping
  them reddens nothing. When that check FAILS it is the good case: add the persona to
  `TeamEndpointTest.Gate`, do not re-align the seed. The frontend's `TeamsPage.test.tsx` carries the
  only test that can discriminate today, because it can mock a permission set the seed cannot
  produce. `#confirmed`
- **Classification writes** — `case_classification` got its first writer in §68.
  `ClassificationVocabulary` (built from the same `CaseRenderer#vocabularies` read the response
  needs, so validating costs no query) + `CaseClassifications` (the reconciler, `CaseSteps`'
  sibling). **The reconcile is BOUNDED by the vocabulary the caller was served**: a retired value's
  assignment is never returned by `GET` and is therefore never removed by a save that omits it,
  because a client cannot resubmit what it was never shown. Consequence, stated: a retired
  assignment is immutable through the API. `liveValueIdsOf` is the ONE implementation of "drop what
  the project no longer offers" and both the renderer and the validator's fallback read it — two
  copies would let the served ids differ from the preserved ids. **Delete is flushed BEFORE insert**
  or replacing a SINGLE axis's value hits V2's `23505` (Hibernate orders inserts first within a
  flush). `#confirmed`
- **`CaseEdit`'s `classificationValueIds` is SORTED and de-duplicated**, and that is a `CaseVersions#hash`
  requirement: the hash's determinism argument rests on there being no *set* in the record's shape.
  `CaseEdit.of` takes the case's stored ids so a bulk action's version row does not hash as though
  the case were unclassified. **`CaseUpdateRequest`'s refused six is now a refused FIVE.** `#confirmed`
- **`ContractConventionTest.requiredPropertiesHaveAComponent`** resolves a schema's `required`
  through `allOf` and checks the serving record has a component for each. Added in §68 after
  `TestCaseDetailView` was found to have omitted `createdBy` since §66 — which made the case
  detail's `OWN`-qualified Delete refuse every caller their own case. `RESPONSE_RECORDS` is a short
  explicit map; add a pair when a new view backs a contract schema. `#confirmed`
- **Operator CLI**: `ApplicationRunner` beans that are inert unless their verb appears in the
  non-option args — `ProvisionCompanyCommand`, `IssueInvitationCommand`. The verb list lives in
  `OperatorCommands`, which `TcmsApplication.main` asks *before the context exists* to pick
  `WebApplicationType.NONE`; a CLI run under a servlet container succeeds and then never exits.
  Commands parse and report only — behaviour lives in a service. `#confirmed`
- **Invitations**: tokens are stored as `hex(sha256(secret))` only — `InvitationTokens`. A link that
  was never delivered is unrecoverable by anyone. `InvitationService.issueIfPending` deliberately
  leaves a live invitation alone (so a `provision-company` re-run cannot invalidate a link already
  sent); `InvitationService.reissue` deliberately replaces it (the operator explicitly asked).
  `#confirmed`
- **Exception handling / API style**: `@RestControllerAdvice` → RFC 7807 ProblemDetail. Unreachable
  resources answer **404, never 403**, identically, so a response cannot be used as an oracle
  (plan §6t).
- **Async**: `CompanyContextTaskDecorator` clears the context on every task so a handler that forgets
  fails closed; `CompanyScopedEvent` is the only thing a handler may re-establish from.

## Conventions observed

- Constructor injection, `final` fields, records for DTOs and results, `Optional` in return
  positions on service APIs.
- **Comments carry the *why*, and often the failure mode a rule prevents** — unusually dense, and
  load-bearing. Match that register; a new file with thin comments reads as foreign here.
- Test `@DisplayName`s are full sentences describing the *stake*, not the mechanic. Test classes own
  a **slug namespace** (`inv-`, `iss-`) and provision their own companies — the Testcontainers
  database is shared across the whole run.
- `System.out` for CLI output, the logger for everything else. **Never log a secret**, including
  invitation URLs.

## Known unknowns

- `src/test/` is the `qa-api-java` peer's domain; this agent only adds tests alongside code it wrote.
- Schema/DDL design (`docs/specs/*-erd.md`, RLS policy shape) is `data-architect`'s.
- **CI runs `./gradlew build -x test` since 2026-08-02 — no backend tests run on push.** `./gradlew build` locally is the only test gate; nothing downstream repeats it.
- ~~Two tests are `@Disabled`~~ **→ fixed and re-enabled 2026-08-02 (§60). No test is `@Disabled`;
  the suite runs 1078 with 0 skipped**, and the `skipped` count is the cheapest way to check that
  claim still holds. `#confirmed`
- **`/reports` is the only dead contract left** (§64 built the step library, §62 landed
  `cases/bulk`). It is **M2.5's**, not a slice's — `ExecutionFact` read model, `ReportModel`,
  Thymeleaf + headless-Chromium renderers, an async job pipeline, 7–8 weeks. Checked in §64 and
  deliberately not started. `#confirmed`
  **Decomposed 2026-08-04 into `docs/plans/2026-08-04-m2.5-reporting-slice-plan.md`** (10 slices,
  one migration). **R1+R2 landed in §86, R3 in §87, R4 in §88; R5–R8, F1 and F2 remain, and `/reports` (the saved-definition GET) is still a dead
  contract** — nothing in §86 is reached through an endpoint. Seven facts that pass verified against
  the code and that a reader should not re-derive (1, 2 and 6 have moved since; struck inline):
  1. ~~**There is no `com.tcms.reporting` package.**~~ → **created in §86** (see the seam above);
     ~~ten~~ **eleven** modules now exist and `ModuleStructureTest` hard-codes them. What is now
     true instead: **`shared` is the last of plan §3's modules that does not exist**, and decision E
     declined to create it for two storage ports and an enum — ~~which is still the answer~~ →
     **§125 built `storage` instead** and struck §3's `shared — storage, audit, attachments, search`
     down to `audit, search`. The two ports have a module; `shared` still does not, and now needs a
     reason of its own rather than inheriting theirs.
  2. **`suite_run_summary`, `plan_snapshot`, `report_definition` and `report_artifact` are in `V1`,
     RLS-forced, and have ZERO code** — no entity, no query, no writer. ~~`execution_fact` too~~ →
     **it got its first writer in §86**; ~~the other four are untouched~~ → **`report_artifact` got
     one in §87 and `report_definition` a full CRUD surface in §92**, so what is left with zero code
     is **`suite_run_summary` and `plan_snapshot`**. `case_health` is now written
     by **two** modules: `repository` owns `never_executed`/`long_unexecuted` (§53) and `reporting`
     owns `failing_repeatedly` (§86) — **3 of 9 signals**.
  3. **There is NO `report_job` table** — not in `V1`, not in the ERD. `report_artifact.storage_key`
     is `NOT NULL`, so an artifact row cannot precede its bytes and cannot serve as the job record.
     The 202-and-poll contract therefore has no home in the schema; that is the plan's only migration.
  4. **`ReportJob.downloadUrl` is required-and-nullable with no server, and there is no poll route
     and no artifact-content route in `openapi.yaml`.** §67's `Evidence.url` finding, one module over:
     the generate slice must land three operations or invent a URL.
  5. **`report:view` / `report:export` are seeded by `V3` to five of six built-in roles** — all but
     Automation Service — and have **zero Java consumers**. First-consumer pattern, 8th instance.
     **But the gate cannot be shown to discriminate**: there is no API-token authentication
     (`com.tcms.auth` never names `api_token`; that is M3), so no persona a test can build lacks
     either key and a gate mutation reddens nothing. §71's `company:teams` coincidence in a worse
     form. The way out is a custom `role` row — that table is instance-level with a global unique
     name, so a fixture may insert one.
  6. **`execution_fact.status_kind` is `NOT NULL` while `test_execution.status_id` is nullable**, so
     the fact log can structurally hold only RECORDED executions. Coverage denominators can never
     come from it — the §46 finding, arriving from the schema. **§86 made this the inner join to
     `case_status`** rather than a separate predicate, and R6 must be written around it.
     **Related, found in §86 and not built**: `execution_fact.executed_by` references `app_user`
     only, while `test_execution` also has `executed_by_token`. Harmless while token auth is M3 and
     nothing writes it — and the day it lands, every ingested result carries a null actor and §6e's
     per-tester throughput section silently excludes CI.
  7. **Naming collision, live**: `execution.PlanSnapshot` is §74's plan-version value object and has
     nothing to do with the `plan_snapshot` reporting TABLE, which has no code. A writer for the
     table must not reuse the name.
  Also: `case_health` has **ten** boolean columns and `HealthSignal` **nine** members — `incomplete`
  has no enum member and therefore no reader (unchanged by §86, which built `failing_repeatedly`).
  And `V1` ships **four** partial indexes on `case_health` (`outdated_spec_changed`,
  `stale_approval`, `never_executed`, `unlinked`) — **`failing_repeatedly` is the first signal with a
  writer and no index**, found in §86 and deliberately not fixed there, since an index is a
  migration and that slice has none. `doc_link` has no entity and no writer, which blocks
  the coverage matrix and doc-structured reports. `frontend/src/mocks/handlers/reporting.ts` answers
  `generate` with `id`/`requestedAt`/`status: 'QUEUED'` — three fields the contract does not declare,
  the last not in its enum — with no consumer. `#confirmed`
- **`ConditionRepository.findLibrary` orders by a column nothing maintains.** `usage_count` reads 0
  on every condition row, so its `ORDER BY usageCount DESC` has never ranked anything. §64 solved
  the same problem for shared steps by deriving the count; conditions were left alone because their
  usage spans `case_condition` AND `suite_condition` with inheritance on the suite side, which is a
  design question rather than a join. `#confirmed`

- ~~**`test_plan_version` is the only one of §6j's four versioned aggregates with no writer**, and
  `entry_criteria`/`exit_criteria` have no writer at all~~ → **all three closed in §74** (see the
  plan-editor seam above). What is now true instead: **`test_plan_version` has a writer and no
  reader** — nothing serves a plan's scope history, so the rows accumulate unread, which is the state
  `suite_version` was in for a long time. A plan timeline is the natural consumer and it is §6p's.
  `#confirmed`

- ~~**Four review-workflow permission keys still have no consumer in Java**~~ → **three got one in
  §76** (`case:review`, `suite:review`, `plan:review`) **and the fourth, `case:comment`, in §79** —
  the review block of the catalogue is fully consumed. ~~**Reviewer ASSIGNMENT is the only unbuilt
  half of §6g's apparatus**~~ → **built in §80**, and it needed **no new key** — one seeded to
  nobody is a 403 nobody can grant out of (§67/§79) — so asking costs the subject's `*:review`,
  §79's argument for resolving a thread: a statement about the review's progress. ~~What is now true
  instead: **§6g's apparatus is complete and has no INBOX.**~~ → **the inbox landed in §81**, as a
  **board generator** rather than as anything in `project` (see the seam above), which is the second
  of the three options and the only one that needed nothing new built; `ix_review_assignment_assignee_user`
  was added one slice ahead for exactly this query, so `V26` is still free. What is now true instead:
  ~~**the last unbuilt half of §6g is the GATING RULES**~~ → **built in §82** (see the seam above);
  **§6g is now COMPLETE — every part of the workflow has an implementation.** Also still unbuilt and
  deliberately so: **the board's other two generators**
  (`DOC_UPDATE` needs §6i's stale-case flagging, which nothing computes; `DEFECT` needs the local
  defect record, which nothing writes — a feature apiece, not a generator apiece); ~~**§6l's
  cross-project "My Work" board**, so the inbox is **per project**~~ → **built in §83** (see the seam
  above): the inbox is now **per project OR personal across every project the caller can see**, and
  the endpoint did not move. ~~**§6l's TEAM board — the only one of its three with no endpoint**~~ →
  **built in §84** (see the seam above), which **completes §6l's three boards**: one operation, three
  selectors, and the endpoint did not move again. **§6l's per-project generator toggles**; **WIP
  limits and configurable columns**; **§6l's cap on derived generation** (located three times now, on
  the generators, built zero); **a team assignee for §6l's unclaimed pool** (no such column —
  `work_item.assignee_id` references `app_user`, §84); and **notifications** — all four of §6g's
  events have a trigger and there is no channel — remain unbuilt. `#confirmed`

- **`audit_event.target_type` is spelled two ways and nothing reads it yet.** Every service writes
  the snake_case table name (`test_run`, `test_case`, `test_plan`, `doc_page`, …) except
  `ExecutionService`'s `"TEST_EXECUTION"` and `ArtifactService`'s `"ARTIFACT"`. Latent rather than
  live — `audit:view` and `project:audit` both have **zero consumers** — and found-not-fixed in §72,
  because changing it splits existing rows. It will present as an audit filter that silently matches
  nothing, which is §45's absent-row family. Sweep it when the audit screen is built. `#confirmed`

- **Per-project §6g policy lives in `project_settings` since §77, and `ProjectSettingsService.effective`
  is the ONE read.** `ProjectSettings` (entity, PK = `project_id`) + `ReviewSettings` (the value that
  crosses module boundaries) + `EditAfterApproval` + `ProjectSettingsService`/`Controller`/`View`/
  `Request`, **`V23`**. Four things had nowhere to live and all four were live defects: the opt-in
  (so §76's apparatus applied to every project unconditionally), `review.required_peer_reviews`
  (mapped, written from a column default, read by nothing), author-cannot-approve-own-work (§6g's
  *"single most valuable setting"*, absent), and the edit-after-approval mode (`ReviewPanel` rendered
  the Retain half; the Re-review half did not exist).
  **A typed table, not `project.settings JSONB`** — §74's closed-vocabulary argument, because a
  misspelled key in a blob reads identically to an unconfigured setting. The **ERD says `settings`
  belongs to `company`** and §6g says the opt-in is per project *and per artifact type*; settled with
  the user in favour of §6g, and `company.settings` is left unmapped and unclaimed.
  **`reviewEnabledFor` is a switch over `ReviewSubjectType`**, so a fourth type is a compile error.
  **"Off" is NARROW: it suppresses the reviewer apparatus and NOT the status ladder.** The wide
  reading would have removed approval from every existing project on upgrade day, since off is the
  default. For the same reason, switching review off does **not** close reviews already open — the
  guard in `ReviewService.moved` covers only the *opening* arm. `#confirmed`
- **`ReviewService.refusesEach` is the policy check, and `refuses` DELEGATES to it.** Asked by each
  ladder *before* it applies anything, returning the message to refuse with — a precondition
  discovered inside `moved` could only be enforced by throwing, and `moved` runs *after* the status
  is written. Batch-first because `CaseBulkService` evaluates up to 400 rows (`findOpenForSubjects` +
  one participations query), and because a single-item and a bulk copy of one policy is §58's E1 —
  the exact divergence §75 found between `BulkOperations.permission` and `PlanLifecycle`.
  **The peer count is read from `review.required_peer_reviews`, never the live setting** (raising the
  bar must not freeze reviews in flight), counts **each person's LATEST verdict** in Java (the trail
  is append-only so relenting is visible; `COUNT(DISTINCT user) WHERE LGTM` is the reading that makes
  it decorative), and **never counts the submitter's own LGTM** whatever the author rule says.
  `#confirmed`
- **`EditAfterApproval.RE_REVIEW`'s demotion is deliberately NOT gated on `*:approve`, and it looks
  like §75's defect reopened.** §75: leaving an approval is an approval act. This is not that, for
  three reasons that must hold together — nobody *requested* the transition (the caller asked to
  edit, which §6g permits, and the project declared in advance that editing invalidates approval);
  it is opt-in and off by default, so a `RETAIN` project keeps all of §75's protection; and it writes
  its **own audit verb** (`*.re_review_required`), never a field on the save's row. Gating it on the
  approve key would make an approved artifact **uneditable by the people §6g expects to edit it**.
  The queued mutation applying that gate is flagged as the most important row in the file: it is the
  obvious reading and it looks like a fix. Wired into `CaseUpdateService`, `SuiteWriteService`
  (rename only — the composition case is named and not built) and `PlanUpdateService`. `#confirmed`
- **The settings GET is gated on project VISIBILITY, the PUT on `project:settings`** — §69's key,
  second consumer. Not a relaxation: §6g's "a project that never turns it on should see no extra UI
  at all" is a decision every artifact screen makes for every reader, Viewer included, and none of
  them holds the write key. `effective` takes **no user and asks no permission** — every caller has
  already been through its own gate, and the value is the project's behaviour rather than something
  a caller may or may not see. `#confirmed`

## Session log

- 2026-08-07 · EXTEND · **attachment owners, phase 1** (§126, M3 slice 10, decision round 3 step 6).
  `V39` narrows `attachment.owner_type` to five values (#50); `storage` gains `AttachmentOwner`,
  `BlobUpload` and three factories, and `EvidenceStore` is renamed `AttachmentStore` with its
  property key deliberately kept; `repository` gains the `CASE` family and `TestCase.attachments`.
  2050 tests. Phases 2–4 (`DOC_PAGE`, `ARTIFACT`, the frontend) are not built.
- 2026-08-07 · EXTEND · **the `storage` module** (§125, M3 slice 9, decision round 3 step 5). Two
  phases. `Attachment` + `AttachmentRepository` + both blob ports + both filesystem adapters move to
  `com.tcms.storage`; `FOUNDATION_MODULES` gains a fourth entry and a new ArchUnit rule
  (`storageDependsOnTenancyAlone`) guards the property that makes it legal; phase 2 deletes
  `ReportBlob` and collapses the duplicated adapter body into `FilesystemBlobStore`.
  **The find worth carrying forward:** a javadoc that names the *exact condition* for a future move
  (`Attachment`'s "a second caller to design against") and a factory pair that keeps a vocabulary out
  of call sites made this a package line and two modifiers — the opposite outcome from §121 phase 3's
  seam, which guessed at a consumer and had to be deleted. **Commit to a shape, leave the caller
  open.** Second find: two identical files in two packages are never read side by side, so the report
  adapter's traversal guard went untested from §87 to §125.
- 2026-08-07 · EXTEND · **grant-instance-admin** (§122, M3 slice 7). The operator tier's first and
  only writer. `GrantInstanceAdminService` / `…Result` / `…Command` in `provisioning`, plus the verb
  in `OperatorCommands`; `GrantInstanceAdminServiceTest` (7, `gia-` namespace). No migration, no
  HTTP surface, no screen — and none should exist until an instance-admin surface does
  (`RoleGrantService`'s own header). **The find worth carrying forward:** this is the third slice
  running where a thing the schema *and the docs* both describe turned out to have no writer at all
  (§120's `stale_automation`, §121's open question #24, now the whole SYSTEM tier). Ask it of any
  `V1` column before assuming it is live. Ten mutation rows queued, none run.
  **Written concurrently with §121's lane in the same tree** — `compileJava` went red on an
  untracked file that had not existed ninety seconds earlier, and the correct move was to stop
  rather than repair it. `#confirmed`
- 2026-08-07 · EXTEND · **The ingestion history reads** (§119 phase 1, M3 slice 4). Two `GET`s over
  `ingestion_job`; the detail is the resource `POST /ingestions`' `Location` has always named and
  never had. New: `IngestionReadService`, `IngestionJobSummary`, `IngestionJobRow`, `UnmatchedTests`;
  `findSummariesInProject` (constructor projection) and `findOneInProject` replace the caller-less
  `findByProjectIdOrderByCreatedAtDesc`; `TestRunRepository.findRunKeys`. **No migration, no key, no
  entity.** Gate: **1953 across 408 classes, 0 failures, 0 skipped** (1946/407 → 1953/408).
  **Two findings.** The replay served `unmatchedCount: N` beside `unmatched: []` — the names were
  computed once and only ever lived in the blob, and the caller who gets a replay is a CI *retry*.
  And `ReportFormat` names two unrelated things in one contract (CI dialect vs report export
  format), which surfaced as a duplicate identifier on the frontend and is aliased `IngestionFormat`
  there. 8 mutation + 11 browser rows queued, none run.

- 2026-08-06 · EXTEND · **Version divergence** (§114, **R8** — the last slice in M2.5's reporting
  plan). A sixth section on `AggregateExecutionReport` over `selectedCte`, one row per (case,
  version) for cases the selection ran at more than one version, plus a header flag printed even at
  zero. New: `ExecutionSource.caseVersionNumbers` (the seam's third method, seventh table),
  `AggregateVersionDivergenceTest` (13 tests, prefix `c4`). **No migration, no key, no entity, no
  frontend code**; contract change is prose. Gate: **1852 across 377 classes, 0 failures, 0 skipped**
  (1839/374 → 1852/377); frontend 958/958, run *after* the backend rather than beside it.
  **The finding is that what divergence costs is a property of the policy and differs in kind across
  the five**, so the row carries a computed `Counted in the totals` column and the narrative follows
  `blendsVersions(rows)` rather than the policy's name — prose keyed on a policy is a second
  implementation of it in a place no reader can check. **The slice has NO RED run** (tests written
  after the code, all green first time); recorded in §114 §5 and in mutation row R8-1, which is the
  only route to a measurement. 7 mutation + 4 browser rows queued, none run.
  Landed alongside a **concurrent strand's §113** (the deploy's second target), which is why this
  slice is §114: the number was taken between writing the section file and indexing it.
- 2026-08-05 · EXTEND · **Conditions get a writer** (§95 phase 1) — `POST` + `PUT
  /projects/{projectId}/conditions`, both on `library:manage`, the `PUT` with `If-Match` and a 409.
  New: `ConditionEdit`, `ConditionWriteRequest`, `ConditionUsage`, `ConditionWriteEndpointTest`
  (20 tests, prefix `c9` — computed free against the whole test tree rather than guessed, §19's
  defect having recurred five times); `Condition` gained a `create` factory, `applyEdit`, the four
  audit columns and `@Version`; `ConditionRepository` gained both count queries;
  `ConditionLibraryEndpointTest` gained 4 tests and had 2 reworked. **No migration, no table, no
  column, no new permission key** — policy count still 59, `V30` still head.
  **The finding is that `usageCount` was always zero and was one slice away from being an impact
  warning** — the column is now unmapped and both counts derived, and the count is deliberately
  **two numbers that must not be added** (see the seam). The contract had posed that exact question
  on `/shared-steps` and left it open; this answers it.
  `npm run gen:api` run and `npm run build` immediately caught the mock's condition fixtures missing
  both new required fields — the payoff of §79's no-cast rule, one build error rather than a page of
  runtime ones. Backend **1704/1704**, build clean; frontend build clean, lint 0, **805/805**.
  **Built and unverified** — 6 mutation rows queued, **zero run** (§65).

- 2026-08-05 · EXTEND · The step corpus (§94 phase 1) — backend 1649 → **1681** (+13, all in
  `StepSuggestionEndpointTest`, prefix `c4`). New: `V30__test_step_action_trgm.sql` (**head**, `V31`
  next free, **policy count still 59** — an index, no table), `StepSuggestionController`/`Service`/
  `Query`/`View`, `TestStepRepository.findSuggestions`. The editor's autocomplete corpus, **derived
  from every live step in the project rather than curated** — the correction to `shared_step`, which
  stayed empty because it needed populating. **Serves no id**: a suggestion is text copied, not an
  entity referenced, and the test pins the exact field set so it stays that way.
  **Finding, recorded and NOT fixed:** `V30` indexes the expression `lower(action)` because Postgres
  consults a functional index only for the expression it was built on — and `shared_step_title_trgm_idx`
  / `condition_title_trgm_idx` (V1) are both spelled on the bare column under `LOWER(title)` queries,
  so **neither is ever used**. Silent: right answers, maintained on every write, same seq scan.
  **Process error worth not repeating:** a second `./gradlew build` started while the first was in
  flight; both wrote `backend/build/` and the `Could not write XML test results` failures read as
  test failures. CLAUDE.md's one-build-one-daemon rule, hit directly.
  **Zero mutations run inline; 4 backend rows queued — built and unverified.**
- 2026-08-06 · EXTEND · The aggregate execution report (**R6 phase 1**) — the first report spanning
  more than one run, and the slice that spends decision D. New: `AggregateExecutionReport`,
  `AggregateSelection`, a duplicated `reporting.AggregationPolicy`; `ReportParameters` gains
  `idList` / `textList` / `optionalInstant`. **No migration (`V36` still free), no contract change,
  no key, policy count 61.** The report is registered by *existing* — `ReportComputations` is a
  registry, so no list was edited. `AggregateExecutionCrossCheckTest` (prefix `c1`) is the oracle;
  **two mutants were run and the second proved the fixture vacuous**, which is why `UNBLOCKED`
  exists. **§19's prefix collision, sixth instance**: `6a` is `CaseListEndpointTest`'s and the full
  suite went red in 64 tests of an unrelated class before the move to `c1`. Gate: **1806 across 363
  classes, 0 failures, 0 skipped** (1795 → 1806). ~~**Still owed by R6: the contract's `parameters`
  schema for this type**~~ → **shipped in phase 2.**
- 2026-08-06 · EXTEND · **R6 phases 2–4 (§111)** — the report becomes askable and its selection
  becomes tested. Phase 2: the contract names `aggregate_execution` and documents the selection
  (**prose only** — two descriptions and a `gen:api`; no schema, no operation, no field). Phase 3:
  `GenerateReportDialog` grows the second entry and its form, and **the run gate stops being
  global** — a project with no runs may ask for this report, asserted `toBeEnabled()` because the
  natural direction passes against the pre-R6 dialog. Phase 4: **`AggregateSelectionTest`**
  (prefix `c2`, slug `aes-`, 18 tests) closes the four queued mutation rows the oracle could not
  see, each re-run against its mutant. **The finding is R6-8 coming back GREEN**: the
  `CASE … ARRAY[NULL]::uuid[]` in `featureGroups` is *not* what keeps the unclassified row —
  `LEFT JOIN LATERAL … ON true` is, and `CROSS JOIN LATERAL` is the mutation that reddens. The
  javadoc claiming otherwise is struck through in place; **protect the word `LEFT`, not the
  `CASE`.** Also corrected: `AggregateSelection`'s *"a 400 at request time"* — a report's own
  parameters are refused by the **worker**, as a `FAILED` job, which is why the dialog makes the two
  reachable mistakes unexpressible. Gate: **1824 across 370 classes, 0 failures, 0 skipped**
  (1806/363 → 1824/370); frontend 958/958. **Nine mutations run across the three phases; 5 browser rows
  queued, none run.** R7/R8 remain unstarted.
- 2026-08-04 · EXTEND · The HTML renderer and the report header (§88) — **R4**, three phases, three
  commits, backend 1615 → **1628** (320 classes), frontend 705/705. New: `ReportHeader`,
  `ReportDocument`, `ReportHeaders`, `HtmlReportRenderer`; `ReportRenderer.render` takes a document;
  `ReportParameters.entries()`; the reporting mock serves HTML and a `{ header, report }` model.
  **No migration (`V27` still head), no table, no column, no key, policy count still 59, contract
  prose only.** Two recorded deviations from the plan: **no Thymeleaf** (the escaping guarantee is a
  counted test instead) and **no logo** (unmapped columns, no override column — branding is M6).
  **The one test that failed first time was the assertion, not the code.**
  **Zero mutations run inline; 9 backend rows and 5 browser rows queued — built and unverified.**
- 2026-08-04 · EXTEND · Saved report definitions (§92) — **R5**, three phases, backend
  1628 → **1649**. New: `V28__report_artifact_definition_on_delete.sql` (**head**, `V29` next free,
  policy count still 59), `ReportDefinitionRepository`/`Service`/`Controller`/`View`, `ReportTypes`,
  `ReportDefinition.create`, `definitionId` on `ReportJobService.request` (**signature changed** —
  `request(userId, projectId, definitionId, type, format, parameters)`), `ReportParameters.merge`,
  `openapi.yaml` **+2 operations** and a changed generate body (`required` is now `[format]`).
  **No new permission key, no new table, module declaration unmoved.**
  **The plan said R5 needs no migration and was wrong**: `report_artifact.definition_id` is a `V1`
  FK with the default `NO ACTION` while its `report_job` sibling is `ON DELETE SET NULL`, so deleting
  a *used* definition was a 500 on a route whose contract says 204.
  **Numbered §92 although its first two commits say §91** — a concurrent strand took the number
  mid-slice; see `docs/handoff/92-*.md` §0.
  **Zero mutations run inline; 10 backend rows and 5 browser rows queued — built and unverified.**
- 2026-08-04 · EXTEND · The report pipeline (§87) — **R3**, seven phases, seven commits, backend
  1553 → **1615**. New: `V27__report_job.sql` (policy count **58 → 59**, head migration, `V28` next
  free), three entities + two repositories, `ReportStore`/`FilesystemReportStore`, `ReportModel` +
  two renderers + two registries, `RunSummaryReport`, the service/runner/scheduler trio,
  `ReportReadService`, `ReportController`, `ProjectService.readGranted`, `openapi.yaml` **+2
  operations**, and the corrected reporting mock. `reporting` widened to
  `{"tenancy", "project", "identity"}`. **No new permission key.**
  **The finding is phase 6**: until it, both report gates were satisfied by an implementation with no
  gate, because no persona the suite could build lacked either key — fixed with the suite's **first
  two custom roles**, whose `@AfterAll` cleanup is itself load-bearing against `SeedDataTest`.
  **Zero mutations run inline; 11 backend rows and 8 browser rows queued — built and unverified.**
- 2026-08-04 · EXTEND · The fact log gets a writer (§86) — **R1+R2** of the M2.5 reporting plan, and
  the product's **tenth Modulith module**. New: `reporting/package-info.java`, `ExecutionSource`,
  `ExecutionFactRefreshService`, `ExecutionFactScheduler`, `ReportingProperties`;
  `ModuleStructureTest` 9 → 10 modules and its rationale rewritten; `IntegrationTestBase` gains
  `tcms.reporting.enabled=false`. **No migration (`V27` still free), no contract change, no key, no
  table, no column** — policy count unchanged at **58**, verified by listing the migration folder
  before writing any SQL rather than by trusting a document.
  **Three things the pass confirmed absent**: the `reporting` package (listed since M0, nine modules
  existed), a writer for `execution_fact` (five indexes, zero rows since M0), and a writer for
  `case_health.failing_repeatedly` (a tile since §47 and a board card since §53, both asserting
  *nothing is failing* on every deployment).
  **The slice is one decision — which columns an `ON CONFLICT` may touch.** Seven move, fifteen are
  frozen dimensions, and **`suite_id`/`suite_path` were added to the frozen set beyond what the plan
  named**, on the identical argument. **`allowedDependencies = {"tenancy"}` alone**, narrower than
  the plan proposed: a declaration is a fact, not a forecast, and R3 is named as what widens it.
  **`failing_repeatedly` is `FAILED`, not `NOT counts_as_pass`** (three BLOCKED runs are not a broken
  test), and the window must be **full** (N=3, refusing to start below 2).
  **Two defects the gate caught that reading could not**: `max(uuid)` does not exist in Postgres, and
  a fixture UUID prefix must be **hex** — `rf…` threw in the static initialiser and failed all eleven
  tests before one ran, §19's family with a new mechanism. **Found and not fixed**: `case_health` has
  no index on `failing_repeatedly` while `V1` ships four for other signals — the first tile that
  scans, and an index is a migration. Also named-not-built: `execution_fact.executed_by` cannot name
  an `api_token`, so M3's ingested results will carry a null actor and §6e's per-tester throughput
  will silently exclude CI. **Gate green, confirmed after the commit**: `./gradlew build` →
  **1530 → 1553** across 300 classes, 0 failures, 0 skipped, counted from the XML. `9d33f02` landed
  with that figure as arithmetic and the observation agreed. **The operational lesson: a subagent
  running the gate is a SECOND LANE in one tree**, and `CLAUDE.md`'s one-`build/`-one-daemon rule
  binds it exactly as it binds a concurrency worktree — six runs died on locks and daemon stops
  because the agent and the parent session both invoked `gradlew`, each `--stop` orphaning the
  other's test worker, which holds `output.bin` and poisons the next attempt. Diagnose by finding the
  holding process; do not retry. **Zero mutations run
  inline** (policy); 9 queued and 8 browser rows, the browser ones **all live-only** — the mock
  invents its own health rows and has never been fed by a fact log, so mock agreement is not evidence.

- 2026-08-04 · PLAN · Decomposed M2.5's **reporting half** into
  `docs/plans/2026-08-04-m2.5-reporting-slice-plan.md` — 10 slices (8 backend, 2 frontend), **one
  migration (`V27`, policy count 58 → 59)**, **no new permission key anywhere**. No code written.
  Seven verified state facts folded into Known unknowns above, of which three change what a reader
  would assume: **the `reporting` package does not exist**, **there is no `report_job` table
  anywhere**, and **the report gate cannot be made to discriminate without a custom test role**,
  because the only built-in role withheld `report:view` is Automation Service and token auth is M3.
  The plan's load-bearing decisions: `execution_fact` is written by an **idempotent recompute**
  (`CaseHealthRecomputeService`'s shape) and not by events, because with zero `CompanyScopedEvent`
  implementations and no outbox consumer an event-only fact log has **no rebuild path**; its
  `ON CONFLICT DO UPDATE` names only the **result** columns, since re-running would otherwise re-file
  historical results under the current taxonomy; the async worker is a **company loop over the
  un-RLS'd `company` table** (a global "find pending jobs" query sees zero rows under `V4`'s
  `nullif` and logs a successful tick having done nothing); output goes through a **second** storage
  port because `execution.EvidenceStore` is unreachable, with the E1 debt named rather than a
  `shared` module created; the aggregation **enum is duplicated and the algorithm is not**, with a
  cross-check test pinning the report against `PlanStatisticsService` for a single-plan selection;
  and the on-screen view reads a **stored `JSON` artifact** rather than a second preview code path.
  PDF, S3, scheduled reports, notifications, Mermaid, the coverage matrix and the evidence bundle are
  named as **not planned**, each with the missing infrastructure. Nothing in the codebase was
  changed; `V27` is still free.

- 2026-08-04 · EXTEND · §6l's team board (§84) — the last of its three with no way of being asked
  for, and a **third selector** on the operation that already serves the other two:
  `GET /work-items?teamId=…`. New: `BoardFacts.teamMembers`, `BoardService.teamBoard` +
  `forAssignees` + `present(cards, Set<UUID>)`, `WorkItemController.twoBoardsProblem`.
  **No migration (`V27` still free), no table, no key** — policy count unchanged at **58**.
  **The slice is one decision: the bound is the CALLER's project visibility, never the team's** —
  *"cards assigned to the team's members"* is one query, reads correctly, and renders another
  client's case key, title and project name to a reader who need not be on the team and need only
  guess a UUID. **§83's fixture could not have proved it** (its assignee is always the caller, so
  both bounds agree on every row), so §84 adds `TEAMMATE` — granted where LEAD is not — and asserts
  it as a **pair**, the second half being what stops the first from passing on a fixture row that
  silently failed to insert. `TeamDirectory` was **not** widened: its `find` gets a second consumer
  for existence, and the expansion is `BoardFacts.teamMembers`, at read time. **Nothing overturned**;
  three things checked rather than assumed, including that `work_item.assignee_id` references
  `app_user`, so §6l's team assignee for the unclaimed pool **does not exist** and is named not-built.
  Found and not fixed: **the two boards are not protected by the same line** — dropping the grant
  filter from `visibleProjects` leaves every `Gates` test green, because a named project goes through
  `visibleProject`'s own copy. All tests green on the first run. Backend 1513 → **1530** (17 in a new
  `BoardEndpointTest.TeamBoard`), 0 failures, 0 skipped. **Zero mutations run inline** (policy); six
  queued. `frontend/src/api/schema.d.ts` is **stale** — `openapi.yaml` changed and `npm run gen:api`
  belongs to the frontend half.

- 2026-08-04 · EXTEND · §6l's cross-project "My Work" board (§83) — `GET /work-items` with the
  project parameter left **off**, which is why §53 chose an instance-scoped path before the board
  existed. New: `ProjectService.readAllVisible` + private `visibleProjects`,
  `WorkItemRepository.findLiveInProjects`, set-taking `BoardFacts` generators,
  `BoardService.personalBoard` + `present`, `WorkItemController.unscopedBoardProblem`.
  **No migration (`V27` still free), no new key, no new table** — policy count unchanged at **58**.
  **The refusal is the feature**: no project and `mine=false` is a **400**, not a silent narrowing.
  **Two findings on review, neither a defect in behaviour**: the personal board's javadoc claimed a
  bound the code does not have — the *answer* is person-sized, the *read* is the whole visible
  company — corrected in `BoardService.personalBoard` and `WorkItemController.list` rather than
  restructured, since pushing `mine` into the four sources is what `present` refuses by name; and
  `visibleProjects` evaluates `canView` once per project in the company on every load. Backend
  1505 → **1513** (8 in a new `BoardEndpointTest.MyWork`), 0 failures, 0 skipped. **Zero mutations
  run inline** (policy). `frontend/src/api/schema.d.ts` is now **stale** — `openapi.yaml` changed and
  `npm run gen:api` belongs to the frontend half of this slice.

- 2026-08-04 · EXTEND · §6g's gating rules (§82) — the last unbuilt part of the review workflow, and
  a slice that built almost nothing new: **two boolean columns** (`V26`) and the two places that
  read them. New: `ApprovalGating`, `PlanLifecycle.startsWithoutApproval` +
  `APPROVAL_REQUIRED_MESSAGE`, `CaseFacts.unapprovedCases`, `ProjectService.holds`,
  `RunCreateService.Outcome.Refused` + `gate`, `PlanStatusService.moving` + `gate`,
  `overrideApprovalGate` on `TestRunInput`/`PlanStatusInput`. **The plan rule is an EDGE and the
  target-keyed reading strands every closed plan for ever**; **the case rule binds on the resolved
  set or the plan is the bypass**; **the override invents no key and is derived** (its holder can
  approve first, so it widens nothing and only changes the record). **Not ANDed with the review
  opt-in** — §77's "off is narrow". **Five backend tests red on the first run, four of them a
  fixture leak**: `project_settings` was not on `clearMutableRows`, so one test turned the gate on
  for every test after it and **three casualties predate the slice**. Policy count unchanged at
  **58**; `V27` is the next free number. Backend 1487 → **1505**. **Zero mutations run inline**
  (policy); ten queued, fifteen browser rows.

- 2026-08-03 · EXTEND · The reviewer's inbox (§81) — §6l's `REVIEW` generator, absent since §53,
  built in **`work`** because **no other module may write its query** (four tables, three modules).
  New: `BoardFacts.PendingReview`/`pendingReviews`, `BoardService.PREFIX_REVIEW` + the review card,
  `frontend/src/mocks/db/workItems.test.ts`. **No migration (`V26` free), no contract change** —
  `kind: REVIEW` went into `openapi.yaml` in §53 ahead of its generator. **A team is expanded at
  READ time**, the mirror of §80's write-time refusal, for the same stated reason. **The finding: the
  MOCK had shipped this feature since Phase 1 and wrong in four ways** — a card per case in
  `IN_REVIEW`, in the wrong column, assigned to the case's **owner** (the person the server refuses
  as an assignee), and never for a suite or a plan. §52's class in its most expensive form: the mock
  was agreeing with an *idea*, not with a server, and it looked finished, which is why nobody built
  the real one for 28 slices. **Second defect made live**: every card's key linked to a case search,
  so a plan review rendered "no cases match" — `linkFor` now keys on `sourceType`, with a suite going
  to the case list filtered to it because a suite has no page of its own. §79's cast repair carried
  to `BoardPage.test.tsx`. Backend 1476 → **1487**; frontend 640 → **649**. **Zero mutations run
  inline** (policy); nine queued, eleven browser rows, and §80's browser row 14 struck through
  because it recorded this absence as correct.

- 2026-08-02 · EXTEND · Reviewer assignment (§80) — the last unbuilt half of §6g's apparatus, and
  **the first review slice that needed a new table**: `review_assignment` is not in `V1`, because
  `V1` designed the record of what happened and never the worklist of what is outstanding. New:
  `ReviewAssignment`, `ReviewAssigneeEdit`, `ReviewAssignmentRepository`, `ReviewAssigneeView`,
  `ReviewAssignmentRequest`, `TeamDirectory` + `TeamRefView` + `identity.IdentityTeamDirectory`,
  `ReviewService.assign`/`unassign`/`store`/`addAssignee`/`removeAssignee`, two controller routes,
  **`V25`** (policy count 57 → 58). **The slice is one idea** — an assignment is a worklist entry and
  not part of the audit record — from which the hard delete, the team cascade (§71's 409 NOT
  extended) and the write-nothing re-ask all follow. **No new key invented** (one seeded to nobody is a 403
  nobody can grant out of), so asking costs `*:review`, and the two gate mutations are a **pair** proving it
  is neither the tier below nor the tier above. **The build caught the contract slip**:
  `ReviewAssignmentInput` shipped with no `required` array, and `ContractConventionTest` failed —
  both fields are required-and-nullable, `RoleAssignmentInput.projectId`'s shape, or "ask nobody"
  would be expressible only by omission. **Zero mutations run inline** (policy); eight queued.
  Backend 1458 → **1476**; frontend 627 → **640** (9 written, **4 generated** by §70's data-driven
  checks discovering the two new `api.*` call sites, `api.del` among them).

- 2026-08-02 · EXTEND · Review comments (§79) — `review_comment`'s first writer ever, and
  `case:comment`'s first consumer in Java, which empties the review block of the catalogue. New:
  `ReviewComment`, `ReviewCommentEdit`, `ReviewCommentRepository`, `ReviewCommentView`,
  `ReviewCommentRequest`, `ReviewCommentResolutionRequest`, `ReviewSubjectType.COMMENT_PERMISSION`,
  `ReviewService.comment`/`resolveComment`/`refuseIfClosed`/`unwrap`/`threads`,
  `ReviewController.respond` (three write paths, one mapping), `SeedDataTest.everyReviewerIsAlsoACommenter`,
  **`V24`**. **The slice is one decision**: the catalogue has one comment key and three review keys,
  and adding the two missing ones would seed keys granted to nobody — so `case:comment` is asked for
  all three subject types, and the ladder that makes that sound is a computed check. **Resolving
  costs `*:review` instead**, and the two gate mutations are a pair that redden one test each.
  **The defect: resolving twice re-stamped the record** and would have handed `resolved_by` to
  whoever clicked last — one of sixteen tests failed on the first run, because it was written from
  the contract sentence rather than the code. `V24` adds `resolved_by` (an act with no actor is not a
  record) and the read path's index; no new table, so the policy count stays 57. **Frontend finding:**
  `ReviewPanel.test.tsx`'s fixture ended `} as Review`, so a new required field reddened all eleven of
  its tests at runtime with zero type errors — §65's finding in a second file; the cast is gone.
  17 backend tests, 7 frontend, **0 mutations run inline** (policy, followed), 7 queued.
- 2026-08-02 · EXTEND · Per-project review settings (§77) — §6g's four settings get the row they have
  needed since the workflow was designed. New: `ProjectSettings`, `ReviewSettings`,
  `EditAfterApproval`, `ProjectSettingsRepository`/`Service`/`Controller`/`View`/`Request`,
  `ReviewService.refuses`/`refusesEach`/`reReviewOnEdit`, `ReviewRepository.findOpenForSubjects`,
  `Review.getRequiredPeerReviews`, **`V23`** (the first table created since `V1`).
  **The ERD/plan conflict was put to the user, not decided in a comment**: the ERD puts `settings` on
  `company`, §6g requires per-project-per-artifact-type. **The gate run produced a mutation result
  for free — 18 of 1419 reddened**, 17 of them `ReviewEndpointTest` finding zero reviews because its
  fixture creates projects by direct insert and therefore gets what a real fresh project gets: review
  off. §72's fixture rule from the other side — there the mock sat in a state no install produces,
  here the *tests* assumed a feature was on. The 18th was `SchemaConventionTest` 56 → 57, working as
  designed. 22 tests, **0 mutations run inline** (policy, followed), 11 queued. The MUI slot trap
  fired a **fourth** time (`inputProps` vs v7's `slotProps`) — `muiTestIds` owns the shape, not the
  prop name, which is the gap; caught by `npm run build`, not by the test runner.
- 2026-08-02 · EXTEND · The review record (§76) — `review` and `review_participation` get their first
  writer ever, and `case:review`/`suite:review`/`plan:review` their first consumers. New:
  `ReviewService`, `Review`, `ReviewParticipation`, `ReviewState`, `ReviewStanding`,
  `ReviewSubjectType`, `ReviewView`, `ReviewParticipationView`, `ReviewParticipationRequest`,
  `ReviewController`, `ReviewRepository`, `ReviewParticipationRepository`, `PeopleDirectory` +
  `IdentityPeopleDirectory`, `project.UserRefView`, `LifecycleLadder.standingOf`,
  `PlanLifecycle.standingOf`, `Suite.getCurrentVersionNo`, **`V22`**. **The placement decision is most
  of the slice**: three writers in two modules that cannot depend on each other, so the record lives
  in `project` and a `review` module is refused with its cost written down. **The E1 finding:** writing
  the third consumer of §6u's naming rule exposed `CaseOwners` and `ExecutionPeople` as byte-identical
  copies; both now delegate to the port. **`V22` adds `WITHDRAWN`** because a withdrawal is not a
  rejection, plus the partial one-open-review index. 22 tests, 4 backend mutations run (**1 / 2 / 1 /
  6**) and 3 frontend (1 each), 3 queued. **The frontend mutation that did not redden is the finding**:
  `waitFor(...toBeDisabled())` passes on `useCan`'s pending state (§66), so the test was rewritten as a
  positive assertion plus a barrier on `PermissionGate`'s denial wrapper.

- 2026-08-02 · EXTEND · The suite lifecycle (§75) — `suite.lifecycle_status` had exactly one writer
  (`Suite.create`, writing `DRAFT`), so three of four values were unreachable and every suite on
  every real instance has been a draft for ever; `Suite.applyEdit`'s javadoc named the slice. New:
  `LifecycleLadder` (shared by suite AND case), `SuiteStatusService`, `SuiteStatusRequest`,
  `Suite.applyStatus`, `SuiteService.viewOf`, `PUT /suites/{suiteId}/status`, six audit verbs,
  `BulkOperations.CASE_KEYS`, `CaseBulkService.leadOnly`/`holds`. **§74 named the reviewer apparatus
  as next and this deliberately is not it** — §28's re-derive rule found §6g's ladder has three
  subject types and the suite's was unbuilt. **The defect: `BulkOperations.permission` was keyed on
  the TARGET**, which §73 named as the weaker shape and left, so a Test Engineer could bulk-rescind a
  lead's approval on cases; fixed per row. **The mutation is the proof — dropping the unmaking arm
  reddens exactly 2 out of 112, one per aggregate.** Fixture finding in both directions: the backend
  fixture seeded every suite `APPROVED` while no real suite ever was, and the mock's fixtures have
  shown approved folders since Phase 1. 19 tests, 4 backend mutations run inline (**2 / 3 / 1 / 2**)
  and 2 frontend (1 each), 3 queued. **No migration — `V22` still free**, and the reviewer slice
  needs it (`project` has no `settings` column).
- 2026-08-02 · EXTEND · The plan editor (§74) — `entry_criteria`, `exit_criteria` and
  `test_plan_version` get their first writers at once, and a plan's scope becomes editable at all.
  New: `PlanUpdateService`, `PlanUpdateRequest`, `CriterionInput`, `PlanScope`, `PlanSnapshot`,
  `PlanVersions`, `TestPlanVersion` + repository, `PlanEdit.validateUpdate`,
  `PlanCriteria.METRICS`/`isKnownOperator`/`serialize`, `TestPlan.applyEdit`/`stampVersion`,
  `PlanScopeItemRepository.findForPlans`/`deleteForPlan`, `PATCH /plans/{planId}`,
  `PlanController#onWriteRace`, `suiteIds`/`caseIds` on the detail. **The orientation finding changed
  the shape before any Java**: the detail served `caseCount` — an expansion — and no scope ids, so
  the editor could not write back what it was served (§68's finding one aggregate over). **The defect
  found: `TestPlan` mapped `lock_version` with no `@Version`**, so it was `0` for ever and an
  `If-Match` could not fail; the sweep found `TestRun` and `work.WorkItem` the same, both left with a
  reason. 30 endpoint tests, 4 backend mutations run inline (**3 / 2 / 4 / 1** red) and 2 frontend
  (1 each) — the version mutation is the one to read, since bump-on-every-save leaves every scope and
  criteria test green. **No migration — `V22` still free.**
- 2026-08-02 · EXTEND · The plan lifecycle (§73) — `test_plan.status` had exactly one writer (the
  create factory, writing `DRAFT`), so four of five values were unreachable and `closed_at` was a
  `V1` column the entity did not map; `TestPlan`'s javadoc named the slice twice. New:
  `PlanLifecycle`, `PlanStatusRequest`, `PlanStatusService`, `PlanDeleteService`, two routes,
  `TestPlan.applyStatus`/`softDelete`, `TestRunRepository.countLiveByPlan`, seven audit actions.
  **Three permissions get their first Java consumer at once**; `plan:review` deliberately stays
  unconsumed. **The finding is the gate on the EDGE** — the obvious target-keyed reading passes an
  "an engineer cannot approve" test and still lets that engineer rescind a lead's approval; the
  mutation reddens **exactly 2** and nothing else notices. Two pre-existing frontend defects, both
  in `PlanListPage`: the colour map is the **sibling** of the one §72 fixed on the run list and
  §72's sweep never ran the grep, and the exit-criteria column read `!c.met` where null means *not
  evaluated*. 23 endpoint tests, 4 backend mutations run inline (2/3/1/1 red), 4 queued.
  **No migration — `V22` still free.**
- 2026-08-02 · EXTEND · The run lifecycle (§72) — `test_run.status` had been written exactly once
  in the product's life (to `NOT_STARTED`, by the factory), so every run on every real instance read
  "not started" for ever and `started_at`/`completed_at` were never filled. Three production
  javadocs named the slice by name, and `RunDetailPage` carried a comment stating a rule nothing
  implemented. New: `ExecutionTargets`, `RunLifecycle`, `RunStatusRequest`, `RunStatusService`,
  `TestRun.begin`/`applyStatus`/`isClosed`, `PUT /runs/{runId}/status`, `Outcome.RunClosed` ×2, four
  audit actions. **`run:close` is the fifth no-consumer permission and the FIRST that
  discriminates** — the gate mutation reddens exactly 1, where §71's could redden nothing.
  **Not §52's "mock agrees with itself"**: the mock had the same hole and looked right only because
  its fixtures ship runs already `IN_PROGRESS` — the general form is that **the mock's fixtures
  never sit in the state a fresh install produces**. Two pre-existing defects found: the run list's
  colour map was `Record<string, string>`, carried a `PLANNED` that is not a run status and was
  missing `NOT_STARTED` (so every real run rendered grey); and the audit `target_type` spelling
  above. 20 endpoint tests, 4 backend mutations run inline (1/2/2/1 red), 3 queued. **No migration —
  `V22` still free.**
- 2026-08-02 · EXTEND · Team management (§71) — `team`'s first writer after readers on three sides
  and none at all: `provision-company` seeds no team, `Team.create` had only test callers,
  `rename`/`reparent` had none, so `GET /teams` answered `[]` on every real instance and the grant
  path's team branch was unreachable. New: `TeamWriteService`, `TeamEdit`, `TeamInput`,
  `TeamMemberInput`, `TeamController`, `AdminDirectoryService.teamViewOf`/`renderTeams`,
  `AdminReach.manageTeams`, three repository counts/lookups, `V21`. `Team.create` gained a
  `description` parameter (4 test call sites) and `rename`/`reparent` were COLLAPSED into
  `applyEdit` — two mutators each stamping `updatedAt` for one PUT is a half-applied row waiting to
  happen. **`company:teams` got its first consumer in Java** — the fourth instance of that shape.
  **The finding is what the mutations could NOT do**: neither distinguishes `company:teams` from
  `company:members`, because `V3` grants both to the same roles; recorded as a computed check rather
  than papered over. 35 endpoint tests + 1 seed check, 2 mutations run inline (exactly 6 red / 2
  red), 3 queued. **`V21` drops a constraint and creates an index in its place** — the first
  migration to do that, so the queued in-place check is worth more than usual.
- 2026-08-02 · EXTEND · The classification vocabulary editor (§69) — `classification_value`'s first
  Java writer; the gate that depends on the dimension row; `V20`. New seams above. **Two orientation
  findings before any code**: `locked` meant three different things and one of them made
  `company:vocabulary` cover nothing; and `@ColumnTransformer` was missing on
  `ClassificationValue.path` exactly as `Suite.path`'s javadoc predicted by name. **First mutation
  reported 4 where the honest answer is 3** — a broken gate let one create succeed, so a second test
  failed on the duplicate check rather than the gate. Rename the fixtures apart before trusting a
  count.
- 2026-08-02 · EXTEND · Classification assignment (§68) — `case_classification`'s first writer after
  three readers and no writer since `V1`; the last of §5's three scheduled Phase-2 gaps and one of
  `CaseUpdateRequest`'s refused six. New: `ClassificationVocabulary`, `CaseClassifications`,
  `CaseClassification.of`, `CaseClassificationRepository.findForCase`,
  `TestCase.classificationValueIds` on the contract (detail only), `OpenApiContract.requiredOf`.
  **The orientation pass changed the shape twice**: the case served NAMES with no id anywhere, so
  the editor could not round-trip (hierarchical Feature ⇒ names are not unique); and the "leaf-only"
  rule the editor's comment used to justify keeping the field read-only **does not exist anywhere in
  the specs** — a parent is assignable. The real content is the reconcile's BOUND — see the seam
  above. **Two pre-existing defects found**: `TestCaseDetailView` never served `createdBy` (required
  via `allOf`), which inverted §66's `OWN` delete gate on the only screen that has one, now caught by
  a computed check; and `CaseDetailService` was a fourth copy of `CaseRenderer`. 18 endpoint tests,
  2 mutations run (4 red / 1 red), 4 queued. **No migration.**
- 2026-08-02 · EXTEND · Evidence attach and delete (§67) — `evidence` + `attachment` get their first
  entities and writers after being in `V1` from the beginning. New: `EvidenceStore` port,
  `FilesystemEvidenceStore`, `StoredBlob`, `Attachment`, `Evidence`, both repositories,
  `EvidenceUpload`, `EvidenceViews`, `EvidenceService`, `EvidenceController`; three routes under the
  run path (attach, delete, GET content). **The download route is not optional**: `Evidence.url` is a
  REQUIRED contract field that had no server, so attach alone would have had to invent it.
  **Rows-then-bytes ordering** is the slice's real content — see the seam above. `V1`'s
  "immutable once the run closes" is deliberately NOT implemented and the reason is in `Evidence`'s
  javadoc (`ExecutionService.record` already amends on a COMPLETED run, so gating evidence alone
  makes the screenshot stricter than the verdict). Adding `Outcome.Invalid` was a **compile error**
  in the delete handler's switch — the sealed-type property working. **No migration.**
  Four mutations run; the `OWN` one reddens exactly 2 while `deletesOwn` and the lead's test stay
  green. Tests added to `ExecutionEndpointTest` as an 8th `@Nested` class rather than a new file, to
  reuse its personas and avoid a 6th UUID-prefix collision.
- 2026-08-02 · EXTEND · Put permission qualifiers on the wire (§65), the rung §61 deferred. New:
  `HeldPermissionRow` projection, `MeController.HeldPermission`, `PermissionSet.qualifierOf/qualifiers`.
  **Collapsed `evaluate` and `qualifierOf` into one traversal** — the replace-don't-union boundary
  was written out twice and nothing failed if one copy was edited. **Deleted four repository
  queries** (`find*Qualifiers`); none had a direct test, which is why the duplication survived.
  `PermissionSet` is now a map and refuses a `NONE`-qualified key. Contract: `EffectivePermissions.
  permissions` items are `HeldPermission`, plus a `PermissionQualifier` enum with **no `NONE`**.
  New: `SeedDataTest`-style catalogue-derived agreement test over five personas × every key.
  **No migration.** Mutation-verified once (hard-coding `ALL` reddens exactly 8); two further
  mutations are QUEUED, not run — see `docs/verification/backend-mutation-checks.md`, the new
  standing policy that batches mutation checks instead of paying them per slice.
- 2026-08-02 · EXTEND · Built the shared-step library (§64), M1.5's other half. New: `SharedStep`
  entity over V1's table (`usage_count` deliberately NOT mapped), `SharedStepRepository`/`Service`/
  `Controller`/`View`/`Edit`/`Query`/`WriteRequest`/`Usage`, `GET` + `POST
  /projects/{id}/shared-steps`. **`usageCount` is DERIVED** — distinct live cases, two queries
  zipped and ranked in the service, because a denormalized counter must be recomputed by every path
  that rewrites a step list and a case edit replaces the list wholesale. **`library:manage` got its
  first consumer in Java.** Also a FIX: `CaseEdit.validate` gained `acceptableSharedStepIds` —
  `sharedStepId` had been persisted unchecked since M1, so a sibling project's id satisfied the FK
  and was stored while an unknown id was a 500. The rule is a UNION (live in project ∪ already
  referenced by this case), and `CaseEdit.of` passes the stored half ALONE so a bulk action over a
  case using a retired entry still reconstructs. Live pass found the emptiness check reporting "add
  at least one step" about a step that was submitted and rejected — predates the slice, swept across
  all three refusals. Fixture prefix `ab`. **No migration.** Mutation-verified twice.
- 2026-08-02 · EXTEND · Built `POST /cases/bulk` (§62), all eight declared actions — the last
  MSW-only surface with a live consumer. New: `BulkAction`/`BulkCommand` (sealed)/`BulkOperations`
  (pure parse + permission)/`BulkVerdict`/`BulkResultView`/`CaseBulkService`, `CaseRenderer`
  (extracted from two byte-identical private copies), `CaseEdit.of`, `TestCase` mutators for
  lifecycle/automation/owner/suite, `CaseTag.of` + `CaseCondition.of` (first writers of both tables),
  batch queries on `TestCaseRepository`/`CaseConditionRepository`, `ConditionRepository.findLiveInProject`.
  **`case:approve` got its first consumer in Java** and the gate is resolved from the parsed command;
  `SeedDataTest` gains the computed check behind that. **No migration.** Mutation-verified twice.
  §19's fixture-collision defect hit a fifth time (the `b0` prefix space was the board tests').
- 2026-08-02 · EXTEND · Built the artifact register in `documentation` (§61): `Artifact` entity over
  V1's table, `ArtifactRepository`/`Service`/`WriteService`/`Controller`, `ArtifactPeople` (the 4th
  `*People` display-name resolver) and `PlanFacts` (the 4th `*Facts` cross-module native-SQL reader,
  here into `execution.test_plan`). **Widened `documentation`'s allowedDependencies to include
  `identity`** and corrected its package comment, which claimed nothing in the module renders a
  person. **Touched the authorization core**: `V19` adds `PLAN_SCOPE` to `role_permission.qualifier`;
  `PermissionQualifier.strongest` is no longer an ordinal comparison and THROWS on the
  `OWN`/`PLAN_SCOPE` pair (they restrict different axes, so their union is inexpressible); new
  `ProjectAccess.canDoOnScope` + `ProjectService.writeGrantedByRowScope` + `project.RowScopeGate`
  mirror the authorship pair. New computed check in `SeedDataTest` keeps the throw unreachable.
  Mutation-verified. `ModuleStructureTest`'s hard-coded rationale updated.
- 2026-08-02 · FIX · Truncated the `Clock` bean to microseconds, closing §58's carried debt and
  re-enabling the two `@Disabled` invitation tests. Swept the class: `ExecutionService.record` and
  `SessionTokens.issue` both bypassed the bean with `Instant.now()` and now take it. Added
  `ClockResolutionTest` and `ArchitectureRulesTest.nothingCallsNowDirectly` (verified red by
  reintroducing the defect). Wrote the missing §59 and the new §60 handoff sections.

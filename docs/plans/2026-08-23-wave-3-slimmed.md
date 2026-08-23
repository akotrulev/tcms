# Wave 3, slimmed — 509 queued rows to 113

> **STATUS: CLOSED, 2026-08-23.** Batch A ran in full — its **41 gap rows**, 36 confirmed, 4
> withdrawn, 1 caught as already-measured. **Batches B, C, D and E did not run** and their 72 rows
> are `CLOSED:NOT-RUN` in the manifest, at the user's direction: no further mutation time is spent
> on this file. `CLOSED:NOT-RUN` is **not a measurement** — see the banner at the top of
> `docs/verification/backend-mutation-checks.md`.
>
> **One number in §1 below is now wrong and is left standing on purpose.** The claim that a green
> prediction is close to a coin flip rested on waves 1+2 withdrawing ten registered gaps against
> seven confirmed. Batch A's 41 rows withdrew **four** — a 10% rate, not ~59%. The register is more
> honest than the argument that ranked this class first assumed. The full record is in
> `RUN RECORD — 2026-08-23, wave 3 batch A`.

**Status: proposed, not run — superseded by the banner above.** This is the wave-3 re-decision the run plan
(`docs/plans/2026-08-22-backend-mutation-backlog-run-plan.md` §9, decision 4) deferred until waves 1,
S and 2 were measured. All three are now landed, so the decision is due.

The run plan's wave 3 was *"everything remaining in the backend lane, section by section"* — **~330**
by its estimate, **509** by the manifest, which is authoritative. This document selects **113** of
those 509 and states the rule that admitted each one. **Nothing is deleted.** The other 396 keep
their ids, their rows and their `queued` status in `mutation-manifest.tsv`; they are simply not in
the committed set. A later wave 3b can take them without re-deriving anything.

---

## 1. Why slim at all, in numbers rather than appetite

203 rows have now been adjudicated across waves 1, S and 2:

| Outcome | Rows |
|---|---|
| MATCH — reddened exactly what the row predicted | 95 |
| MORE | 44 |
| ZERO-AS-PREDICTED | 24 |
| **ZERO-UNEXPECTED — a finding** | **19** |
| FEWER — a finding | 9 |
| STALE / ERROR / lane / not-a-measurement | 12 |

Two rates matter, and they are very different:

- **A row predicting a specific red is right about 83% of the time.** 28 of 167 such rows surprised
  us (19 ZERO-UNEXPECTED + 9 FEWER). Running 400 more of them buys roughly 60–70 findings at ~51 s
  of Gradle each plus the adjudication, which is the expensive half.
- **A row predicting GREEN is wrong far more often.** Waves 1, S and 2 between them **withdrew ten
  registered gaps** — five in wave 1, five in wave 2 — against seven that measurement confirmed. The
  queue's own belief about what it cannot see is close to a coin flip, and every withdrawal removes
  a false entry from a register other work reads as fact.

That asymmetry is the whole argument. **The cheapest finding in this pass is a green prediction that
comes back red**, and the queue has 41 of those left unrun. They are the spine of the slimmed set.

Measured cost, for planning: **192 timed runs, 51 s mean, 2.7 h of wall clock** for all 203. 113 rows
is therefore **~1.6 h of runner time on shanks** and — by the run plan's own ratio — two to three
sessions of authoring and adjudication, against eight to ten for the full 509.

---

## 2. The admission rules

A row is in the slimmed set if **and only if** it matches one of these, on its own text — not on its
section, not on a hunch:

| Rule | Admits | Rows | Why this rule and not "read them all" |
|---|---|---|---|
| **gap** | The row predicts GREEN / "expected green" / "should redden nothing" / "recorded as a known gap" | **41** | The highest surprise rate in the pass (10 withdrawn vs 7 confirmed). Each run either deletes a false register entry or converts a belief into a measurement. |
| **blind** | The row says it does not know — *"the only observer"*, *"confirm whether"*, *"check when running"*, *"worth measuring rather than assuming"*, *"has no observer"* | **38** | The author had the code in front of them and still could not tell. That is the definition of a row a reading cannot replace. |
| **sec** | The consequence is cross-company, RLS, a permission gate, a cap bypass, or a leak | **16** | A ZERO here is not a test-suite defect, it is an unguarded tenancy boundary. §0.1 is the invariant everything rests on. |
| **loss** | The consequence is silent destruction — clobbered columns, an overwritten draft, bytes deleted before the row, a discarded committed edit | **15** | Unrecoverable in production and invisible in a green build. |
| **silent** | A `sec` or `loss` row that also states the failure is silent — *"no error anywhere"*, *"silently"*, *"invisible"* | **8** | Not an independent admit; it ranks the two above. Loud failures need no mutation to find. |
| **probe** | Two rows from each **hot** section that no other rule reached | **14** | A section that already produced a ZERO-UNEXPECTED or a FEWER has demonstrated blind spots. Sending two probes in is cheaper than either running all of it or writing it off. |
| **D2/D4/D5/D6/D9** | The remaining representatives of the run plan §5 collapse families | **8** | §5 collapsed 113 rows into 42; wave 2 applied D1, D3 and D8 only. These are the leftover keeps of the five families nobody has run. |

**What this deliberately drops: the base-rate row.** ~360 of the 396 excluded rows are well-written,
name one test, predict one red, and describe ordinary behaviour in a section whose measured siblings
came back clean. Their expected yield is exactly the 17% base rate above and no better, and they are
the rows the run plan itself said would be re-decided rather than assumed.

**Also excluded:** the seven Hetzner migration-in-place rows (wave 7 — they need a restored dump and
are independent of everything here).

---

## 3. Hot sections — where the measured evidence already points

21 sections that still hold queued rows have **already produced a finding**. Ranked by findings, then
by residue:

| Section | Run | Findings | Still queued | In the slimmed set |
|---|---|---|---|---|
| `§126 phase 2` | 6 | **3** (3 ZERO-UNEXPECTED) | 6 | 2 (probe) |
| `§192` | 4 | **3** (2 ZERO-UNEXPECTED, 1 FEWER) | 3 | 2 (probe) |
| `§242 amendment (2026-08-18)` | 6 | **2** | 7 | 2 |
| `§237` | 2 | 1 | 18 | 3 |
| `§247` | 5 | 1 | 17 | 1 |
| `§129 phase 1` | 2 | 1 | 16 | 1 |
| `§240` | 5 | 1 | 15 | 5 |
| `§131` | 3 | 1 | 11 | 1 |
| `§126 phase 3` | 2 | 1 | 10 | 2 |
| `§126 phase 1` | 7 | 1 | 9 | 1 |
| `§249` | 1 | 1 | 8 | 3 |
| `§121`, `§218 phase 2` | 3 / 2 | 1 each | 7 each | 1 / 3 |
| `§133`, `§236` | 3 / 2 | 1 each | 6 each | 2 / 3 |
| `§136`, `§179` | 3 / 6 | 1 each | 4 each | 1 / 2 |
| `§117`, `§157` | 1 / 2 | 1 each | 3 each | 2 / 2 |
| `§158`, `§235` | 2 / 1 | 1 each | 2 each | 2 / 2 |

**`§237` and `§247` are the two to watch.** Both have a measured finding and the largest untested
residue in the file (18 and 17). The slimmed set sends 3 and 1; if either comes back with another
ZERO-UNEXPECTED, running the rest of that section is the obvious wave 3b.

**`§127` and `§243` have never had a row run at all** — 14 and 11 queued, no evidence in either
direction. The slimmed set takes 1 and 3 on the row rules alone. There is no probe rule for them
because a section with no measurement is not yet *hot*; it is unknown, and guessing at it is what the
base-rate exclusion is for.

---

## 4. The set

59 sections, 113 rows. `**derive**` in the scope column means the manifest carries `?` — the class is
a section property and must be derived once for the section, per run plan §6's note on the 57
sections that name nothing.

**One row needs adjudication before it is run:** `§133 AG-9` reads as an assertion to *write* ("issue
an `Agent` token owned by a Viewer, then `POST` a case with it") rather than a mutation to apply. It
may belong in `RETIRED:NOT-A-MEASUREMENT` beside the other two. Decide when the section is opened.

### §246 — 7 rows

| id | scope | why | mutation |
|---|---|---|---|
| `§246 CE-M1` | ExportCompanyTest | sec | Move `CompanyContext.open(company.getId())` out of `ExportCompanyCommand.run` and make it the first line of `ExportCompanyService.export` |
| `§246 CE-M10` | **derive** | gap | Write `manifest.json` **first** instead of last |
| `§246 CE-M11` | ExportCompanyTest | gap · blind · sec | Delete the `WHERE t.company_id = ?` predicate from the walked-table statement, leaving only the RLS policy |
| `§246 CE-M13` | **derive** | gap | Remove the second `requireGucMatches` call (S2b, at the end of the walk) |
| `§246 CE-M2` | **derive** | gap · blind | Drop `isolation = Isolation.REPEATABLE_READ` from `ExportCompanyService.export` |
| `§246 CE-M8` | ExportCompanyTest | blind | In `ExportCompanyCommand.report`, `return EXIT_OK` unconditionally |
| `§246 CE-M9` | **derive** | gap | Rewrite `harvestBlobKeys` to list `<root>/<companyId>/` through `BlobInventory` instead of reading the three columns |

### §240 — 5 rows

| id | scope | why | mutation |
|---|---|---|---|
| `§240 RF-M2` | **derive** | gap · blind | In `ReportHeaders.of`, pass `generatedAt` where `watermark` is expected |
| `§241 OC-1b` | **derive** | gap | The same mutation, watching **only** the two §8.3 agreement arms |
| `§241 OC-3` | **derive** | gap · sec | In the generalised project-role query, delete `AND ra.company_id = :companyId` from both union arms |
| `§241 OC-7` | **derive** | gap | In `IdentityProjectAccess`, point `effectiveProjectRoleNames` at a private copy of the override instead of `EffectiveRoleNames` |
| `§241 OC-8` | **derive** | gap · blind | In `OrgChartService.companyRolesByPerson`, filter on `role.assignable` instead of on `Role.COMPANY_MEMBER_ID` |

### §128 — 4 rows

| id | scope | why | mutation |
|---|---|---|---|
| `§128 CD-13` | CaseDraftEndpointTest | gap · blind | `MAX_CONTENT_BYTES` is measured in `String.length()` rather than UTF-8 bytes |
| `§128 CD-14` | CaseDraftEndpointTest | loss | `TestCaseDraft.overwrite` merges the incoming content instead of replacing it |
| `§128 CD-2` | CaseDraftEndpointTest | loss | The `deleteForCase` call is moved to the **top** of `apply`, before validation |
| `§128 CD-6` | CaseDraftEndpointTest | loss · silent | The `lockVersion == null` check is replaced with a default of `0` |

### §138 — 4 rows

| id | scope | why | mutation |
|---|---|---|---|
| `AP-10` | AiProposalEndpointTest | blind | The credential branch moves **after** the proposal lookup |
| `AP-12` | AiProposalEndpointTest | blind | The synthetic `Idempotency-Key` passed to the create path is replaced with `null` |
| `AP-2` | AiProposalEndpointTest | blind | `V43`'s `verdict_source` CHECK gains `'TCMS_VERIFIED'` |
| `AP-9` | AiProposalEndpointTest | sec | `AiProposalDecisionService.decide` drops the `TokenContext.currentOrNull() != null` branch |

### §245 — 4 rows

| id | scope | why | mutation |
|---|---|---|---|
| `§245 BA-M10` | ExecutionBulkAssignTest | gap · blind | Move the `run.isClosed()` check **after** `validate` in `apply` |
| `§245 BA-M11` | ArchitectureRulesTest | D5 | Delete the `AuditEvent.bulkOperationId` writer — make the eight-argument `record` overload ignore its last parameter |
| `§245 BA-M6` | ExecutionBulkAssignTest | loss | Delete the `Objects.equals(execution.getAssigneeId(), to)` branch, so an unchanged row is written and audited anyway |
| `§245 BA-M7` | ExecutionBulkAssignTest | blind | Remove `@Transactional` from `ExecutionBulkAssignService.assign` |

### §131 phase 2 — 3 rows

| id | scope | why | mutation |
|---|---|---|---|
| `OB-16` | ExecutionEndpointTest | gap · blind | `IngestionService` records the event unconditionally instead of on `!recorded.isEmpty()` |
| `OB-17` | ExecutionEndpointTest | blind | `reporting.FactRefreshOnResultsChanged` calls `rollups.refresh()` before `refresh.refresh()` |
| `OB-19` | ExecutionEndpointTest | blind | Either handler's `@ConditionalOnProperty` is removed |

### §132 phase 1 — 3 rows

| id | scope | why | mutation |
|---|---|---|---|
| `TA-2` | IngestionEndpointTest | sec | `AuditEvent.record` sets `actorUserId = null` when a token is present |
| `TA-3` | IngestionEndpointTest | sec · silent | `CompanyContextTaskDecorator` clears the company but not the token (its state before this slice) |
| `TA-6` | IngestionEndpointTest | blind | `ApiTokenAuthenticationFilter` opens the token scope without try-with-resources |

### §181 — 3 rows

| id | scope | why | mutation |
|---|---|---|---|
| `§181 OS-M1` | **derive** | blind | In `OwnerStanding`, drop the `c.owner_id IS NOT NULL` conjunct |
| `§181 OS-M4` | **derive** | sec | In `OwnerStanding`, delete `AND m.company_id = c.company_id` from the membership join |
| `§181 OS-M6` | CaseHealthRecomputeTest | loss | In `CaseHealthRecomputeTest.signalsOf`, remove `"incomplete"` from the column list |

### §218 phase 2 — 3 rows

| id | scope | why | mutation |
|---|---|---|---|
| `§218 NC-2` | **derive** | gap · sec | In `NotificationChannelController.read`, change the `GateResult.Denied` arm to `ResponseEntity.notFound().build()` |
| `§218 NC-3` | NotificationChannelTest | loss · silent | In `NotificationChannelService.update`, delete the `expectedVersion != null && …` comparison, keeping the `@Version` backstop |
| `§218 NC-6` | **derive** | gap · blind | Add `parameters: [{ $ref: '#/components/parameters/IdempotencyKey' }]` to `createNotificationChannel` |

### §227 phase 1 — 3 rows

| id | scope | why | mutation |
|---|---|---|---|
| `§227 PM-3` | **derive** | gap | In `ReportJobView.modelUrl`, key on `job.getStatus() == COMPLETED` instead of on `getModelArtifactId() == null` |
| `§227 PM-7` | **derive** | gap | In `ReportJobRunner.runPendingJobs`, fold the reaped count back into the loop bound (`int run = failStuckJobs();`) |
| `§227 PM-8` | **derive** | gap | In `ReportingProperties`, delete the non-positive `stuckJobTimeout` check |

### §236 — 3 rows

| id | scope | why | mutation |
|---|---|---|---|
| `§236 DK-3` | ExecutionEndpointTest | gap | In `DefectLinkRepository.findByExecutionIdAndProviderAndExternalKey`, drop `AndProvider` from the name |
| `§236 DK-5` | ExecutionEndpointTest | gap | In `DefectLinkService.write`, move the `run.isClosed()` check *below* the validation and the lookup |
| `§236 SE-1` | ExecutionEndpointTest | gap | In `EvidenceService.attach`, replace the `stepOutcomes.findByExecutionIdAndStepId` empty branch with `stepResultId = null` (attach to the execution instead of refusing) |

### §237 — 3 rows

| id | scope | why | mutation |
|---|---|---|---|
| `§237 QR-2` | AggregateVariabilityTest | blind | In `AggregateExecutionReport.metadata`, change the headline entry to `share(totals.trustedPassed(), totals.trustedExecutions())` — i.e. make the single printed rate the excluded one |
| `§237 QR-7` | **derive** | gap · blind | In `ExecutionSource.quarantinedCases`, add `AND c.deleted_at IS NULL` to the `test_case` join |
| `§238 SV-4` | **derive** | gap · blind | In `Suite.applyCompositionVersion`, add `this.updatedAt = now` (and take a `now`) |

### §243 amendment (2026-08-21) — 3 rows

| id | scope | why | mutation |
|---|---|---|---|
| `§243 RN-M3` | ReviewNotificationTest | blind | In `ReviewService.moved`, move the `outbox.record` call out of the `to == APPROVED` branch so it also fires on the rejection/withdrawal arm |
| `§243 RN-M5` | ReviewNotificationTest | sec | In each notifier, mint `UUID.randomUUID()` for the `dispatch` event id instead of passing `payload.eventId()` |
| `§243 RN-M6` | ReviewRenderingTest | blind | In `ReviewSubjects.describe`, return `Optional.empty()` unconditionally |

### §249 — 3 rows

| id | scope | why | mutation |
|---|---|---|---|
| `§249 CS-M4` | **derive** | sec · silent | In `CrossProjectSearchService`, return `visible` instead of the intersection when `request.projectIds()` is non-empty |
| `§249 CS-M6` | **derive** | gap · blind | Remove the `IllegalArgumentException` from `CaseScope`'s compact constructor |
| `§249 CS-M7` | **derive** | gap | In `CrossProjectSearchService.hit`, resolve `projectKey`/`projectName` from a fresh `ProjectRepository.findById` instead of the gated map |

### §117 — 2 rows

| id | scope | why | mutation |
|---|---|---|---|
| `IN-12` | IngestionEndpointTest | probe | `IngestionService.write` records a result on a case whose execution is absent from a named run |
| `IN-9` | IngestionEndpointTest | probe | `IngestionEdit.MAX_REPORT_BYTES` removed |

### §120 — 2 rows

| id | scope | why | mutation |
|---|---|---|---|
| `DR-4` | CaseHealthRecomputeTest | D9 | The mirror arm drops `AND last_ci.at >= :cutoff` (unbounded, as the first draft of the design had it) |
| `DR-6` | CaseHealthRecomputeTest | gap | `HealthSignal.UNDECLARED_AUTOMATION` is declared **before** `ORPHANED` instead of appended |

### §121 phase 4 — 2 rows

| id | scope | why | mutation |
|---|---|---|---|
| `FQ-21` | CaseDetailEndpointTest | D4 | `CaseRenderer.flakesFor` returns `Map.of()` for a case with no row, and `TestCaseDetailView.of` receives `CaseFlakeView.of(CaseFlake.unscored(...))` instead of null |
| `FQ-24` | CaseDetailEndpointTest | gap | `CaseBulkService.loadContext` passes `Map.of()` for `flakes` regardless of `authored` |

### §122 — 2 rows

| id | scope | why | mutation |
|---|---|---|---|
| `GA-1` | GrantInstanceAdminServiceTest | gap | `grant-instance-admin` is removed from `OperatorCommands`' verb set, leaving the `ApplicationRunner` in place |
| `GA-7` | GrantInstanceAdminServiceTest | sec · silent | The service opens a `CompanyContext` around the grant — e.g. the granting operator's own company |

### §126 phase 2 — 2 rows

| id | scope | why | mutation |
|---|---|---|---|
| `DA-6` | DocPageAttachmentEndpointTest | probe | `write` skips `pages.findLiveInProject` and trusts the project gate |
| `DA-9` | DocPageAttachmentEndpointTest | probe | `DocPageAttachmentView` drops `uploadedBy` (or `CaseAttachmentView` gains a field this one lacks) |

### §126 phase 3 — 2 rows

| id | scope | why | mutation |
|---|---|---|---|
| `FA-10` | ArtifactFileEndpointTest | loss | `delete` calls `removeFile` **before** `artifacts.delete` |
| `FA-8` | ArtifactFileEndpointTest | loss · silent | `removeFile` returns early for every kind (the whole body deleted) |

### §132 phase 2 — 2 rows

| id | scope | why | mutation |
|---|---|---|---|
| `TA-10` | IngestionEndpointTest | gap · blind · loss · silent | `record` merges rather than overwrites — it leaves an existing `executedByToken` standing on an amendment |
| `TA-11` | IngestionEndpointTest | sec | `ApiTokenRepository.findAllByIdIn` becomes `findAllById` |

### §133 — 2 rows

| id | scope | why | mutation |
|---|---|---|---|
| `§133 AG-4` | SeedDataTest | blind | `RoleGrantService.revoke`'s `orElse(false)` becomes `orElse(true)` |
| `§133 AG-9` | IngestionEndpointTest | blind | The intersection is asserted end-to-end: issue an `Agent` token owned by a **Viewer**, then `POST` a case with it |

### §157 — 2 rows

| id | scope | why | mutation |
|---|---|---|---|
| `SA-2` | CaseHealthRecomputeTest | probe | `max(r.subject_version_no)` → `min(...)`, or an `ORDER BY r.approved_at DESC LIMIT 1` |
| `SA-3` | CaseHealthRecomputeTest | probe | Move `c.lifecycle_status = 'APPROVED'` out of the `SELECT` expression and into the `WHERE` |

### §158 — 2 rows

| id | scope | why | mutation |
|---|---|---|---|
| `§158 CD-4` | CaseHealthRecomputeTest | blind | Drop the `postconditions` half of the ` |
| `§158 CD-6` | CaseHealthRecomputeTest | D9 | Replace the content comparison with the timestamp one |

### §167 — 2 rows

| id | scope | why | mutation |
|---|---|---|---|
| `§167 DL-4` | **derive** | gap | In `HealthRecomputeOnLinksChanged`, delete the class (or gate it behind a property that defaults off) |
| `§167 DL-5` | **derive** | loss | In `CaseRenderer.docLinksFor`, change `computeIfAbsent(...).add(...)` to overwrite (`put`) instead of accumulate |

### §179 — 2 rows

| id | scope | why | mutation |
|---|---|---|---|
| `§180 TL-M2` | **derive** | probe | In `DashboardService.segments`, restore the `"var(--color-" + status.name().toLowerCase() + ")"` argument and the matching `SegmentView` component |
| `§180 TL-M4` | CaseBulkEndpointTest | probe | In `LifecycleStatus`, change `APPROVED`'s label to `"approved"` |

### §192 — 2 rows

| id | scope | why | mutation |
|---|---|---|---|
| `§192 DF-2` | IntegrationTest | probe | In the same method, **swap the two arms** — check `declinable` first and `continue`, then `retained` |
| `§192 DF-6` | IntegrationTest | probe | In `CaseUpdateService`, resolve `retained` from `testCase.getSuiteId()` unconditionally, dropping the `request.suiteId()` branch |

### §209 — 2 rows

| id | scope | why | mutation |
|---|---|---|---|
| `§209 AM-5` | **derive** | gap | In `applySupersession`, delete the `artifacts.flush()` |
| `§210 DQ-6` | PermissionEvaluatorTest | sec | In `PermissionEvaluatorTest.theQualifierIsOverriddenToo`, delete the `qualify(...)` call and its `finally` |

### §228 — 2 rows

| id | scope | why | mutation |
|---|---|---|---|
| `§227 PM-11` | ReviewGateTest | gap | In `CaseStatusService.apply`, delete the `reviews.refuses(...)` arm and go straight to `move(...)` |
| `§227 PM-12` | **derive** | gap | In `CaseStatusService.move`, delete the `reviews.moved(...)` call |

### §235 — 2 rows

| id | scope | why | mutation |
|---|---|---|---|
| `§235 PM-2` | **derive** | probe | In `DocPageService#search`, drop the re-ordering and return the entity read's own order |
| `§235 PM-3` | **derive** | probe | Change `ts_headline`'s source from `body.text` to `p.title` |

### §242 amendment (2026-08-18) — 2 rows

| id | scope | why | mutation |
|---|---|---|---|
| `§242 LG-M11` | RepositoryTransactionTest | D6 · probe | Remove `@Transactional(readOnly = true)` from `AuditEventRepository` |
| `§242 LG-M4` | **derive** | probe | In `AuditLogService.forCompany`, replace the `evaluateCompanyWide(userId).has("audit:view")` gate with `true` |

### 11. §98's flattening — 1 row

| id | scope | why | mutation |
|---|---|---|---|
| `11d` | **derive** | blind | `ExecutionSource` — change `('s' |

### 12. §99's run scope — 1 row

| id | scope | why | mutation |
|---|---|---|---|
| `12b` | **derive** | D2 | Same method — drop `AND c.deleted_at IS NULL` |

### 13. §101's M2 rollups — 1 row

| id | scope | why | mutation |
|---|---|---|---|
| `13h` | **derive** | D2 | Same statement — drop `AND r.deleted_at IS NULL` from the `test_run` join |

### 14. §106's board drag — 1 row

| id | scope | why | mutation |
|---|---|---|---|
| `14b` | WorkItemEndpointTest | blind | *(backend, unrun)* `WorkItemService`'s move — accept a status outside `allowedTransitions` |

### 15. R6 phase 1's aggregate report — 1 row

| id | scope | why | mutation |
|---|---|---|---|
| `R6-5` | AggregateExecutionCrossCheckTest | gap | `LATEST_PER_CONFIG`'s partition drops `configuration_id` |

### 19. §115's coverage matrix — 1 row

| id | scope | why | mutation |
|---|---|---|---|
| `CM-7` | CoverageMatrixReportTest | D2 | `findPage` drops `AND p.deleted_at IS NULL` |

### 20. §116's API tokens — 1 row

| id | scope | why | mutation |
|---|---|---|---|
| `AT-2` | ApiTokenEndpointTest | sec | `cappedToToken`'s intersection becomes a **union** (`held` ∪ role grant) |

### §121 — 1 row

| id | scope | why | mutation |
|---|---|---|---|
| `FQ-11` | FlakeScoreTest | loss | `CaseQuarantineRequest.quarantined` becomes a primitive `boolean` |

### §124 — 1 row

| id | scope | why | mutation |
|---|---|---|---|
| `AD-5` | PlanStatisticsEndpointTest | gap | `auditRemoval` is moved to *after* `artifacts.delete(artifact)` |

### §126 phase 1 — 1 row

| id | scope | why | mutation |
|---|---|---|---|
| `CA-8` | CaseAttachmentEndpointTest | loss · silent | `BlobUpload.key` uses the sanitized filename as its last segment instead of a fresh UUID |

### §127 — 1 row

| id | scope | why | mutation |
|---|---|---|---|
| `DB-13` | DocPageEndpointTest | loss | The guard is moved **below** `store.delete(row.getStorageKey())` |

### §129 phase 1 — 1 row

| id | scope | why | mutation |
|---|---|---|---|
| `CL-1` | CaseCloneEndpointTest | loss | `copyAttachments` reuses the source's `storageKey` instead of allocating a fresh one — the literal reading of §6v's "by reference, not byte copy" |

### §129 phase 2 — 1 row

| id | scope | why | mutation |
|---|---|---|---|
| `CL-23` | PlanCloneEndpointTest | gap | `PlanCloneService.derive` puts the source's criteria in the `PlanUpdateRequest` instead of passing the JSON to `write` |

### §131 — 1 row

| id | scope | why | mutation |
|---|---|---|---|
| `OB-13` | OutboxDispatchTest | blind · sec | `dispatchDue` drops the company check on the claimed message |

### §132 phase 3 — 1 row

| id | scope | why | mutation |
|---|---|---|---|
| `TA-13` | ApiTokenAuthenticationTest | blind | The bump moves to `ApiTokenAuthenticationFilter`, in its own transaction |

### §136 — 1 row

| id | scope | why | mutation |
|---|---|---|---|
| `IK-7` | SuiteWriteEndpointTest | blind | `DocPageWriteService.writeSubjectPage` passes a key instead of null |

### §141 — 1 row

| id | scope | why | mutation |
|---|---|---|---|
| `§141 RL-4` | TokenRateLimiterTest | blind | `SecurityConfiguration` moves `rateLimitFilter` to `.addFilterAfter(…, CompanyContextFilter.class)` |

### §155 — 1 row

| id | scope | why | mutation |
|---|---|---|---|
| `LV-6` | ArchitectureRulesTest | gap | `IngestionService` passes the token owner's id instead of `null` as `actorId` to `startIfNotStarted` |

### §159 — 1 row

| id | scope | why | mutation |
|---|---|---|---|
| `AR-M3` | ProjectEndpointTest | blind | In the controller's switch, answer `Unreachable` with 403 instead of 404 |

### §160 — 1 row

| id | scope | why | mutation |
|---|---|---|---|
| `RR-M6` | **derive** | blind | In `IngestionJobView.reportUrl`, return the URL unconditionally instead of null for a null key |

### §178 — 1 row

| id | scope | why | mutation |
|---|---|---|---|
| `§178 SL-M3` | CaseHealthRecomputeTest | blind | In `SpecLinkage`, change `bool_or(l.stale)` to `bool_or(l.stale AND l.relationship = 'VERIFIES')` |

### §187 — 1 row

| id | scope | why | mutation |
|---|---|---|---|
| `§187 DC-5` | CaseBulkEndpointTest | blind | In `CaseBulkService.loadContext`, pass `List.of()` instead of `declinesByCase.getOrDefault(...)` to `CaseEdit.of` |

### §200 — 1 row

| id | scope | why | mutation |
|---|---|---|---|
| `§200 PR-1` | **derive** | blind | In `CaseCreateService`, pass `CaseEdit.ProcedureRule.REQUIRED` instead of `DEFERRED` |

### §217 — 1 row

| id | scope | why | mutation |
|---|---|---|---|
| `§217 FD-6` | SchemaConventionTest | gap | Change `company_email_domain.domain` from `CITEXT` back to the plan's `TEXT` |

### §225 — 1 row

| id | scope | why | mutation |
|---|---|---|---|
| `§225 PM-2` | **derive** | gap · sec | In `PermissionEvaluator.MemoKey`, drop the `token` component |

### §229 phase 1 — 1 row

| id | scope | why | mutation |
|---|---|---|---|
| `§229 PM-13` | ExecutionEndpointTest | gap | In `PlanService`, restore the private `configurationsOf` body (the repository call and the `ConfigurationView` mapping) instead of delegating |

### §229 phase 2 — 1 row

| id | scope | why | mutation |
|---|---|---|---|
| `§229 PM-16` | ArtifactEndpointTest | loss | In `ArtifactWriteService.edit`, delete the `artifact.isWriteOnce()` refusal |

### §247 — 1 row

| id | scope | why | mutation |
|---|---|---|---|
| `§247 SK-M22` | CronScheduleTest | gap | Truncate `nameFor`'s composed run name from the **back** instead of the front — or not at all |
---

## 5. Running it

Same harness as waves 1, S and 2 — the manifest is authoritative and the `wave` column carries `W3`
for exactly these 113 ids. Order the batches so the evidence compounds:

| Batch | Content | Rows | Why here |
|---|---|---|---|
| **A** | Every `gap` row | 41 | The highest-yield class in the file, and each one is a single run with no fixture owed. If the withdrawal rate holds, this batch alone rewrites a third of the `DOCUMENTED GAPS` register. |
| **B** | Every `sec` / `loss` / `silent` row | 31 | The consequence half. Run second so a finding here lands against a register batch A has already corrected. |
| **C** | The remaining `blind` rows | 25 | Slower per row — several need their scope derived first. |
| **D** | The 14 hot-section probes | 14 | Last, because each one's result decides whether its section's residue is promoted to a wave 3b. |
| **E** | The five collapse representatives | 8 | D2, D4, D5, D6, D9 — the families wave 2 did not reach. |

**A `gap` row that comes back RED is a register correction, not just a result.** Withdraw the entry
in `DOCUMENTED GAPS` in the same commit that records the measurement, the way waves 1 and 2 did —
otherwise the file keeps asserting a hole that has been proved not to exist.

**Stopping is legitimate after any batch.** A, B and C are 97 of the 113 and are independent of each
other; D and E are the ones whose value depends on what the earlier batches found.

## 6. What is explicitly not committed

396 rows stay `queued` with no wave tag. That is a **deliberate, recorded deferral**, not a silent
truncation — the distinction the queue's own §65 policy exists to protect. If wave 3's findings rate
comes in materially above the 17% base rate, the argument for a wave 3b over the residue gets
stronger, and the manifest can produce that set the same way this one was produced.

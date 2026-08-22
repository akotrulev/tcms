# Company Data Export Implementation Plan

**Date:** 2026-08-22 · **Written by:** `ep-writing-plans`

> **For agentic workers:** REQUIRED SUB-SKILL: Use `ep-executing-plans` to implement this plan
> task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Take one company's data out of this instance as a single archive, so that offboarding a
client and answering a GDPR request are operator commands rather than a hand-written pile of `psql`
`\copy` invocations. Companies are provisioned by a script and today have **no way out**.

**Architecture:** A fourth **operator CLI command** — `export-company --slug=acme --out=…` — beside
`provision-company`, `issue-invitation` and `grant-instance-admin`. Not an HTTP endpoint: the act is
an operator's, it is long-running, it writes a file to the box's filesystem, and it must be possible
with no browser and no session. The walk is **catalogue-driven** — the table list comes from
`pg_policies` and `information_schema` at run time, never from a list in Java — so a table added by a
later slice is exported the day it exists, and a table nobody classified fails the build.

**Tech Stack:** Spring Boot 3.5.6 / Java 21 / Postgres 17 · `java.util.zip` and Jackson, both
already on the classpath · **no contract, no migration, no frontend file, no new dependency.**

---

## Area E of `docs/plans/2026-08-18-unplanned-gaps.md`

The master plan scoped this area as **tasks 33–40** and required this document before work starts
(*"When you pick up area N, your first act is to run `ep-writing-plans` on that area alone"*). Read
the master's Area E section first — it names the three traps, and all three are settled below rather
than left to implementation.

| Master task | Where it lands here |
|---|---|
| 33 — read the operator-command precedent | Task 1 (already read; the findings are in *What already exists*) |
| 34 — decide the redaction rule and write it down first | **Task 2**, and the whole *Redaction* section below |
| 35 — write the failing export test | Tasks 3 and 6 |
| 36 — one `CompanyContext`, one read-only transaction | **Task 5**, and the whole *The walk* section below |
| 37 — include the blobs | Tasks 8–9 |
| 38 — register the command and gate | Tasks 10–11 |
| 39 — the runbook note | Task 12 |
| 40 — close the area | Tasks 13–14 |

### E is the one area in the plan that may run beside another

The master plan's parallelism table: E holds **no** `openapi.yaml`, **no** Flyway version, the
`provisioning` module which nothing else in the plan touches, and **no** frontend file. `A ∥ E`,
`B ∥ E`, `C ∥ E`, `D ∥ E`, `E ∥ F` and `E ∥ G` are all marked eligible, and `E ∥ G` is the only pair
G has. Two consequences bind this document:

- **The fixture UUID prefix is pre-assigned: `7c`.** Verified unused 2026-08-22. Every seeded id in
  every test below starts `7c…`. Do not choose another; the prefix register is hand-managed with no
  check behind it and has collided five times (`docs/specs/parallel-work-rules.md:26`).
- **The close is the conflict.** Every area ends by editing `docs/agent-context/be-java.md`. Two
  areas' closes must not run at once. Task 14 is the one to stagger.

### Four deviations from the master plan, and why

1. **The table list is derived from the live catalogue, not written in Java.** The master plan says
   *"It reads every table"*. A `List.of(...)` of 78 table names is the shape ERD §0.1 has already
   paid for twice: reading `V1` top to bottom missed five join tables, and scripting the comparison
   found them. `SchemaConventionTest` exists because the invariant needs **computing**. The export
   is the same invariant one direction over — *is every table accounted for* — so it computes it,
   and `CompanyExportTablesTest` fails the build when a table is neither walked nor excluded for a
   written reason. **This is also the whole of what Area C costs us**: when `V68` adds
   `run_schedule` with a `company_isolation` policy, it is exported with **no code change at all**,
   because it arrives in the walked partition by construction.

2. **`ExportCompanyResult` carries an outcome, and there is a fourth exit code.** The master plan
   names the record but not its shape. A missing blob — a row naming bytes that are not on disk — is
   neither a success nor a failure: the archive is still the best available copy of the client's
   data and refusing to write it helps nobody, but exiting `0` would tell an offboarding script that
   a complete copy exists. So `EXIT_INCOMPLETE = 3`, alongside the `0/1/2` all three sibling
   commands use, and the gaps are enumerated in the manifest and on stdout.

3. **The archive is written to a temporary file and renamed on success.** Not in the master plan and
   not optional: the entire point of the area is that a *silently short* archive is worse than a
   failure. A file at the operator's `--out` path exists only if the walk finished, the sentinels
   passed and the manifest was written — which makes "the archive is there" a true signal instead of
   a hopeful one.

4. **`sso_login_attempt`, `social_login_attempt`, `user_social_identity`, `api_token_lookup` and
   `instance_setting` are excluded outright, not redacted column by column.** Each holds credential
   material or instance infrastructure and **nothing a client could want**; four of them are empty in
   steady state by construction. Exporting a row with every column blanked is worse than not
   exporting it — it invites the reader to believe something was lost.

---

## What already exists — do not rebuild it

| Piece | Where | What it gives this area |
|---|---|---|
| `OperatorCommands` | `provisioning/OperatorCommands.java:22-30` | The verb set. `ALL` is package-private *so the test derives its cases from it*; adding a fourth constant + one `Set.of` entry is the whole registration. |
| `TcmsApplication.main` | `TcmsApplication.java:36-38` | Reads `OperatorCommands.isRequestedBy(args)` **before the context exists** and switches to `WebApplicationType.NONE`. Miss this and the command works and then never exits. |
| `ProvisionCompanyCommand` | `provisioning/ProvisionCompanyCommand.java:97-125` | The runner shape: inert unless its verb is a non-option argument, parse → service → report → `exit`, usage text to `System.out`, `SpringApplication.exit` so the pool drains. |
| `GrantInstanceAdminCommand` | `provisioning/GrantInstanceAdminCommand.java:111-150` | The `switch` over a result outcome that yields the exit code — the shape Task 10 copies for `EXIT_INCOMPLETE`. |
| `CompanyContext.open` / `require` | `tenancy/CompanyContext.java:61-69,117` | The scope that always restores, and the loud read. |
| `CompanyAwareTransactionManager` | `tenancy/CompanyAwareTransactionManager.java:53` and `doBegin` | Sets `app.current_company` **at transaction begin, from whatever `CompanyContext` holds then**. This is why the context is opened by the *caller*, before the transactional method is entered. |
| `CompanyRepository` | `tenancy/CompanyRepository.java` | Resolve the slug with **no context open** — `company` is instance-level and carries no RLS. |
| `AttachmentStore` / `ReportStore` / `RawReportStore` | `storage/` | `Optional<StoredBlob> open(String key)`; `StoredBlob(InputStream, long)` and **the caller closes it**. Three ports because three roots with different retention. |
| `BlobUpload.key` | `storage/BlobUpload.java:61-63` | `companyId + "/" + ownerId + "/" + randomUUID` — every evidence and raw-report key. |
| `ReportJobRunner.storageKey` | `reporting/ReportJobRunner.java:422-424` | `companyId + "/" + jobId + "/" + randomUUID + "." + ext` — every report key. **Both are company-prefixed**, which is what makes the archive's blob paths collision-free with no invention. |
| `SchemaConventionTest` | `tenancy/SchemaConventionTest.java:47-124` | The precedent for Tasks 3 and 4 exactly: an exception list with a reason per entry, swept against the live catalogue, failing with a message that says what to do. |
| `IntegrationTestBase.superuserConnection()` | `IntegrationTestBase.java:71` | **The ground truth the export cannot have.** The suite's superuser bypasses RLS, so a test can count what the export *should* have found. Nothing under test connects as it. |

---

## Decision 1 — the walk: one context, one transaction, one snapshot

The failure this section exists to prevent: **a table read outside `CompanyContext` returns zero
rows under RLS.** No exception, no log line, no failing query — `company_id = NULL` is simply never
true (`V1__initial_schema.sql:1421-1424`). An export that loses context between two tables produces
an archive that opens, parses and is missing everything after the point it was lost, and the
operator who ran it has a file that looks exactly like a good one.

### The four rules, and the reason each is not the obvious code

**1. The context is opened by the command, around the call — never inside the service method.**

```java
// ExportCompanyCommand — instance-level work first, with NO context open
Company company = companies.findBySlug(slug).orElseThrow(...);   // `company` carries no RLS

try (var ignored = CompanyContext.open(company.getId())) {
    result = exportService.export(company, out);                 // @Transactional starts INSIDE
}
```

`CompanyAwareTransactionManager` reads `CompanyContext` in `doBegin` and issues
`set_config('app.current_company', ?, true)` for that transaction. A `CompanyContext.open()` written
*inside* an `@Transactional` method runs **after** `doBegin` has already decided there was no
company — the GUC is unset for the life of that transaction and every RLS read returns nothing. The
proxy boundary is the trap and it is invisible in a stack trace.

**2. The whole walk is one `@Transactional(readOnly = true, isolation = REPEATABLE_READ)` method.**

`readOnly` because nothing here writes, and because it lets Postgres and Hibernate skip dirty
checking over a walk that touches every row a company owns. `REPEATABLE_READ` because the default
`READ COMMITTED` takes a **new snapshot per statement**: a run finishing halfway through the walk
yields a `step_result` whose `test_execution` was not in the archive, and a referential-integrity
error in the importer months later is the last place anybody wants to discover that. One snapshot
across ~70 tables is the whole point of doing this in one transaction rather than one per table.

**3. Every walked statement spells `WHERE company_id = :companyId` in addition to the policy.**

Not redundancy — belt-and-braces at the one moment we most want it. It also makes the same code
correct for the four tables that carry `company_id` as a **target** and have no policy at all
(`membership_invitation`, `api_token_lookup`, `sso_login_attempt`, `instance_audit_event`), so the
walk has one form rather than two. `company` itself is filtered on `id`.

**4. The archive is written to `<out>.partial` and `ATOMIC_MOVE`d onto `<out>` as the last act.**

The manifest is the **last entry written into the zip**, so even a killed process cannot leave a
manifest-bearing archive; and the rename means a path that exists is a path that finished.

### How a short archive fails loudly

There is no in-band ground truth for an RLS-protected table. The counterfactual count — *"how many
rows should there have been?"* — is itself an RLS-filtered query, so with the context lost it also
answers zero, and comparing the two proves nothing. That is not a gap in the design; it is the
reason the checks below are the shape they are.

| # | Check | Catches |
|---|---|---|
| **S1** | `CompanyContext.require()` at method entry, asserted equal to the company being exported | The service called with no scope, or the wrong one |
| **S2** | `SELECT current_setting('app.current_company', true)` read as the **first** and again as the **last** statement of the transaction, both asserted equal to the company id | A walk that spanned two transactions — `SET LOCAL` does not survive one, so the second read comes back NULL. This is the single check that catches the exact defect the master plan names |
| **S3** | **The seeded-vocabulary sentinel.** `company_membership`, `role_assignment`, `key_sequence`, `case_status` and `classification_dimension` are non-empty for **every** provisioned company — `CompanyProvisioningServiceTest`'s completeness claim is that a provisioned company can classify a case, allocate a display key and record an execution status. A zero count in any of the five aborts the export | Context lost before the walk, a policy misconfigured, an export pointed at a half-provisioned company |
| **S4** | Per-table row counts and a SHA-256 per entry in `manifest.json`, written last | A truncated or hand-edited archive, months later, by a reader who was not there |
| **S5** | *(test only)* every walked table's archived count compared against a **superuser** count taken through `IntegrationTestBase.superuserConnection()`, which bypasses RLS | The real thing: any table the export under-reads for any reason at all |
| **S6** | *(test only)* `theExportRefusesWithNoCompanyContext` — call the service outside a scope and assert it **throws**, and that no file exists at `--out` | An "improvement" that moves the `CompanyContext.open` inside the transaction, i.e. the exact regression this section is about |

S3 is the one that carries the load in production; S5 is the one that carries it in CI. **Neither is
optional and the sentinel list is not a formality** — it is the same argument ERD §0.1 makes about
its own schema test.

---

## Decision 2 — the blobs

Evidence, report artifacts and raw CI payloads are **files on a filesystem**, not rows in Postgres
(`storage/package-info.java`: *"they do not roll back with the transaction that named them"*). A
table-only walk produces a manifest naming bytes it did not include — a `attachment` row with a
`storage_key`, a `size_bytes` and no file anywhere in the archive.

### What is collected, and from where

| Root | Property | Named by | Key minted at |
|---|---|---|---|
| evidence | `tcms.storage.evidence.root` | `attachment.storage_key` | `BlobUpload.key` — `<companyId>/<ownerId>/<uuid>` |
| reports | `tcms.storage.reports.root` | `report_artifact.storage_key` | `ReportJobRunner.storageKey` — `<companyId>/<jobId>/<uuid>.<ext>` |
| raw-reports | `tcms.storage.raw-reports.root` | `ingestion_job.raw_storage_key` (**nullable**) | `BlobUpload.key` |

**The rows are the authority, not the directory.** The keys come out of the walked table dumps, in
the same transaction and the same snapshot — so the blob set and the row set describe the same
instant. Listing `<root>/<companyId>/` and archiving whatever is there would be a different and
worse thing: it sweeps orphans left by failed writes, it depends on a key layout that is
`BlobUpload`'s business and not this command's, and it would silently start exporting somebody
else's bytes the day that layout changed.

**Archive paths are the storage key, verbatim, under a per-root prefix.** Keys are already
company-prefixed UUID triples, so they are unique, ASCII, traversal-free and need no invention:

```
blobs/evidence/<companyId>/<ownerId>/<uuid>
blobs/reports/<companyId>/<jobId>/<uuid>.pdf
blobs/raw-reports/<companyId>/<jobId>/<uuid>
```

The **client's own filename is not used as a path** and this is deliberate: `attachment.filename` is
client-supplied, `BlobUpload.sanitizeFilename` only takes the last segment, two screenshots on one
execution are both called `screenshot.png`, and a zip entry name is one of the classic traversal
sinks. The filename is already in `tables/attachment.jsonl` next to the key, which is where a
reader reconstructs it from — and `README.txt` in the archive says so in one sentence.

**Bytes are streamed** — `Files.copy(blob.stream(), zip)` inside the try-with-resources
`StoredBlob` closes; a company with 4 GB of evidence must not need 4 GB of heap.

### The two reconciliations, both recorded in the manifest

- **`missingBlobs`** — a walked row whose key opens to nothing. Each is listed as
  `{table, id, storageKey}`, printed on stdout, and the command exits **3**. This is the archive
  telling the truth about itself rather than a silent short copy.
- **`orphanBlobs`** — files under `<root>/<companyId>/` that **no walked row names**. Counted and
  listed in the manifest, **not archived**: they are unreferenced bytes whose provenance nothing can
  state, and an archive that includes them claims they are the client's data. A non-zero count is
  worth an operator's attention (`ReportJobRunner` writes bytes first and the row second, so a
  crashed job leaves exactly this), which is why it is in the manifest and not swallowed.

### `company.logo_storage_key` — the one open question left for the implementer

`company.logo_storage_key TEXT NULL` exists (`V1__initial_schema.sql:35`, §6u branding) and the
column is walked like any other. **Whether anything writes it yet, and which of the three roots it
would live under, has not been established by this plan.** Task 8 step 4 is to grep for a writer:
if there is one, the key joins the evidence collection; if there is not, the manifest records
`"logoBlob": "no writer in this build"` and the reader is told rather than left to wonder. Do not
guess a root — a wrong one is the exact "silently under the report root" failure
`storage/package-info.java` keeps two port types to prevent.

---

## Decision 3 — the redaction list

**Decided here, before any code, and enumerated by name.** A redaction decided during
implementation is a redaction that gets one table wrong.

The archive leaves this instance. It goes to a client, to a data-protection officer, into a ticket
attachment, onto somebody's laptop. **Nothing in it may be a live credential or a credential
digest.**

### Dropped columns — the table is exported, the column is not

| Table | Column | Since | Why it must not travel |
|---|---|---|---|
| `app_user` | `password_hash` | `V1:81` | A bcrypt digest is an offline-cracking target, and these are people's real reused passwords |
| `api_token` | `token_hash` | `V1:337` | The digest an API token is checked against. `name`, `token_prefix`, `expires_at`, `revoked_at` are **kept** — an operator must be able to see *which* integrations existed |
| `membership_invitation` | `token_hash` | `V6:70` | An unconsumed invitation digest is a live admission to the company |
| `notification_channel` | `secret` | `V54:130` | AES-GCM ciphertext of the webhook URL, and **a webhook URL is the authentication for the room it posts to**. `secret_hint` is **kept** — its own comment says it is *"the only part of the credential that ever leaves this table"*, and it is what lets the client recognise which webhook was configured |
| `identity_provider` | `client_secret_encrypted` | `V53:37` | The OIDC confidential-client secret |
| `identity_provider` | `bind_password_encrypted` | `V57:31` | The LDAP service-account bind password |

Ciphertext is redacted on the same terms as a digest: the key is `tcms.integration.secret-key` /
`tcms.identity.provider-secret-key` from the environment, and *"encrypted with a key you do not
have"* is a property of today's holder, not of the archive's future ones.

### Excluded tables — no row at all

| Table | Why |
|---|---|
| `api_token_lookup` | The pre-auth digest index (`V36`). A digest, a token id and a company id — **nothing else**; every fact worth exporting is on the policed `api_token` row |
| `sso_login_attempt` | `state_hash`, the PKCE verifier and the nonce. Single-use, 10-minute expiry, **empty in steady state**, and nothing identifying is in it by construction (ERD §0.1) |
| `social_login_attempt` | The same one provider family over. Instance-level, no `company_id` at all — it cannot be attributed to this company even if we wanted it |
| `user_social_identity` | Instance-level by decision (`V58`): a person's GitHub identity spans every company they belong to, so it is **not this company's data to export** |
| `instance_setting` | Instance infrastructure. Not the client's, and a plausible place for an instance secret |
| `permission`, `role`, `role_permission` | Instance-wide reference data. The catalogue is in `docs/specs/2026-07-30-tcms-roles-permissions-naming.md`, which is a better artifact than a dump of it. `role_assignment` — *which* role this company's people hold — **is** exported, and it is the part that carries meaning |
| `instance_role_assignment` | SYSTEM-scope operator grants. Cross-company by definition |
| `instance_audit_event` | Operator actions **across clients**. It carries a nullable `company_id`, so a filtered export is technically possible and is still refused: the trail spans clients and correlating it is an operator's job, not an offboarded client's |
| `flyway_schema_history` | Infrastructure. The head version is in the manifest instead, which is the one fact a future importer needs |

### `app_user` is exported, member-scoped

`app_user` has no `company_id` — one identity may belong to several companies. The export includes
**only the rows this company's `company_membership` names**, and only `id`, `email`, `display_name`,
`status` and the timestamps. Without it the archive is a graph of UUIDs and every `created_by` is
unreadable, which fails the GDPR half of the goal outright.

### What is deliberately NOT redacted, and why the rule is a list rather than a pattern

`test_case_version.content_hash`, `suite_version.content_hash`, `test_plan_version.content_hash` and
`doc_page_version.content_hash` (`V1:577,745,761,978`) are **content addresses of the client's own
test assets**. A blanket "strip anything matching `%hash%`" rule would destroy the integrity check on
every version snapshot in the archive, which is the opposite of the goal. So the redaction list is
**enumerated**, and the *sweep that guards it* is the pattern — with an allowlist that has a written
reason per entry. That is Task 4, and it is the piece that fails the build when a later slice adds a
secret-bearing column.

---

## The archive

```
export-<slug>-<yyyyMMdd'T'HHmmss'Z'>.zip
├── manifest.json                  ← written LAST
├── README.txt
├── tables/
│   ├── app_user.jsonl
│   ├── attachment.jsonl
│   ├── company.jsonl
│   ├── test_case.jsonl
│   └── …                          ← one file per walked table, alphabetical
└── blobs/
    ├── evidence/<companyId>/<ownerId>/<uuid>
    ├── reports/<companyId>/<jobId>/<uuid>.<ext>
    └── raw-reports/<companyId>/<jobId>/<uuid>
```

**Zip, not tar.gz:** `java.util.zip` is in the JDK, so the command adds no dependency to a jar that
is also the server; entries are individually addressable, so a reader can pull one table without
inflating 4 GB; and `unzip` needs nothing installed on a client's machine. Entry order is
deterministic (tables alphabetical, then blobs by key) so two exports of an unchanged company differ
only in their timestamps — which makes them diffable, and a diff is how somebody proves an export is
complete.

**JSONL per table, not CSV:** NULL and empty string are different values and CSV cannot say so;
`JSONB` columns (`project_settings`, `plan_snapshot`, `ingestion_job.raw_payload`, `company.settings`)
nest; and a line-per-row file streams in and out with no parser state. Each row is produced by
Postgres itself:

```sql
SELECT to_jsonb(t) - :redactedColumns FROM <table> t WHERE t.company_id = :companyId ORDER BY <pk>
```

`to_jsonb(t)` means **no per-column Java mapping**, so a column added by a later migration is
exported the day it exists; `- text[]` is jsonb key deletion, so redaction is one bind parameter and
cannot be forgotten per-column; `ORDER BY` the primary key makes the output deterministic.

### `manifest.json`

```json
{
  "formatVersion": 1,
  "exportedAt": "2026-08-22T09:14:07Z",
  "schemaVersion": "67",
  "company": { "id": "…", "slug": "acme", "displayName": "Acme Corporation", "status": "ACTIVE" },
  "tables": [
    { "name": "test_case", "rows": 1842, "sha256": "…", "redactedColumns": [] },
    { "name": "api_token", "rows": 3,    "sha256": "…", "redactedColumns": ["token_hash"] }
  ],
  "excludedTables": [
    { "name": "sso_login_attempt", "reason": "CREDENTIAL_ONLY" },
    { "name": "permission",        "reason": "INSTANCE_REFERENCE" }
  ],
  "blobs": {
    "evidence":    { "files": 210, "bytes": 448203919 },
    "reports":     { "files": 12,  "bytes": 8402011 },
    "raw-reports": { "files": 64,  "bytes": 19204812 }
  },
  "missingBlobs": [ { "table": "attachment", "id": "…", "storageKey": "…" } ],
  "orphanBlobs":  [ { "root": "reports", "path": "…" } ],
  "sentinels":    { "company_membership": 14, "role_assignment": 21, "key_sequence": 6,
                    "case_status": 7, "classification_dimension": 4 },
  "complete": false
}
```

`"complete": false` is the machine-readable form of exit code 3, so a script that unzips the archive
reaches the same verdict as one that read the exit status. `schemaVersion` is the Flyway head — the
one thing a future importer must check before believing any of it.

---

## File structure

**Create — `backend/src/main/java/com/tcms/provisioning/`**

- `ExportCompanyCommand.java` — the `ApplicationRunner`. Parsing, reporting, exit codes. `provisioning`'s established split: **no decision lives here.**
- `ExportCompanyService.java` — the walk. One `@Transactional(readOnly = true, isolation = REPEATABLE_READ)` method, the sentinels, the zip.
- `ExportTables.java` — the catalogue partition: walked / excluded / unclassified, and the exclusion reasons. `SchemaConventionTest`'s exception-list shape, moved into main code because the export needs it at run time.
- `ExportRedactions.java` — the enumerated `table.column` list and the reasoned KEEP allowlist, as constants with a comment per entry.
- `ExportManifest.java` — the record the manifest serialises from, plus its nested `TableEntry`, `ExcludedTable`, `BlobSummary`, `MissingBlob`, `OrphanBlob`.
- `ExportCompanyResult.java` — `(companyId, slug, Path archive, long bytes, Map<String,Long> tableRows, int blobCount, List<MissingBlob> missing, List<OrphanBlob> orphans)` with `wasComplete()`.

**Modify**

- `backend/src/main/java/com/tcms/provisioning/OperatorCommands.java` — one constant, one `Set.of` entry.
- `docs/plans/2026-07-30-tcms-hetzner-deployment-runbook.md` — a section on running it against the live box.
- `docs/agent-context/be-java.md` · `docs/HANDOFF.md` (one index row) · `docs/verification/backend-mutation-checks.md`.

**Create — tests**

- `backend/src/test/java/com/tcms/provisioning/ExportTablesTest.java` — the totality sweep. No table may be unclassified.
- `backend/src/test/java/com/tcms/provisioning/ExportRedactionSweepTest.java` — the secret-column sweep against `information_schema.columns`.
- `backend/src/test/java/com/tcms/provisioning/ExportCompanyTest.java` — the export driven end to end against a seeded `7c…` company, with a second company B seeded alongside.
- `backend/src/test/java/com/tcms/provisioning/ExportCompanyCommandTest.java` — argument parsing, plain unit, the §150 lane (`ProvisionCompanyCommandTest`'s shape).

**Not touched, ever, by this area:** `docs/specs/openapi.yaml`, either `schema` copy, `npm run gen:api`, any file under `frontend/` or `packages/`.

---

## Phase 1 — the classification and the redaction list

**One commit.** The two computed checks, before anything exports anything. Written first because
both are *decisions*, and a decision made while the export is half-built is a decision made to fit
the code. At the end of this phase nothing exports, two new tests are green, and the answer to
"what travels and what does not" is in the repository.

### Task 1: Read the operator-command precedent

**Files:** read only —
`backend/src/main/java/com/tcms/provisioning/OperatorCommands.java`,
`ProvisionCompanyCommand.java`, `GrantInstanceAdminCommand.java`,
`backend/src/main/java/com/tcms/TcmsApplication.java:29-44`,
`backend/src/test/java/com/tcms/provisioning/OperatorCommandsTest.java`.

- [ ] **Step 1: Confirm the four properties this area must not break**

1. `OperatorCommands.ALL` is the **only** list of verbs; `TcmsApplication:36` asks it before the
   context exists, and `OperatorCommandsTest:33-45` derives every case from the set. A fourth verb
   is therefore covered by its tests the day it is added — **and only if it is added to `ALL`.**
   A verb registered as an `ApplicationRunner` but missing from `ALL` runs a servlet container, does
   the operator's work correctly and **never exits**.
2. `ALL` is package-private *"so `OperatorCommandsTest` can derive its cases from the set instead of
   listing the verbs a second time"* (`OperatorCommands.java:22-26`). Do not widen it.
3. Every runner is inert unless its verb is a **non-option** argument
   (`ProvisionCompanyCommand.java:99-101`), because `@SpringBootTest` executes `ApplicationRunner`
   beans — a runner that acted on context startup would run inside every integration test in the
   suite and call `System.exit` in the Gradle test JVM.
4. `exit(int)` goes through `SpringApplication.exit(context, …)` so shutdown hooks run and the
   connection pool drains (`ProvisionCompanyCommand.java:243-251`).

- [ ] **Step 2: Note the one thing that is different here**

All three existing commands **write**. This one only reads, and it is the first operator command
that needs a `CompanyContext` at all: `provision-company` runs with **no** company context
(`CompanyContext.clear()`'s documented "correct state for instance-level operator work"). That
inversion is the whole of Decision 1 and it is why the export cannot be modelled on
`CompanyProvisioningService`'s transaction handling.

### Task 2: Write the redaction decision down, in code, before it can be lost

**Files:**
- Create: `backend/src/main/java/com/tcms/provisioning/ExportRedactions.java`

- [ ] **Step 1: The dropped columns, one constant, a reason each**

```java
package com.tcms.provisioning;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What must not leave this instance inside an export archive.
 *
 * <p><strong>Enumerated, never pattern-matched.</strong> Four {@code content_hash} columns are
 * content addresses of the client's own version snapshots, and a blanket "strip anything matching
 * %hash%" rule would destroy the integrity check on every snapshot in the archive — the opposite of
 * what an export is for. So the list is written out and {@code ExportRedactionSweepTest} is the
 * pattern: it sweeps {@code information_schema.columns} and fails the build when a column whose
 * name suggests a credential is neither redacted here, nor in an excluded table, nor on {@link
 * #DELIBERATELY_KEPT} with a reason.
 *
 * <p>The archive goes to a client, a data-protection officer, a ticket attachment. Ciphertext is
 * redacted on the same terms as a digest: "encrypted with a key you do not have" is a property of
 * today's holder, not of every future one.
 */
final class ExportRedactions {

    /** {@code table -> columns dropped from every exported row of it}. */
    static final Map<String, Set<String>> DROPPED_COLUMNS = Map.of(
            // A bcrypt digest of a password people reuse. V1:81.
            "app_user", Set.of("password_hash"),
            // The digest an API token is checked against. V1:337. `name`, `token_prefix`,
            // `expires_at` and `revoked_at` are KEPT — the client must be able to see which
            // integrations existed and when they were revoked.
            "api_token", Set.of("token_hash"),
            // An unconsumed invitation digest is a live admission to the company. V6:70.
            "membership_invitation", Set.of("token_hash"),
            // AES-GCM ciphertext of a webhook URL, and a webhook URL IS the authentication for the
            // room it posts to. V54:130. `secret_hint` is KEPT: its own column comment calls it
            // "the only part of the credential that ever leaves this table", and it is what lets a
            // client recognise WHICH webhook was configured.
            "notification_channel", Set.of("secret"),
            // The OIDC confidential-client secret (V53:37) and the LDAP bind password (V57:31).
            "identity_provider", Set.of("client_secret_encrypted", "bind_password_encrypted"));

    /**
     * Columns the sweep would flag that are exported deliberately. <strong>Every entry needs a
     * reason, and adding one is a decision</strong> — the same rule
     * {@code SchemaConventionTest.INSTANCE_LEVEL_TABLES} carries.
     */
    static final Map<String, String> DELIBERATELY_KEPT = Map.ofEntries(
            Map.entry("test_case_version.content_hash", "content address of the client's own case"),
            Map.entry("suite_version.content_hash", "content address of the client's own suite"),
            Map.entry("test_plan_version.content_hash", "content address of the client's own plan"),
            Map.entry("doc_page_version.content_hash", "content address of the client's own page"),
            Map.entry("api_token.token_prefix",
                    "the displayed first characters — V36 added it precisely so a token can be "
                            + "named without being reproduced"),
            Map.entry("notification_channel.secret_hint",
                    "masked tail, designed to leave the table (V54's column comment)"),
            Map.entry("company.logo_storage_key", "a blob key, not a credential"),
            Map.entry("attachment.storage_key", "a blob key — it is how the archive's bytes are found"),
            Map.entry("report_artifact.storage_key", "a blob key"),
            Map.entry("ingestion_job.raw_storage_key", "a blob key"),
            Map.entry("ingestion_job.idempotency_key", "the CI caller's own de-duplication key"),
            Map.entry("report_job.idempotency_key", "the caller's own de-duplication key"),
            Map.entry("permission.key", "a permission name; the table is excluded anyway"),
            Map.entry("project.key", "the client's own project key — it is in every URL they own"));

    /** The column-name shapes {@code ExportRedactionSweepTest} treats as suspect. */
    static final List<String> SUSPECT_NAME_FRAGMENTS = List.of(
            "hash", "secret", "password", "token", "credential", "encrypted",
            "nonce", "verifier", "salt", "cipher", "private_key");

    private ExportRedactions() {
    }

    static Set<String> droppedFrom(String table) {
        return DROPPED_COLUMNS.getOrDefault(table, Set.of());
    }
}
```

> **Check the KEEP list against the live catalogue while writing it.** Several entries above were
> derived from migrations and one or two column names (`report_job.idempotency_key`, `V66`) should
> be confirmed rather than trusted — the sweep in Task 4 will tell you within one run, and an entry
> naming a column that does not exist is itself a finding.

### Task 3: Classify every table, from the catalogue

**Files:**
- Create: `backend/src/main/java/com/tcms/provisioning/ExportTables.java`

- [ ] **Step 1: The partition**

Three groups, decided at run time from the live database:

```java
/**
 * Which tables an export walks, which it deliberately leaves out, and the refusal when a table is
 * neither.
 *
 * <p><strong>Derived from the catalogue, never listed in Java.</strong> ERD §0.1 paid for this
 * lesson twice: reading V1 top to bottom missed five join tables and four version tables, and
 * scripting the comparison found them. A hard-coded list of 78 names is a list that is wrong the
 * first time somebody adds a table and right-looking forever after. The consequence worth stating:
 * when area C's {@code V68} adds {@code run_schedule} with a {@code company_isolation} policy, it is
 * exported <em>with no change to this class</em>.
 */
final class ExportTables {

    /** Walked with {@code WHERE company_id = ?} — every table under a company_isolation policy. */
    List<String> walked();

    /** Not exported, each with a reason that goes into the manifest. */
    static final Map<String, Exclusion> EXCLUDED = …;

    enum Exclusion { INSTANCE_REFERENCE, CREDENTIAL_ONLY, INSTANCE_INFRASTRUCTURE, CROSS_COMPANY }
}
```

The three partitions, and how each is computed:

| Partition | Computed as | Filter |
|---|---|---|
| **RLS-walked** | `pg_policies` where `policyname = 'company_isolation'` | `WHERE company_id = ?` (belt-and-braces beside the policy) |
| **Target-filtered** | carries `company_id` (`information_schema.columns`) but has **no** policy: `membership_invitation`, `api_token_lookup`, `sso_login_attempt`, `instance_audit_event` — plus `company` itself, filtered on `id` | explicit `WHERE company_id = ?`; three of the five are then **excluded** by name, see the table in *Decision 3* |
| **Instance-level, no `company_id`** | the remainder | `app_user` is walked **member-scoped**; everything else is excluded with a reason |

- [ ] **Step 2: `app_user`, the one special case, written as one**

```java
// app_user has no company_id — one identity belongs to several companies (ERD §0.1). Exported
// member-scoped, because without it the archive is a graph of UUIDs and every created_by is
// unreadable, which fails the GDPR half of the goal outright.
static final String APP_USER_SQL = """
        SELECT to_jsonb(u) - 'password_hash' FROM app_user u
         WHERE u.id IN (SELECT m.user_id FROM company_membership m WHERE m.company_id = ?)
         ORDER BY u.id""";
```

Note that the inner `company_membership` read is **itself RLS-protected**, so this statement is one
more thing that returns nothing outside the context — which is why S3's sentinel counts
`company_membership` and not something incidental.

- [ ] **Step 3: The refusal**

```java
if (!unclassified.isEmpty()) {
    throw new IllegalStateException(
            "These tables are neither walked nor excluded, so an export would silently omit them: "
            + unclassified + ". Classify each in ExportTables — a company-owned table needs a "
            + "company_isolation policy, and an instance-level one needs an Exclusion with a "
            + "reason. This is the same refusal SchemaConventionTest makes one direction over.");
}
```

**It throws at export time, not only in a test.** A deployment running a build older than its
database is exactly the case where a test cannot help.

### Task 4: The two computed checks

**Files:**
- Create: `backend/src/test/java/com/tcms/provisioning/ExportTablesTest.java`
- Create: `backend/src/test/java/com/tcms/provisioning/ExportRedactionSweepTest.java`

- [ ] **Step 1: `ExportTablesTest` — nothing is unclassified**

```java
/**
 * Every table is walked or excluded — the export's half of ERD §0.1's argument.
 *
 * <p>{@code SchemaConventionTest} asks "does every table carry company_id"; this asks "does the
 * export know what to do with every table". They fail on the same commit for the same new table and
 * they are not the same check: a table can carry company_id, satisfy that test, and still be missing
 * from an archive because nothing walks it.
 */
class ExportTablesTest extends IntegrationTestBase {

    @Test
    void everyTableIsEitherWalkedOrExcludedForAReason() {
        // allTables() from information_schema, the SchemaConventionTest:98 shape, over the
        // superuser connection. Assert walked ∪ excluded == all, and that the two do not overlap.
    }

    @Test
    void everyRlsProtectedTableIsWalked() {
        // pg_policies where policyname = 'company_isolation'. A company-owned table that is
        // excluded is a client's data left behind, which is the failure that has no symptom.
    }

    @Test
    void everyExclusionNamesATableThatExists() {
        // The V1 DO-block's own rule (V1:1453) — a rename must not silently drop a table's
        // classification into a name nothing matches.
    }
}
```

- [ ] **Step 2: `ExportRedactionSweepTest` — the check the master plan asked for**

> **Master task 34's acceptance, in one test:** *"a test that fails if a new secret-bearing column
> appears unredacted."*

```java
/**
 * Sweeps every column in the schema for a name that suggests a credential, and fails unless each is
 * accounted for. THE point: it fails for a column that does not exist yet.
 */
class ExportRedactionSweepTest extends IntegrationTestBase {

    @Test
    void everySuspectColumnIsRedactedExcludedOrDeliberatelyKept() {
        // For each information_schema.columns row whose column_name contains any
        // ExportRedactions.SUSPECT_NAME_FRAGMENTS fragment:
        //   accounted for iff  dropped by ExportRedactions
        //                  ||  its table is in ExportTables.EXCLUDED
        //                  ||  "table.column" is in DELIBERATELY_KEPT
        // The failure message must name the column AND the three ways out, because the person
        // reading it is somebody who just added a column and has never read this file.
    }

    @Test
    void nothingIsKeptOrDroppedForAColumnThatIsGone() {
        // Both directions. A DELIBERATELY_KEPT entry naming a dropped column is a stale exemption,
        // and a stale exemption is how the NEXT column of that name slips through unnoticed.
    }

    @Test
    void thePasswordHashAndTheFiveOtherKnownSecretsAreOnTheDroppedList() {
        // The six from the plan, asserted literally. This is the arm that fails if somebody
        // "simplifies" DROPPED_COLUMNS while the sweep still passes because the table got excluded.
    }
}
```

- [ ] **Step 3: Run them and watch the first one fail**

```bash
~/bin/tcms-remote backend ./gradlew test --tests 'com.tcms.provisioning.Export*Test'
```

Expected: `ExportTablesTest` fails until `ExportTables` classifies all ~78 tables, and the sweep
fails on any column the list above missed. **Tests run on `shanks`, not locally** — standing
directive. Both failures are the deliverable of this phase: they are the two lists, checked.

- [ ] **Step 4: Gate and commit**

```bash
~/bin/tcms-remote backend ./gradlew build
git add backend/src/main/java/com/tcms/provisioning/Export{Redactions,Tables}.java \
        backend/src/test/java/com/tcms/provisioning/Export{Tables,RedactionSweep}Test.java
git commit -m "feat: what an export carries, and what it must never carry"
```

Record the measured test count in the commit body — it is the number Task 13's handoff cites.

---

## Phase 2 — the walk

**One commit.** The service, the sentinels, the tables. No blobs yet, and the commit message says
so: an archive produced at this commit is complete on rows and empty on bytes, which is a state the
manifest already describes truthfully (`blobs.evidence.files: 0`).

### Task 5: Write the failing export test first

**Files:**
- Create: `backend/src/test/java/com/tcms/provisioning/ExportCompanyTest.java`

- [ ] **Step 1: Seed two companies on the `7c` prefix**

```java
/**
 * The export, driven against a real company with a second company alongside it.
 *
 * <p>Per the async-harness lesson (handoff §14) every test class shares one container and therefore
 * one database, so this class owns the {@code exp-} slug namespace and the {@code 7c} UUID prefix
 * (pre-assigned 2026-08-22, area E of the unplanned-gaps plan). It must not assume it is alone.
 */
@DisplayName("export-company — a complete archive, and nothing of anybody else's")
class ExportCompanyTest extends IntegrationTestBase {

    private static final UUID COMPANY_A = UUID.fromString("7c000000-0000-0000-0000-000000000001");
    private static final UUID COMPANY_B = UUID.fromString("7c000000-0000-0000-0000-000000000002");
    private static final UUID CASE_A    = UUID.fromString("7c000000-0000-0000-0000-000000000101");
    private static final UUID TOKEN_A   = UUID.fromString("7c000000-0000-0000-0000-000000000201");
```

Seed A through `CompanyProvisioningService` (so the vocabularies and key counters are real, which
S3's sentinel depends on), then add, inside `CompanyContext.open(COMPANY_A)`: a project, a suite, a
case with a version, a run with an execution, **an `api_token`**, **a `notification_channel` with a
secret**, and — in Phase 3 — an `attachment` with real bytes. Seed B the same way with
deliberately similar-looking data, which is `RlsIsolationTest`'s paired-fixture discipline.

- [ ] **Step 2: The six arms**

```java
    @Test
    void everyWalkedTableMatchesADirectCountTakenAsSuperuser() {
        // S5, and the arm that matters most. For each table in ExportTables.walked():
        //   archived = lines in tables/<t>.jsonl
        //   truth    = SELECT count(*) FROM <t> WHERE company_id = COMPANY_A, over
        //              superuserConnection(), which bypasses RLS.
        // Assert equal, table by table, and report EVERY mismatch rather than the first —
        // one short table and forty short tables are different diagnoses.
    }

    @Test
    void companyBIsAbsentFromEveryLine() {
        // Not "no COMPANY_B rows in company_membership" — grep the WHOLE archive, every table
        // file, for COMPANY_B's uuid and for company B's slug and project key. The cross-company
        // assertion has to be over the bytes, because the interesting leak is a column nobody
        // thought to check.
    }

    @Test
    void noRedactedValueAppearsAnywhereInTheArchive() {
        // Read the six values back out of the database as superuser (the bcrypt digest, the token
        // digest, the invitation digest, the channel ciphertext …) and assert not one of them
        // occurs in any archive entry. Value-based, not key-based: a redaction that renamed the
        // key and kept the value would pass a key-based assertion.
    }

    @Test
    void theSecretHintSurvivesAndTheSecretDoesNot() {
        // Both halves of the notification_channel decision on one channel, so that a later
        // "redact the whole table" simplification reddens instead of quietly losing the hint.
    }

    @Test
    void theExportRefusesWithNoCompanyContext() {
        // S6. Call the service OUTSIDE CompanyContext.open and assert it throws, and that no file
        // exists at the --out path. This is the regression guard for the one defect the master
        // plan names: a walk outside the context returns zero rows from every RLS table and
        // produces an archive that opens fine and is empty.
    }

    @Test
    void aCompanyWithNoSeededVocabulariesAbortsRatherThanExporting() {
        // S3 from the other side: insert a bare `company` row as superuser — no membership, no
        // key_sequence, no case_status — and assert the export ABORTS naming the sentinel table,
        // and leaves no file. A half-provisioned company and a lost context look identical from
        // inside the walk, and refusing both is correct.
    }
}
```

- [ ] **Step 3: Run it and watch it fail**

```bash
~/bin/tcms-remote backend ./gradlew test --tests 'com.tcms.provisioning.ExportCompanyTest'
```

Expected: FAIL — `ExportCompanyService` does not exist, so it will not compile.

### Task 6: Write the service — the walk itself

**Files:**
- Create: `backend/src/main/java/com/tcms/provisioning/ExportCompanyService.java`
- Create: `backend/src/main/java/com/tcms/provisioning/ExportManifest.java`
- Create: `backend/src/main/java/com/tcms/provisioning/ExportCompanyResult.java`

- [ ] **Step 1: The method signature, and the javadoc that carries the rule**

```java
/**
 * Walks one company's data into a single archive.
 *
 * <h2>The context is opened by the CALLER, and this is not a style preference</h2>
 *
 * <p>{@link com.tcms.tenancy.CompanyAwareTransactionManager} issues
 * {@code set_config('app.current_company', …)} in {@code doBegin}, reading whatever
 * {@link CompanyContext} holds <em>at that moment</em>. A {@code CompanyContext.open()} written
 * inside this method would run after the transaction had already begun with no company — the GUC
 * stays unset for its whole life, every RLS policy evaluates {@code company_id = NULL}, and the
 * walk reads <strong>zero rows from every company-owned table without raising anything</strong>.
 * The archive opens, parses, and is empty. That is the defect this whole class is shaped around,
 * and {@code ExportCompanyTest.theExportRefusesWithNoCompanyContext} is what keeps it fixed.
 *
 * <h2>One transaction, REPEATABLE READ</h2>
 *
 * <p>Not one transaction per table, and not the default READ COMMITTED. A run finishing partway
 * through a 78-table walk under READ COMMITTED yields a {@code step_result} whose
 * {@code test_execution} is not in the archive — a referential break discovered months later by
 * whoever imports it. One snapshot is the reason the walk is one method.
 */
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public ExportCompanyResult export(Company company, Path out) { … }
```

- [ ] **Step 2: The order of operations, exactly**

1. **S1** — `UUID active = CompanyContext.require();` and refuse unless it equals `company.getId()`.
2. **S2a** — `SELECT current_setting('app.current_company', true)`; refuse unless it equals the id.
3. Open `<out>.partial` and a `ZipOutputStream` over it.
4. `ExportTables.classify(connection)` → refuse on any unclassified table (Task 3 step 3).
5. **S3** — count the five sentinels. Any zero aborts, naming the table and both diagnoses
   ("either the company context is not open, or this company was never fully provisioned").
6. Walk the walked tables **alphabetically**, one `tables/<t>.jsonl` entry each, streaming
   `to_jsonb(t) - :dropped` rows; accumulate the row count and a `MessageDigest` per entry.
7. Collect the blob keys as the rows go past — Phase 3 fills this in; here it is an empty list and
   the manifest says `0`.
8. **S2b** — read `current_setting` again. Unequal or NULL ⇒ abort, delete `<out>.partial`.
9. Write `README.txt`, then `manifest.json` **last**.
10. Close the zip; `Files.move(partial, out, ATOMIC_MOVE, REPLACE_EXISTING)`.

- [ ] **Step 3: Every failure path deletes the partial**

One `try/catch (RuntimeException | IOException)` around 3–10 that deletes `<out>.partial` and
rethrows. **A half-written archive must not survive under any name an operator could mistake for a
good one**, and `.partial` on disk after a crash is a diagnosis, not a deliverable.

- [ ] **Step 4: How the rows are read**

`JdbcTemplate` (or the `EntityManager`'s connection via `doWork`) and **not** JPA entities. Three
reasons, and they are the same reason: `to_jsonb(t)` needs no mapping, so a column added by a later
migration is exported the day it lands; there is no entity for several walked tables
(`case_tag`, `suite_case`, `role_permission`-shaped joins); and streaming a `ResultSet` with a fetch
size beats materialising a company's entire `test_execution` history into the heap. Use the
`EntityManager`'s connection so the work is inside the same transaction the manager configured —
**a fresh `DataSource.getConnection()` would be a second connection with no GUC set**, which is
Decision 1's failure with a longer path to it.

- [ ] **Step 5: Run the test to green on its first four arms**

```bash
~/bin/tcms-remote backend ./gradlew test --tests 'com.tcms.provisioning.ExportCompanyTest'
```

Expected: the count, isolation, redaction and refusal arms pass. Blob arms do not exist yet.

### Task 7: Gate and commit the walk

- [ ] **Step 1: Full backend gate**

```bash
~/bin/tcms-remote backend ./gradlew build
```

- [ ] **Step 2: Commit**

```bash
git add backend/src/main/java/com/tcms/provisioning/ backend/src/test/java/com/tcms/provisioning/
git commit -m "feat: one company's rows, in one context and one snapshot"
```

---

## Phase 3 — the blobs

**One commit.** The half without which the manifest describes bytes the archive does not contain.

### Task 8: Collect the keys from the rows and stream the bytes

**Files:**
- Modify: `backend/src/main/java/com/tcms/provisioning/ExportCompanyService.java`
- Modify: `backend/src/main/java/com/tcms/provisioning/ExportManifest.java`

- [ ] **Step 1: Harvest the keys during the walk, not after it**

Three columns, gathered as their tables stream past in step 6 of Task 6:

| Root | Table | Column | Note |
|---|---|---|---|
| `evidence` | `attachment` | `storage_key` | Covers **all six owner kinds** — `CASE`, `STEP`, `EXECUTION`, `STEP_RESULT`, `DOC_PAGE`, `ARTIFACT` (`V1:679`). One table, one column, because `attachment` is the polymorphic parent |
| `reports` | `report_artifact` | `storage_key` | |
| `raw-reports` | `ingestion_job` | `raw_storage_key` | **Nullable** — `V1:1342`. A null is not a missing blob and must not be reported as one |

Same transaction, same snapshot as the rows: the blob set and the row set then describe one instant,
which is what makes `size_bytes` in the archive's `attachment.jsonl` agree with the file beside it.

- [ ] **Step 2: Write them through the three ports, streamed**

```java
for (BlobRef ref : refs) {
    try (Optional<StoredBlob> blob = store(ref.root()).open(ref.key())) {   // AttachmentStore /
        if (blob.isEmpty()) {                                              // ReportStore /
            missing.add(new MissingBlob(ref.table(), ref.rowId(), ref.key()));
            continue;                                                      // RawReportStore
        }
        zip.putNextEntry(new ZipEntry("blobs/" + ref.root() + "/" + ref.key()));
        Files.copy(blob.get().stream(), zip);       // streamed: a 4 GB company is not 4 GB of heap
        zip.closeEntry();
    }
}
```

The entry name is **the storage key verbatim** under a per-root prefix. The keys are already
`<companyId>/<ownerId>/<uuid>` (`BlobUpload.java:61-63`, `ReportJobRunner.java:422-424`), so they are
unique, ASCII and traversal-free, and a reader maps a row to its bytes with no manifest lookup.
`attachment.filename` — client-supplied, duplicated across executions, and one of the classic zip
traversal sinks — stays a **field in the table dump**, and `README.txt` says in one sentence that a
reader who wants original names joins on the key.

- [ ] **Step 3: The two reconciliations**

- `missingBlobs` — every row whose key opened to nothing, `{table, id, storageKey}`, in the manifest
  and on stdout, and the result is **incomplete** (exit 3, `"complete": false`).
- `orphanBlobs` — walk `<root>/<companyId>/` on each of the three roots and record any file **no
  walked row named**. Counted and listed, **not archived**: unreferenced bytes whose provenance
  nothing can state, and an archive that includes them claims they are the client's data.
  `ReportJobRunner` writes bytes first and the row second on purpose, so a crashed job leaves
  exactly this — the count is information, not an error.

- [ ] **Step 4: Settle `company.logo_storage_key` — the open question, closed here**

```bash
grep -rn "logo_storage_key\|logoStorageKey\|setLogo" backend/src/main/java/com/tcms/ | head
```

If a writer exists, add its root to the harvest in step 1 and an arm to the test. If none does,
put `"logoBlob": "no writer in this build"` in the manifest and say so in the handoff. **Do not
guess a root.** A wrong one writes a company logo under the reports root, silently — the exact
failure `storage/package-info.java` keeps `AttachmentStore` and `ReportStore` as two types to make
a compile error instead.

### Task 9: The blob arms of the test

**Files:**
- Modify: `backend/src/test/java/com/tcms/provisioning/ExportCompanyTest.java`

- [ ] **Step 1: Three arms**

```java
    @Test
    void aSeededEvidenceFilesBytesAreInTheArchive() {
        // Master task 37's acceptance, literally. Attach known bytes to an execution through the
        // real path (EvidenceService → AttachmentStore), then read the archive entry at
        // blobs/evidence/<key> and assert the bytes are EQUAL — not that the entry exists, and not
        // that the size matches. Equality is the only assertion that catches a stream copied from
        // the wrong key.
    }

    @Test
    void aRowNamingBytesThatAreGoneIsReportedRatherThanSkipped() {
        // Delete the file from under the store, keep the row. Assert: the archive still exists,
        // manifest.missingBlobs names that attachment id, "complete" is false, and the result
        // says INCOMPLETE. The archive is still the best available copy; what must not happen is
        // exit 0.
    }

    @Test
    void companyBsBytesAreNotInTheArchive() {
        // Seed evidence on company B too. Its key is under B's uuid prefix, so a directory-listing
        // implementation would still get this right by accident — assert on the ENTRY NAMES, which
        // is what fails if somebody ever changes the harvest to a filesystem sweep.
    }
```

- [ ] **Step 2: Gate and commit**

```bash
~/bin/tcms-remote backend ./gradlew build
git add backend/src/
git commit -m "feat: the export carries the bytes its rows name"
```

---

## Phase 4 — the command

**One commit.** The operator-facing half. Nothing here decides anything —
`provisioning`'s split is parsing and reporting only.

### Task 10: Write the runner

**Files:**
- Create: `backend/src/main/java/com/tcms/provisioning/ExportCompanyCommand.java`

- [ ] **Step 1: The class, mirroring its two siblings exactly**

```java
/**
 * The {@code export-company} CLI entry point.
 *
 * <pre>{@code
 * java -jar tcms.jar export-company --slug=acme --out=/var/backups/acme.zip
 * }</pre>
 *
 * <p>Parsing and reporting only — every decision lives in {@link ExportCompanyService}, the same
 * split {@link ProvisionCompanyCommand}, {@link IssueInvitationCommand} and
 * {@link GrantInstanceAdminCommand} use.
 *
 * <p>Inert unless asked: an {@link ApplicationRunner} runs on every boot, including under
 * {@code @SpringBootTest}. See {@link ProvisionCompanyCommand} for why the argument check is the
 * mechanism rather than a profile exclusion.
 *
 * <h2>Exit codes</h2>
 *
 * <ul>
 *   <li>{@code 0} — a complete archive is at {@code --out}</li>
 *   <li>{@code 3} — <strong>an archive is at {@code --out} and it is incomplete</strong>: one or
 *       more rows name bytes that are not on disk. Enumerated in {@code manifest.json} and on
 *       stdout. Distinct from 0 because an offboarding script must be able to tell a complete copy
 *       from the best available one, and distinct from 1 because there IS a file and it is worth
 *       keeping</li>
 *   <li>{@code 2} — usage error: a missing or malformed argument</li>
 *   <li>{@code 1} — nothing was written. The walk refused (no company context, a sentinel table
 *       empty, a table nothing classifies), the slug names no company, or the transaction failed.
 *       <strong>No file exists at {@code --out}</strong></li>
 * </ul>
 */
@Component
public class ExportCompanyCommand implements ApplicationRunner {

    static final String COMMAND = OperatorCommands.EXPORT_COMPANY;

    private static final int EXIT_OK = 0;
    private static final int EXIT_FAILED = 1;
    private static final int EXIT_USAGE = 2;
    private static final int EXIT_INCOMPLETE = 3;
```

- [ ] **Step 2: The usage text, in the register the siblings use**

```java
    private static final String USAGE = """
            Usage:
              export-company --slug=<slug> --out=<path to a .zip>

            Writes one company's entire dataset — every company-owned table, plus the evidence,
            report and raw-ingestion files those rows name — to a single zip archive. For
            offboarding a client and for answering a data-protection request.

            READ-ONLY. Nothing in the database is changed, and running it twice is safe.

            What the archive does NOT contain, deliberately: password hashes, API-token digests,
            invitation digests, notification-channel secrets and identity-provider client secrets
            and bind passwords. manifest.json lists every redaction and every table left out, with
            the reason. See the self-hosting runbook.

            The file appears at --out only if the export finished. A partial run leaves
            <out>.partial instead, which is a diagnosis rather than a deliverable.

            Exit 3 means an archive was written and something it references is missing from disk;
            manifest.json names each one. Exit 1 means no file was written at all.
            """;
```

- [ ] **Step 3: `run`, `parse`, `report`, `exit`**

`run` mirrors `ProvisionCompanyCommand:97-125` line for line: the non-option guard, `parse` into
`(slug, out)` with `required(args, …)` and the "given more than once" check, `System.out` for the
usage message (never the logger — *"an operator reading a usage message should not have to look past
a timestamp and a thread name"*), then:

```java
        try {
            Company company = companies.findBySlug(slug).orElse(null);   // NO context: instance-level
            if (company == null) {
                System.out.println("No company on this instance has the slug '" + slug + "'.");
                exit(EXIT_FAILED);
                return;
            }
            ExportCompanyResult result;
            try (var ignored = CompanyContext.open(company.getId())) {   // ← the whole of Decision 1
                result = exportService.export(company, Path.of(out));
            }
            exit(report(result));
        } catch (RuntimeException e) {
            log.error("Exporting '{}' failed; no archive was written and nothing was changed. "
                    + "Re-running after fixing the cause is safe.", slug, e);
            exit(EXIT_FAILED);
        }
```

- [ ] **Step 4: `report` — per part, the way `provision-company` reports**

Table count, total rows, blob count and bytes per root, the archive path and its size — then, when
incomplete, every missing blob by table and id, and the sentence that says the archive was still
written. `GrantInstanceAdminCommand:111-150`'s switch-yields-the-code shape:

```java
    private int report(ExportCompanyResult result) {
        System.out.println("Archive: " + result.archive() + " (" + result.bytes() + " bytes)");
        System.out.println("  " + result.tableRows().size() + " tables, "
                + result.totalRows() + " rows");
        System.out.println("  " + result.blobCount() + " files");
        if (result.orphans().isEmpty()) { … } else { … }
        if (result.wasComplete()) {
            return EXIT_OK;
        }
        System.out.println();
        System.out.println("INCOMPLETE — " + result.missing().size() + " row(s) name bytes that "
                + "are not on disk. The archive was written anyway and manifest.json lists each "
                + "one; this is the best available copy, not a complete one.");
        result.missing().forEach(m -> System.out.println("  " + m.table() + " " + m.id()));
        return EXIT_INCOMPLETE;
    }
```

- [ ] **Step 5: A pure unit test for the parsing**

`backend/src/test/java/com/tcms/provisioning/ExportCompanyCommandTest.java`, the §150 lane —
`ProvisionCompanyCommandTest`'s shape, no container: both arguments required, each rejected when
repeated, blank treated as missing, and `--out` accepted as both a relative and an absolute path.

### Task 11: Register the verb

**Files:**
- Modify: `backend/src/main/java/com/tcms/provisioning/OperatorCommands.java:22-30`

- [ ] **Step 1: The constant and the set entry — both, in one edit**

```java
    static final String EXPORT_COMPANY = "export-company";

    static final Set<String> ALL =
            Set.of(PROVISION_COMPANY, ISSUE_INVITATION, GRANT_INSTANCE_ADMIN, EXPORT_COMPANY);
```

**The set entry is the load-bearing half.** A runner registered without it starts a servlet
container, writes the archive correctly and never exits — `OperatorCommands`' own header calls this
out as *"the kind of defect that is found by an operator rather than by a test"*, which is exactly
why `ALL` exists and why `OperatorCommandsTest` derives from it.

- [ ] **Step 2: Confirm the existing tests cover the new verb without being edited**

```bash
~/bin/tcms-remote backend ./gradlew test --tests 'com.tcms.provisioning.OperatorCommandsTest' \
                                          --tests 'com.tcms.provisioning.CliContextStartsTest'
```

Expected: PASS, with `everyVerb` and `notAPrefixMatch` now covering four verbs from the same
assertions. **If either test had to be edited to accommodate the fourth verb, something was added in
the wrong place** — that property is the whole reason `ALL` is package-private.

- [ ] **Step 3: Gate and commit**

```bash
~/bin/tcms-remote backend ./gradlew build
git add backend/src/
git commit -m "feat: export-company, the fourth operator verb"
```

---

## Phase 5 — the record

**One commit.** The docs a later reader needs, and the §65 rows.

### Task 12: The runbook section

**Files:**
- Modify: `docs/plans/2026-07-30-tcms-hetzner-deployment-runbook.md`

- [ ] **Step 1: A new section after §7 (*Layout, config, secrets*)**

§7 is where the three storage roots and their ownership live, which is what this command reads.
Cover, in the runbook's own imperative voice:

```bash
# Export a company from the live box. Same image as the server; --rm because it exits.
docker compose -f compose.prod.yaml run --rm \
  -v /var/backups/tcms:/export \
  backend export-company --slug=acme --out=/export/acme.zip
```

- The container must see the **three storage roots**, or every blob is reported missing and the
  command exits 3 with an archive full of holes. `compose.prod.yaml` already bind-mounts all three
  (§7); `run` inherits the service definition, so this is a note to check rather than a step — and
  it is the first thing to check when an export comes back incomplete.
- `--out` must be on a **mounted volume**, or the archive lands in the container's writable layer
  and disappears with it. This is §7's own warning about the roots, one command over.
- The archive contains client data and no credentials. Say what is redacted, in one list, and point
  at `manifest.json` for the authoritative per-run copy.
- Exit codes: `0` complete, `3` written-with-gaps (read `manifest.json`), `1` nothing written, `2`
  usage. **A `<out>.partial` on disk means the run did not finish**; it is safe to delete.
- Disk: the archive is roughly the size of the company's evidence directory plus its row data.
  Check free space on `/var/backups` before exporting a large client — this is the one operator
  command that can fill a disk.

### Task 13: The handoff section

**Files:**
- Create: `docs/handoff/<NN>-the-company-data-export.md`
- Modify: `docs/HANDOFF.md` — **one index row, appended to the table. Never append prose to
  `HANDOFF.md` itself.**

- [ ] **Step 1: Take the next free § number at the moment you write it**

`§243` is the highest at the time this plan was written and **four other areas may be landing
concurrently**. Read the index, take the next free number, and use it for both the filename prefix
and the row.

- [ ] **Step 2: Write the section**

Cover, in the register the numbered sections use: that the walk is **catalogue-driven** and what
that buys (area C's `run_schedule` exported with no code change); the `CompanyContext`-outside-the-
transaction rule and *why* the obvious code is wrong; the six sentinels S1–S6 and which of them run
in production versus in CI; the **full redaction list**, table by table, because a handoff is where
somebody looks for it; the two exclusion classes and the four tables excluded outright; the archive
format and why zip + JSONL + `to_jsonb`; exit code 3 and the argument for it; `orphanBlobs` as
information rather than an error; the measured gate numbers from every phase; and the open question
on `company.logo_storage_key` with whatever Task 8 step 4 found.

- [ ] **Step 3: One index row**

```markdown
| [§NN](handoff/NN-the-company-data-export.md) | **The company data export** — area E of `docs/plans/2026-08-18-unplanned-gaps.md`… |
```

### Task 14: The orientation cache and the verification rows

**Files:**
- Modify: `docs/agent-context/be-java.md`
- Modify: `docs/verification/backend-mutation-checks.md`

> **Stagger this task.** Every area in the master plan closes by editing `be-java.md`. Two closes at
> once is the one conflict E's disjointness does not remove.

- [ ] **Step 1: `be-java.md`**

Replace the `Last verified:` header with this slice's, keeping the previous one below it as the
file's convention has it, and add a section covering three things a later agent needs and cannot
grep for:

1. **The seam:** `provisioning` now has a read-only command, the first one, and the first operator
   command that runs *inside* a `CompanyContext` rather than with none open.
2. **The rule it generalises, stated once so it outlives this area:** *a company-scoped operation
   opens `CompanyContext` **outside** the `@Transactional` boundary, because
   `CompanyAwareTransactionManager` reads the context in `doBegin` — a context opened inside the
   method is a transaction with no GUC and a silent zero-row read of every policed table.*
3. **The catalogue-driven pattern:** `ExportTables` and `ExportRedactions` are the third and fourth
   members of the family `SchemaConventionTest` started — *a schema-wide or catalogue-wide invariant
   is computed, never read* — and the two sweeps fail the build for a table or a column that does
   not exist yet.

**No `fe-react.md` entry.** This area has no frontend surface at all, which is what made it the one
area eligible to run beside G.

- [ ] **Step 2: The mutation rows**

**§65 is standing policy: write the rows, run nothing.** A slice that lands without them has skipped
the check outright. Ids are stable and quoted later, so do not renumber them.

| id | Mutation | Expected |
|---|---|---|
| `§NN CE-M1` | Move `CompanyContext.open(...)` from `ExportCompanyCommand` to the first line of `ExportCompanyService.export` | **Red** — `ExportCompanyTest.everyWalkedTableMatchesADirectCountTakenAsSuperuser`, every walked table at 0. **The headline mutation of the area**: it is the defect the master plan named, and if it comes back green the whole test is decorative |
| `§NN CE-M2` | Drop `isolation = REPEATABLE_READ` back to the default | **Expected GREEN**, and ⚠️ **recorded as a gap.** Nothing in the suite mutates a company *during* an export. Closing it needs a concurrent writer mid-walk, which is a test of its own; the argument for the isolation level is in the javadoc where a reader will find it |
| `§NN CE-M3` | Remove one sentinel (`key_sequence`) from S3 | **Expected GREEN** unless `aCompanyWithNoSeededVocabulariesAbortsRatherThanExporting` names that table specifically. ⚠️ **Check when running**: if it is green, the abort test is asserting the mechanism and not the list, and one arm per sentinel is the fix |
| `§NN CE-M4` | In `ExportRedactions`, delete the `app_user → password_hash` entry | **Red** — `ExportRedactionSweepTest.everySuspectColumnIsRedacted…` **and** `ExportCompanyTest.noRedactedValueAppearsAnywhereInTheArchive`. Two independent reds is the point: one reads the catalogue, one reads the bytes |
| `§NN CE-M5` | Add a fake secret column (`ALTER TABLE project ADD COLUMN webhook_secret TEXT`) in a scratch migration | **Red** — the sweep, on a column that did not exist when the test was written. **This is master task 34's acceptance and the row that proves it.** Revert the migration, not just the column |
| `§NN CE-M6` | In `ExportTables`, exclude one RLS-protected table (`test_case`) with a made-up reason | **Red** — `everyRlsProtectedTableIsWalked`. The failure mode with no symptom: a client's cases quietly left behind |
| `§NN CE-M7` | Make `Files.move(partial, out)` unconditional — move even on failure | **Red** — `theExportRefusesWithNoCompanyContext` and `aCompanyWithNoSeededVocabularies…`, both of which assert **no file exists**. The whole "the path existing is a true signal" property |
| `§NN CE-M8` | Return `EXIT_OK` from `report` when `missing` is non-empty | **Red** — `aRowNamingBytesThatAreGoneIsReportedRatherThanSkipped`, if it asserts the result outcome. ⚠️ **Check when running**: if that arm only reads the manifest, the exit code is untested and one assertion closes it |
| `§NN CE-M9` | Harvest blob keys by listing `<root>/<companyId>/` instead of from the rows | **Expected GREEN** on the happy path, ⚠️ **and that is the finding.** `companyBsBytesAreNotInTheArchive` catches it only if it asserts entry names. An orphan from a crashed report job would be exported as client data, which is the reason the rows are the authority |
| `§NN CE-M10` | Write `manifest.json` first instead of last | **Expected GREEN.** ⚠️ Recorded: nothing in the suite kills the process mid-archive. The ordering is a property of a **truncated** archive, and only a killed run demonstrates it |

- [ ] **Step 3: No frontend browser rows**

State it explicitly in the commit message rather than leaving the queue silently untouched: this
area has no browser surface, so `docs/verification/frontend-browser-checks.md` gains **no rows**.
"Nothing to drive, and here is why" is a legitimate outcome; a silent skip is not.

- [ ] **Step 4: Commit and push**

```bash
git add docs/
git commit -m "docs: the company export's handoff, runbook and mutation rows"
git push
```

---

## Area C, and what E gains when it lands

The master plan sequences E after C *"so it exports `run_schedule` too"*. **With the walk written as
Task 3 writes it, that is not a code dependency at all.** `V68` creates `run_schedule` with a
`company_isolation` policy like every other company-owned table; the export's table list is read from
`pg_policies` at run time; so the table is walked the first time the export runs against a database
migrated past `V68`, with no edit to any file in this area.

What C *does* change is two numbers and one test:

- `ExportTablesTest` sweeps the live catalogue, so it covers `run_schedule` automatically — **it
  does not need to be told**. If it ever needs editing to accommodate a new table, the sweep has
  been written as a list and the whole argument of Task 3 has been lost.
- `ExportRedactionSweepTest` covers any suspect column C adds. A schedule holding, say, a
  webhook trigger secret would fail the build in **C's** lane, not this one — which is the correct
  place for that conversation to happen.
- The handoff should name the dependency as *"nothing to do; verify by re-running
  `ExportTablesTest` after `V68` lands"*, so nobody plans a follow-up slice for work that is
  already done.

**Therefore E does not have to wait for C.** The master plan's parallelism section already marks
`C ∥ E` eligible; this is why it costs nothing.

---

## Open questions left for the implementer

1. **`company.logo_storage_key`** — is there a writer, and which root? Task 8 step 4. The plan
   refuses to guess; the manifest states whichever answer is true.
2. **Very large companies.** Nothing here bounds the archive's size or the transaction's duration. A
   `REPEATABLE READ` transaction held open for an hour holds back vacuum on every table it touched.
   That is acceptable for an offboarding run on a scheduled maintenance window and **is not
   acceptable as a nightly job**, which is why this is a command and not a scheduled task. If it
   ever becomes one, the row-count and duration go in the manifest first so the decision has
   numbers behind it.
3. **Import is not in scope and is not implied.** The archive is a faithful export, not a restore
   format: `formatVersion` and `schemaVersion` exist so a future importer can refuse a stale one,
   and nothing in this area promises one will be written.
4. **`instance_audit_event` is excluded** and a client could reasonably ask for the operator actions
   taken against their company (it carries a nullable `company_id` for exactly that correlation).
   Recorded as a deliberate refusal rather than an oversight; reversing it is a decision about
   whether an operator trail that spans clients can be filtered honestly, not a code change.

---

## Self-review

**Spec coverage.** Master tasks 33–40, each answered: 33 → Task 1; 34 → Task 2 plus the *Redaction*
section, decided **before** any code as the master required, with the enumerated list written into
the handoff at Task 13; 35 → Tasks 5 and 9, which assert row counts per table against a direct
superuser count, company B's absence over the whole archive, and every redacted value's absence;
36 → Tasks 6 and 10 step 3, one `CompanyContext` opened by the caller and one read-only
`REPEATABLE READ` transaction, with S1–S6 as the loud-failure mechanism; 37 → Tasks 8 and 9,
including the master's own acceptance arm (*"a seeded evidence file's bytes are in the archive"*);
38 → Task 11 and the `./gradlew build` gate at the end of every phase; 39 → Task 12; 40 → Tasks 13
and 14.

**The three things the brief required be settled, settled.** (1) The walk: the context is opened
outside the transactional boundary — with the reason, which is `doBegin` — and a short archive is
caught by a sentinel in production, by a superuser ground-truth count in CI, and by an atomic rename
that makes the file's existence meaningful. (2) The blobs: three roots, keys harvested from rows in
the same snapshot, entry names are the storage keys verbatim, missing bytes are declared and exit 3,
orphans are counted and not archived. (3) The redaction list: six columns across five tables, nine
tables excluded outright, four `content_hash` columns and two display aids explicitly kept with
reasons, and a catalogue sweep that fails for a column that does not exist yet.

**Type consistency.** `ExportCompanyResult` is produced by `ExportCompanyService.export` (Task 6),
extended with blob fields in Task 8 and consumed by `ExportCompanyCommand.report` (Task 10 step 4).
`ExportManifest.MissingBlob` is the one missing-blob shape, in the manifest and in the result.
`ExportRedactions.DROPPED_COLUMNS` is read by the walk's `to_jsonb(t) - ?` bind and by both sweep
tests. `ExportTables.walked()` is read by the walk, by `ExportTablesTest` and by the manifest.

**Three things this plan deliberately leaves to execution.** The exact `information_schema` and
`pg_policies` SQL is described and not written out — `SchemaConventionTest:133-145` already has both
shapes and copying them here would be a copy that drifts. `ExportCompanyTest`'s seeding is named and
its source is named (`CompanyProvisioningServiceTest`, `RlsIsolationTest`'s paired fixtures) rather
than transcribed. And Task 13's handoff prose is scoped, not drafted, because it must state the gate
numbers that were actually measured.

**What this plan does not touch, restated because it is the area's defining property.** No
`openapi.yaml`. No `npm run gen:api`. No `schema.d.ts`, no `schema.ts`. No Flyway version. No file
under `frontend/` or `packages/`. No new dependency in `build.gradle.kts`. That is why E is the one
area in the master plan eligible to run beside any other, and any step above that appears to need
one of them is a step that has gone wrong.

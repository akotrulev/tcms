# §246 — The company data export

**Area E of `docs/plans/2026-08-18-unplanned-gaps.md`** (master tasks 33–40), stepped first into
`docs/plans/2026-08-18-gap-e-company-data-export.md` as that plan requires. **No migration, no
contract change, no permission seeded or moved, catalogue still 55, head still `V67`, no file under
`frontend/` or `packages/` touched.** Fixture prefix **`7c`**.

> ⚠️ **BUILT, UNGATED AND UNVERIFIED.** This lane ran under the batch-2 standing directive to run no
> tests: `./gradlew build` was **not** run, no targeted class was run, and nothing here can quote a
> test count. Every claim below is read off the code. The `§246 CE-*` rows in
> `docs/verification/backend-mutation-checks.md` are unrun too, per §65. It ran **concurrently with
> area B** at the user's explicit permission — the two lanes share no file, which is the property
> that made E the one area in the plan eligible to run beside another.

## What shipped

A fourth operator CLI verb beside `provision-company`, `issue-invitation` and
`grant-instance-admin`:

```bash
java -jar tcms.jar export-company --slug=acme --out=/var/backups/acme.zip
```

Not an HTTP endpoint. The act is an operator's, it is long-running, it writes a file to the box's
filesystem, and it must be possible with no browser and no session. `docs/plans/2026-07-30-tcms-hetzner-deployment-runbook.md` §7a is the operator's copy.

New: `provisioning/{ExportCompanyCommand, ExportCompanyService, ExportCompanyResult, ExportManifest,
ExportTables, ExportRedactions}.java` and, in `storage`, `{BlobRoot, BlobInventory,
FilesystemBlobInventory}.java` plus one method on `FilesystemBlobStore`. Modified:
`OperatorCommands.java` (one constant, one `Set.of` entry) and `provisioning/package-info.java`.
Four test classes: `ExportTablesTest`, `ExportRedactionSweepTest`, `ExportCompanyTest`,
`ExportCompanyCommandTest`.

## The rule that outlives this area

**A company-scoped operation opens `CompanyContext` OUTSIDE the `@Transactional` boundary.**

`CompanyAwareTransactionManager` issues `set_config('app.current_company', …)` in `doBegin`, reading
whatever `CompanyContext` holds *at that moment*. A `CompanyContext.open()` written inside the
transactional method runs **after** the transaction has already decided there was no company: the
GUC stays unset for its whole life, every RLS policy evaluates `company_id = NULL`, which is never
true, and the walk reads **zero rows from every company-owned table without raising anything**.

That failure has no symptom. The archive opens, parses, and is empty — and the operator who ran it
has a file that looks exactly like a good one. It is worse than an outright failure, which is why
the whole class is shaped around making it loud.

`export-company` is also the **first operator command that runs inside a company context at all**.
Its three siblings run instance-level with none open — `CompanyContext.clear()`'s documented
"correct state for instance-level operator work" — so this area inverts the module's established
transaction handling rather than copying it.

## The six checks, and which of them run where

There is no in-band ground truth for an RLS-protected table. The counterfactual count — *"how many
rows should there have been?"* — is itself an RLS-filtered query, so with the context lost it also
answers zero, and comparing the two proves nothing. That is not a gap in the design; it is why the
checks are the shape they are.

| # | Check | Where it runs | Catches |
|---|---|---|---|
| **S1** | `CompanyContext.require()` at entry, asserted equal to the company being exported | production | Called with no scope, or the wrong one |
| **S2** | `SELECT current_setting('app.current_company', true)` as the **first** and again as the **last** statement | production | A walk that spanned two transactions — `SET LOCAL` does not survive one, so the second read is NULL |
| **S3** | Five seeded vocabularies non-empty: `case_status`, `classification_dimension`, `company_membership`, `key_sequence`, `role_assignment` | production | Context lost before the walk, a misconfigured policy, or an export pointed at a half-provisioned company |
| **S4** | Per-table row counts and a SHA-256 per entry in the manifest, written last | production | A truncated or hand-edited archive, months later, by a reader who was not there |
| **S5** | Every walked table's archived line count against a **superuser** count that bypasses RLS | suite | The real thing: any table the export under-reads, for any reason |
| **S6** | `theExportRefusesWithNoCompanyContext` | suite | An "improvement" that moves the `CompanyContext.open` inside the transaction |

S3 carries the load in production; S5 carries it in CI. **The sentinel list is not a formality** —
it is `CompanyProvisioningServiceTest`'s completeness claim (a provisioned company can classify a
case, allocate a display key and record an execution status) reused as a liveness probe, and a
half-provisioned company and a lost context are indistinguishable from inside the walk. Refusing
both is correct.

The refusal message names **all five** sentinels, and `everySentinelTableIsNamedInTheRefusal` asserts
that rather than merely asserting it aborted — a check written about "did it abort" leaves four of
the five decorative, which is §241's and §243's lesson in a third place.

**`<out>.partial` + `ATOMIC_MOVE`.** Every failure path deletes the partial and the rename is the
last act, so **a file at `--out` is a file whose walk finished**. That makes "the archive is there"
a true signal instead of a hopeful one, and it is what three of the test's arms assert (`CE-M7`).

## The walk is catalogue-driven, and that is what area C costs

`ExportTables.classify` reads `pg_tables`, `pg_policies` and `information_schema.columns` **at run
time** and partitions them:

- **walked with `WHERE company_id = ?`** — everything carrying a `company_isolation` policy, plus
  the target-column tables that carry `company_id` and no policy;
- **`company`**, filtered on `id`;
- **`app_user`**, member-scoped through `company_membership`;
- **excluded**, eleven tables each with an `Exclusion` reason that travels into the manifest;
- **unclassified** — which **throws at export time, not only in a test**, because a deployment
  running a build older than its database is exactly the case a test cannot help with.

A `List.of(...)` of ~78 table names is the shape ERD §0.1 has already paid for twice: reading `V1`
top to bottom missed five join tables and four version tables, and scripting the comparison found
them. `SchemaConventionTest` exists because the invariant needs **computing**; this is the same
invariant one direction over — *is every table accounted for* — so it computes it.

**The consequence for area C: nothing to do.** When `V68` adds `run_schedule` with a
`company_isolation` policy, it arrives in the walked partition by construction and is exported the
first time the command runs against a migrated database, **with no edit to any file in this area**.
`ExportTablesTest` sweeps the live catalogue, so it covers `run_schedule` automatically. **If that
test ever has to be edited to accommodate a new table, the sweep has been rewritten as a list and
the whole argument has been lost.** Verify by re-running `ExportTablesTest` after `V68` lands; do
not plan a follow-up slice for work that is already done.

## The redaction list, in full, because a handoff is where somebody looks for it

**Decided before any code.** A redaction decided during implementation is a redaction that gets one
table wrong.

### Dropped columns — the table travels, the column does not

| Table | Column | Why |
|---|---|---|
| `app_user` | `password_hash` | A bcrypt digest is an offline-cracking target, and these are people's real reused passwords |
| `api_token` | `token_hash` | The digest a token is checked against. `name`, `token_prefix`, `expires_at`, `revoked_at` are **kept** — an operator must be able to see *which* integrations existed |
| `membership_invitation` | `token_hash` | An unconsumed invitation digest is a live admission to the company |
| `notification_channel` | `secret` | AES-GCM ciphertext of a webhook URL, and **a webhook URL is the authentication for the room it posts to**. `secret_hint` is **kept**: its own column comment calls it *"the only part of the credential that ever leaves this table"* |
| `identity_provider` | `client_secret_encrypted` | The OIDC confidential-client secret |
| `identity_provider` | `bind_password_encrypted` | The LDAP service-account bind password |

Ciphertext is redacted on the same terms as a digest: *"encrypted with a key you do not have"* is a
property of today's holder, not of the archive's future ones.

### Excluded tables — no row at all

`api_token_lookup`, `sso_login_attempt`, `social_login_attempt` (`CREDENTIAL_ONLY`);
`user_social_identity`, `instance_role_assignment`, `instance_audit_event` (`CROSS_COMPANY`);
`permission`, `role`, `role_permission` (`INSTANCE_REFERENCE`); `instance_setting`,
`flyway_schema_history` (`INSTANCE_INFRASTRUCTURE`).

Exporting a row with every column blanked is worse than not exporting it — it invites the reader to
believe something was lost. `role_assignment`, which is *which* role this company's people hold, **is**
exported; it is the part that carries meaning.

**`instance_audit_event` is a deliberate refusal, not an oversight.** It carries a nullable
`company_id`, so a filtered export is technically possible. The trail spans clients and correlating
it is an operator's job, not an offboarded client's. Reversing that is a decision about whether a
cross-client operator trail can be filtered honestly, and it is not a code change.

### What is deliberately NOT redacted, and why the rule is a list rather than a pattern

`test_case_version.content_hash`, `suite_version.content_hash`, `test_plan_version.content_hash` and
`doc_page_version.content_hash` are **content addresses of the client's own test assets**. A blanket
*"strip anything matching `%hash%`"* rule would destroy the integrity check on every version snapshot
in the archive, which is the opposite of the goal. Three more are token **ids**, not tokens:
`audit_event.actor_token_id`, `test_execution.executed_by_token` and `ai_proposal.created_token_id` —
§132's attribution, and dropping them would leave an exported audit trail unable to answer the
question it exists for.

So the redaction list is **enumerated**, and the sweep that guards it is the pattern.
`ExportRedactionSweepTest` reads `information_schema.columns`, flags any column name containing
`hash · secret · password · token · credential · encrypted · nonce · verifier · salt · cipher ·
private_key`, and fails unless each is dropped, in an excluded table, or on `DELIBERATELY_KEPT` with
a written reason — **including for a column that does not exist yet**, which is master task 34's
stated acceptance. `key` is deliberately **absent** from the suspect fragments: every storage key,
idempotency key, permission key and project key would match it, and a list that flags sixty benign
columns is a list whose failures get waved through.

`app_user` is exported **member-scoped** — only the rows this company's `company_membership` names.
Without it the archive is a graph of UUIDs and every `created_by` is unreadable, which fails the GDPR
half of the goal outright.

## The archive

```
manifest.json     ← written LAST
README.txt
tables/<name>.jsonl        one per walked table, alphabetical
blobs/evidence|reports|raw-reports/<storage key verbatim>
```

**Zip**, because `java.util.zip` is in the JDK (the command adds no dependency to a jar that is also
the server), entries are individually addressable, and `unzip` needs nothing installed on a client's
machine. **JSONL, not CSV**, because NULL and empty string are different values and CSV cannot say
so, and because four columns in this schema are JSONB. Each row is produced by Postgres itself:

```sql
SELECT (to_jsonb(t) - ?::text[])::text FROM <table> t WHERE t.company_id = ? ORDER BY <pk>
```

`to_jsonb(t)` means **no per-column Java mapping**, so a column a later migration adds is exported the
day it lands; `- text[]` is jsonb key deletion, so redaction is one bind parameter that cannot be
forgotten per column; `ORDER BY` the primary key makes it deterministic, and determinism is what lets
somebody **prove** an export is complete by diffing two runs.

## The blobs

Evidence, report artifacts and raw CI payloads are files on a filesystem, not rows in Postgres.
Keys are harvested from `attachment.storage_key`, `report_artifact.storage_key` and
`ingestion_job.raw_storage_key` (nullable — a null is not a missing blob) in the **same transaction
and therefore the same snapshot** as the row dumps, so the blob set and the row set describe one
instant.

**The rows are the authority, not the directory.** Listing `<root>/<companyId>/` and archiving
whatever is there sweeps orphans left by failed writes, depends on a key layout that is
`BlobUpload`'s business, and would silently start exporting somebody else's bytes the day that layout
changed. `CE-M9` records that this is **expected green** if mutated — the suite cannot see it — which
is the honest statement about it.

Entry names are the storage key **verbatim** under a per-root prefix. The client's own
`attachment.filename` is *not* a path: it is client-supplied, two screenshots on one execution are
both `screenshot.png`, and a zip entry name is a classic traversal sink. It stays a field in the
table dump and `README.txt` says a reader who wants original names joins on the key.

Bytes are **streamed**; a company with 4 GB of evidence must not need 4 GB of heap.

### The two reconciliations

- **`missingBlobs`** — a walked row whose key opens to nothing. Listed as `{table, id, storageKey}`,
  printed on stdout, `"complete": false`, and the command **exits 3**. The archive is still the best
  available copy of the client's data and refusing to write it helps nobody; exiting `0` would tell
  an offboarding script that a complete copy exists. That is the fourth exit code and the argument
  for it.
- **`orphanBlobs`** — files under this company's prefix that no walked row names. Counted and
  listed, **not archived**: unreferenced bytes whose provenance nothing can state, and an archive
  including them would be claiming they are the client's data. `ReportJobRunner` writes bytes first
  and the row second on purpose, so a crashed job leaves exactly this — the count is information,
  not a fault, which is why it prints on every run rather than only on the bad one.

### The fourth storage port, and why it is a fourth type

Orphan reconciliation needs enumeration, and the three blob ports cannot provide it:
`AttachmentStore`'s header refuses precisely that shape — *"An implementation that could be asked
'list this company's files' would be one refactor away from being the place that decides, and the
decision would then exist in two places."*

That argument is about the ports every **request path** holds, whose callers are answering *may this
person reach these bytes*. So the capability went onto a type those callers do not hold:
`storage/BlobInventory` with one method, `keysUnder(BlobRoot, prefix)`, whose only caller is this
export and whose javadoc says in as many words that it **must never become an authorization input**.
`BlobRoot` is an enum rather than a qualifier string for the package's own reason, one turn weaker:
it names roots, never bytes, so a wrong member yields a wrong *listing* and never a misplaced file.

`provisioning`'s `allowedDependencies` gains `"storage"` — its fourth entry. `storage` is one of
`ArchitectureRules.FOUNDATION_MODULES` and four other modules already declare it, so this is not the
"wider list standing in for a port" that `project`'s header warns against; that warning is about
reaching sideways into a peer.

## The open question the plan refused to guess, closed

**`company.logo_storage_key` has no writer anywhere in this build.** Grepped
(`logo_storage_key|logoStorageKey|setLogo|logoKey`) across `backend/src`, `frontend/src`,
`packages/` and `openapi.yaml`: **two hits, and neither is a writer** — the `V1__initial_schema.sql:35`
DDL, and `reporting/ReportHeader.java:55`, whose javadoc already says the column and the two colour
columns *"are unmapped and have no writer anywhere (branding is M6)"*. It is not mapped on the
`Company` entity either.

**Therefore no root can be named for it**, because no writer has ever chosen one, and the plan's
instruction was explicit: *do not guess a root* — a wrong one writes a company logo under the reports
root, silently, which is the exact failure `storage/package-info.java` keeps two port types to turn
into a compile error. The column is **walked like any other** (it is in `DELIBERATELY_KEPT` as "a
blob key, not a credential"), and the manifest records `"logoBlob": "no writer in this build"` so the
reader is told rather than left to wonder. **When branding lands in M6, that slice picks the root and
adds one line to `harvestBlobKeys`.**

## What is owed

- **Nothing here has been gated.** `./gradlew build` is the first thing to run against this area.
- **No test runs the command as a process**, so the four exit codes and the usage text have no
  automated observer. `ExportCompanyCommandTest` covers parsing only; the runbook's §7a is the
  instruction and a real run against a provisioned instance is the check.
- **`CE-M2`, `CE-M9`, `CE-M10`, `CE-M12`, `CE-M13` are expected green and recorded as gaps** rather
  than presented as checks — the REPEATABLE READ snapshot, the rows-are-the-authority harvest, the
  manifest-written-last ordering, the unclassified-table refusal and S2b each need a fixture the
  suite does not have (a concurrent writer, a real orphan, a killed process, a temporary table, a
  walk spanning two transactions).
- **Import is not in scope and is not implied.** The archive is a faithful export, not a restore
  format; `formatVersion` and `schemaVersion` exist so a future importer can refuse a stale one, and
  nothing here promises one will be written.
- **Very large companies.** Nothing bounds the archive's size or the transaction's duration, and a
  REPEATABLE READ transaction held open for an hour holds back vacuum on every table it touched.
  Acceptable for an offboarding run in a maintenance window; **not acceptable as a nightly job**,
  which is why this is a command and not a scheduled task. If it ever becomes one, the row count and
  duration go in the manifest first so the decision has numbers behind it.

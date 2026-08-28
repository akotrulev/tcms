# be-planner — orientation cache (TCMS)

**Baseline commit:** `8f50f6a5` · **Created:** 2026-08-28 · Working tree dirty (docs only).
Re-verify every cited file before quoting it in a plan; this file is a map, not evidence.

## Version lane

| | |
|---|---|
| Backend | Java 21 · Spring Boot 3.5.6 · Gradle · Spring Modulith · `-Werror` |
| DB | Postgres 17 · Flyway owns all DDL · `hibernate.ddl-auto: validate` · `baseline-on-migrate: false` |
| Frontend | Vite 8 · React 19 · TS strict · MUI v9 · React Router 7 · TanStack Query · **no Next.js/RSC** |
| Packages | Node workspace: `@tcms/client` → `@tcms/cli` → `@tcms/mcp`, built in that order |
| Auth | Keycloak / OIDC; `api_token` digests (`V36`); external identity `V53`/`V57`/`V58` |
| Tests | Testcontainers, real Postgres. **No H2 lane** (RLS, ltree, citext). No WireMock/MockWebServer. |

## Build, run & test — gates run on `shanks`, never locally

`~/bin/tcms-remote sync` · `… backend ./gradlew build` · `… frontend npm run test:run` ·
`… packages npm run build`. Read `docs/specs/shanks-runner-details.md` first (the fd-limit
false-red). CI on push = backend **compile only**, frontend **smoke only**; the backend suite gates
nothing before master (`gh workflow run tests.yml`).

## Layout

- Modules are Java packages under `com.tcms`: `api · tenancy · identity · project · storage`
  (foundation) + `repository · execution · documentation · reporting · work · integration · auth ·
  provisioning`.
- `ArchitectureRules.FOUNDATION_MODULES` javadoc states the bar a **sixth** must clear. A proposal
  that needs no sixth entry owes no paragraph.
- Cross-module crossing is `tenancy.OutboxHandler` + a `CompanyScopedEvent`. Implemented today in
  `reporting/FactRefreshOnResultsChanged`, `repository/HealthRecomputeOnResultsChanged`,
  `repository/HealthRecomputeOnLinksChanged`.
- Each module's `package-info.java` declares `@ApplicationModule(allowedDependencies = …)` **and
  argues it**. `integration` = `{api, tenancy, project}` and says `execution`/`work` "never may be".
- Frontend: `src/features/<area>/`, `src/api/queries/` (one `keys.ts`), `lib/labels.ts`,
  `lib/describeError.ts`, `DataRegion`, `components/ErrorBoundaries.tsx`.

## Seams to reuse

| Seam | Where |
|---|---|
| Credential at rest | `integration/SecretCipher.java` (AES-GCM, **package-private**), `SecretHint`, `SecretRedaction` |
| Outbound HTTP | `integration/WebhookPoster.java` — the timeouts are why it is a bean. **No URL/scheme/host validation anywhere in `integration/`** — pre-existing SSRF surface. |
| Secret-set-separately write shape | `integration/NotificationChannelInput.java` (null = unchanged), `NotificationChannel#replaceSecret` |
| Request-path controller in `integration` | `NotificationChannelController.java:55` (`If-Match` on PUT) |
| Per-company scheduled sweep | `tenancy/OutboxScheduler.java:78-80` — `companies.findAll()` + `CompanyContext.open()` + per-company catch. Siblings take initial delays 1/2/3 min. |
| Idempotency keys | `V17`, `V42`, `V59`, `V66` |
| Derived facts off links | `repository/SpecLinkage.java` derives `UNLINKED` from a bare `count(*)` over the mirror — **no `target_kind` predicate**, so every external link clears it by default |
| Caller identity in `integration` | `@AuthenticationPrincipal Jwt` in the controller → plain `UUID userId` parameter into the service (`NotificationChannelService.java:149`). No `identity` import; `storage`'s "a UUID it never resolves" pattern |
| Traceability edge | `documentation/DocLink` (all columns `updatable=false`) + mirror `repository/CaseDocLink`, refreshed by `repository/DocumentationFacts.java:203-211` (`target_kind <> 'DOC_BLOCK'`), CHECK closed over 3 kinds in `V47` |
| Defect links | `execution/DefectLink.java` — `provider` is **free text by decision** (`:50-54`); `status_cache`/`synced_at` writerless (`V62`); unique index `(company_id, execution_id, provider, external_key)` |
| Coverage | `reporting/CoverageMatrixReport.java:171` takes `docPageId` — doc-block scoped only |
| **Cross-leaf read from a foundation module** | `project/ReviewSubjectDirectory.java:40` — **interface in `project`, impls in the owning leaves** (`repository/CaseReviewSubjects`, `repository/SuiteReviewSubjects`, `execution/PlanReviewSubjects`, `integration/ReviewSubjects`). Dependency inversion; ArchUnit-clean. **This is the sanctioned answer** when `project` (deps `{api, tenancy}`) needs data from `documentation`/`execution`. |
| Cross-leaf read, weak precedent — do NOT copy into a foundation module | `reporting/CoverageMatrixReport.java:509` reads `documentation` via an **opaque id with no FK**. ArchUnit cannot see raw SQL, so this inverts the architecture invisibly. |
| Review subjects are a **closed enum bound to permission keys** | `project/ReviewSubjectType.java:30-42` — `CASE("case:review")`, `SUITE("suite:review")`, `PLAN("plan:review")`. A fourth member costs a catalogue key + §A3 + both copies pinned by `matrixCopies.test.ts`. |
| Credential reads are gated on the **write** key, by argument | `identity/ApiTokenService.java:47-52` (*"no partial-visibility story for credentials"*), `identity/IdentityProviderService.java:30-42`. Splitting these into read/write halves reverses an argued decision. |
| Stored-but-unserved columns | `identity/RoleAssignment.java:69` `grantedBy` · `execution/RunSchedule.java:148,151` `updatedAt`/`updatedBy` — all absent from the wire. Check the entity before costing a "new field" as a migration. |
| `Problem` optional-member claim | `openapi.yaml:8049` argues across §162→§163→§164 that **`errors` is the one optional member**, checked by `ErrorBodyPolicyTest.bothEnvelopesSerializeTheSameKeys` + `theContractsProblemIsThisRecord`. Adding a member costs the comment too. |

## Conventions observed (project narrows the library defaults)

- Repository **interfaces** must carry `@Transactional(readOnly = true)` or RLS returns zero rows,
  silently. `RepositoryTransactionTest` enforces.
- `company_id NOT NULL` leads every index — ERD §0.1, `SchemaConventionTest`.
- `openapi.yaml` is the contract; **two** generated copies (`frontend/src/api/schema.d.ts`,
  `packages/client/src/schema.ts`), regenerated in the same commit. Add `required` on every schema
  including inline nested objects.
- Spring required property = `${VAR}` **with no colon**. `ConfigConventionTest` reads the text.
- Superseded decisions are struck through and kept, never deleted.
- Applied migrations are immutable — argue in the new one.
- Work is **sequential**; one subagent at a time. Work on the current branch, do not create one.
- Plans live in `docs/plans/<NNN>-<initiative>.md` — **flat files**, house format set by `003`.

## Plan artifacts

| File | State |
|---|---|
| `docs/plans/003-case-authoring-redesign.md` | proposed, nothing implemented. Claims `V71`. Holds `openapi.yaml`. |
| `docs/plans/004-issue-tracker-integration.md` | **revision 5, 2026-08-28** — this agent's. Proposed, nothing implemented. Claims `V72`–`V74`. **Per-user credential; no service credential; no status sync-back; link stores the tracker id, never the key. Zero open questions.** |
| `docs/plans/005-renders-backend-support.md` | **revision 2, 2026-08-28** — this agent's. Proposed, nothing implemented. **Goes FIRST: holds `openapi.yaml`, claims `V71`.** Backs `docs/specs/2026-08-28-renders-backend-grounding.md` (which is now corrected in place and defers to 005). |

**`NNN` high-water mark: `005`.**

**Flyway high-water: `V69` on disk.** Numbering was **re-cut on 2026-08-28** when 005 was placed
first; 003 and 004 shift **+1** and **their files still say the old numbers until amended**:

| Claim | Number |
|---|---|
| `002-` §255 `gherkin_source` deleter | `V70` (unchanged) |
| **plan 005** (S3, `review_enabled_doc` inert) | **`V71`** |
| plan 003 case authoring | **`V72`** (file still says `V71` at :6, :389) |
| plan 004 issue tracker | **`V73`–`V75`** (file still says `V72`–`V74`, ~20 mentions) |
| plan 005 S10 lockout, if it needs DDL | next free when written, `V76`+ |

**Next free: `V76`.**

**Three plans claim `openapi.yaml`** (003, 004, 005); `parallel-work-rules.md:19` allows one holder.
**005 holds it first**, then 003, then 004 — both rebase by re-running `gen:api`, never by
hand-merging the generated files.

**The out-of-order trap that set 005's slice order:** `out-of-order` is unset, so claiming a LOW
migration number and shipping it LATE is refused on any already-migrated database — and
Testcontainers cannot see it (fresh container every run), so the gate stays green and the lab
breaks. 005 therefore lands `V71` in its third slice, inert, before 003 begins.

**Live catalogue collision:** 005's `doc:review` (S9b) and 004's possible `integration:manage` read
key are both "the 56th key". Going first, `doc:review` is 56th and 004's is 57th. Unresolved.

## Known unknowns

- `002` is claimed by the §255 deleter but has no plan file on disk.
- Catalogue is 55 keys and plan 004 rev 4 keeps it there (visibility = project assignment +
  the caller's own tracker credential; nothing left for a 56th key to gate).
- Unmapped for planning purposes: `work`, `auth`, `provisioning`, `reporting` internals.
- `data-architect` owns anything below the logical altitude of `V72`'s DDL; `devops-engineer` owns
  secret provisioning for the tracker PATs.

## Session log

- **2026-08-28** — FULL orientation (no cache existed). Entry mode `DETAIL` (a hand-written draft
  plan, no code). Pressure-tested and rewrote `004` as revision 2: seven corrections, three
  decisions superseded. Cache created.
- **2026-08-28** — `DETAIL` again, same initiative. Product answered the open questions and
  inverted the credential model. Rewrote `004` as revision 4: per-user credential (`PER_USER`
  promoted from deferred to the only mode), no service credential anywhere, status sync-back out
  of scope, Phase 4 deleted. Verified three provider constraints externally — **Jira DC OAuth 2.0
  does NOT require 11.2+** (documented for DC 9.12 onward); ADO **global** PATs decommission
  2026-12-01 and org-scoped survive; Jira Cloud API tokens carry a mandatory ≤365-day expiry.
- **2026-08-28** — `DETAIL`, same initiative. Product closed the last four questions. Rewrote the
  affected sections as revision 5: id-only link identity (`external_provider` + `external_issue_id`
  + `external_host`, no key, no `binding_id`), links survive binding deletion, a
  `integration:manage`-gated connected-members list with a closed field set, GitHub/GitLab deferred.
  Verified: Jira redirects moved issue keys within a site (not across a migration); ADO work-item
  ids are organization-scoped unique and id-addressable in both REST and browser URLs. Plan now has
  **no open questions**.
- **2026-08-28** — TRUST (cache at HEAD; delta was docs-only). Entry mode `DETAIL` — grounding
  analysis for the nine UI renders, no code. Verified ~12 load-bearing claims in
  `2026-08-28-renders-backend-grounding.md`; found five errors (miscounted piles, §3's
  "no migration" header false for 4 of 10 rows, `grantedBy` already stored, N8 mis-costed as the
  cheapest when it is the most expensive, the "live defect" is half-real). Wrote `005`:
  11 build / 5 defer / 13 drop, 10 slices, 5 open questions. Key structural finding recorded above:
  N7/N8 are `ReviewSubjectDirectory`-shaped SPI extensions, not queries.
- **2026-08-28 (same session)** — coordinator answered all five questions; **Q5 reversed**: 005 goes
  FIRST and takes `openapi.yaml`. Revised `005` to revision 2. Consequences worked through rather
  than restated: migration block re-cut to `V71` only (not a `V71`–`V72` block — a claimed-unwritten
  number is a hole), `V71` split into its own inert slice landing third because of the out-of-order
  trap above, slice order re-derived on **collision-first** rather than cheap-first (`Problem` leads,
  since every refusal in 003/004 inherits it), and the exact +1 amendments to 003/004 enumerated in
  005 §8.2. Q1 recorded as decided, Q4 dropped the audit-IP row into the design-change list, Q2
  stays open, new **Q6** raised (the 56th-key collision). Verdicts recounted honestly as
  17 build / 6 defer / 14 drop over 37 discrete decisions.

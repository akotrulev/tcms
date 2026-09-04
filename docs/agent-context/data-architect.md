# data-architect — orientation cache

Baseline: 016 T2, created 2026-09-04 @ aaf6ca27.
Trust this as a lead, not evidence; `CLAUDE.md` and `docs/specs/*` outrank it.

## Version lane
- **PostgreSQL 17** (`#volatile` — re-probe every run; Testcontainers boots it for every backend
  test, there is no H2 lane because RLS, `ltree` and `citext` have no H2 equivalent).
- Backend Java 21 / Spring Boot 3.5.6 / Hibernate 6; `hibernate.ddl-auto: validate`, so booting a
  context is itself a schema gate.

## Build, run & test
- Migrations are applied by **Flyway as `tcms_owner`**, the pool connects as `tcms_app`.
  `baseline-on-migrate: false` so `V1`'s RLS block always executes.
- Gates run on `shanks` via `~/bin/tcms-remote` — standing directive, never locally.
  Schema lane: `~/bin/tcms-remote backend "ulimit -n 65535; ./gradlew test --tests 'com.tcms.schema.*'
  --tests 'com.tcms.tenancy.*'"`. `BUILD FAILED` on `Could not write XML test results` with zero
  assertion failures is the fd limit, not a red suite.

## Layout
- `backend/src/main/resources/db/migration/` — `V1__initial_schema.sql` (87 tables, 74 forced RLS
  policies, the reference data; V2–V75 were absorbed into it 2026-08-29 and deleted, so it only ever
  runs on an empty database), then `V2__pm_foundation` · `V3__tracker_core` · `V4__retire_work_item`
  · `V5__tracker_board` · `V6__release` · `V7__import` · `V8__agent` · `V9__time`.
  **`out-of-order` is unset** — a reused number is SILENTLY not applied and Testcontainers, which
  builds every database from empty, can never show the conflict. `ls` the directory before writing.
- Role split for compose and the VM: `deploy/postgres/initdb/10-tcms-roles.sql`.

## Seams to reuse
- **Tenancy is the invariant everything rests on.** `company_id UUID NOT NULL` on every
  company-owned table, leading every composite index and every previously-global uniqueness
  constraint. `FORCE ROW LEVEL SECURITY`, never merely `ENABLE`.
- **The isolation predicate is byte-identical everywhere** and pinned by
  `SchemaConventionTest.everyPolicyHasTheExactIsolationPredicate`:
  `(company_id = nullif(current_setting('app.current_company', true), '')::uuid)`, `USING` only —
  an explicit `WITH CHECK` FAILS the test, because a USING-only policy already reuses USING as the
  insert check.
- **The new-table idiom is the `DO $$` loop**, copied verbatim from `V1` (see `V8` §4, `V9` §5): a
  `protected TEXT[]`, an existence check that RAISEs, `ENABLE` then `FORCE` then `CREATE POLICY`,
  and a closing `array_length` assertion so a loop covering fewer tables than intended fails loudly.
- **No `GRANT` statements in any migration.** `ALTER DEFAULT PRIVILEGES FOR ROLE tcms_owner` (set up
  before Flyway runs) already grants `tcms_app` SELECT/INSERT/UPDATE/DELETE on every new table.
- **`tcms_seed_project_defaults` is never restated "to be safe".** `CREATE OR REPLACE FUNCTION`
  replaces the whole body, so a reproduction that omits a block silently stops seeding it
  (`ProjectKeySequenceSchemaTest` is what catches one that went missing). Only touch it when a new
  **per-project default row** is genuinely being seeded — `V3` §5 is what that looks like.
- **Polymorphic references carry no FK, by module rule not omission**: `attachment.owner_id`,
  `doc_link.source_id`, `review.subject_id`, `issue_link.target_id` (`work_item_link`'s successor
  since `V4` dropped it) and `time_entry.subject_id` (`V9`). The cost — a deleted target orphans the
  row, integrity in application code — is named in ERD §0.1. A real FK is used wherever the target
  belongs to a foundation module (`api · tenancy · identity · project · storage`).
- Read projections have exactly **one writer** each: `issue_fact` ← `tracker.IssueFactProjection`,
  `issue_time_rollup` ← `time.RollupService`. A downstream module writing another module's
  projection is the failure ERD §7a point 2 names.

## Conventions observed
- `id UUID PRIMARY KEY` (no `uuidv7()` — the app supplies ids), `created_at`/`updated_at
  TIMESTAMPTZ NOT NULL DEFAULT now()`, `created_by`/`updated_by UUID` **without** an FK,
  `lock_version BIGINT NOT NULL DEFAULT 0` for optimistic locking.
- Enum-ish columns are `TEXT` + a named `ck_<table>_<column>` CHECK, never a PG enum type.
- Indexes are `ix_<table>_<what>`, uniqueness `uq_<table>_<what>`, always `company_id` first.
- **Comments carry the *why*** and are load-bearing here in a way they are not elsewhere:
  `SchemaCommentTest` fails when a `COMMENT ON` names a `table.column` or a bare `snake_case` token
  the database does not declare, so a comment naming a dropped table (e.g. `work_item_link`) reddens
  the build. `--` comments are outside that corpus but are still read by `citedFiles.test.ts` for
  file paths.
- **A pinned count moves with every new company-owned table**: `SchemaConventionTest`'s
  `.isEqualTo(95)` (92 before `V9`). It is what makes the predicate check non-vacuous — a table
  added without its policy would otherwise pass by not being looked at. The plans do not always
  remember it; check it whenever a migration adds a table.

## Known unknowns
- `#volatile`: the live schema, the server version, current index/statistics state, real data
  volumes. Never plan a migration against the cached shape — re-probe what you are about to touch.
- No `EXPLAIN` work has been done on this schema by this agent; every index here is chosen from the
  stated access pattern, not from a measured plan.

## Session log
- 2026-09-04 — 016 T2: wrote `V9__time.sql` (`timesheet_period`, `time_entry`, `issue_time_rollup`
  + `project_settings.track_time`), bumped the policy count 92 → 95, added ERD §7f and the §0.1
  polymorphic-reference paragraph. Schema + tenancy suites green on shanks.

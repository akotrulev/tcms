# Collapsing the migration set into a fresh `V1`, at feature-complete

**Status:** decided in principle, not scheduled. Written 2026-08-15.
**Decision owner:** the user, who proposed it. Recorded here so it does not get lost between now
and the cut, and so the deadline below is visible before it passes.

---

## 1. The decision

When the product is feature-complete, replace `V1`…`V55` with a single regenerated
`V1__initial_schema.sql`, wipe the lab and production databases, and redeploy. The migration
history exists to move a database that already holds rows; while no deployment holds rows worth
keeping, it is carrying a cost with no corresponding benefit.

This is a **cleanup**, not a fix. It was raised on 2026-08-15 while diagnosing the lab deploy
failure of `87a2614` (Actions run 31897579899), and it does not address that failure — see §5.

## 2. Why it is reasonable

55 files, 8,533 lines. Every backend test migrates all 55 into a fresh Testcontainers Postgres, so
the set is on the critical path of the whole backend suite, repeatedly, per test class.

Neither deployed database holds data that cannot be recreated: the lab is a homelab box seeded from
`provision-company`, and production has never been handed to a client. A squash is normally
unavailable precisely because the wipe is unavailable, and that is not yet true here.

## 3. The deadline is FIRST REAL DATA, not feature-completeness

**These are two different dates and the earlier one binds.** The squash is available only while both
deployments are disposable. The moment a client company's rows land on the Hetzner box, wiping stops
being a cleanup and starts being data loss, and the option closes **permanently** — there is no
version of this that recovers it later.

So "when all features are implemented" is the *intent*, but the *constraint* is "before the first
company that matters is provisioned in production". If a client is onboarded during M5 while M6 is
still being built, the window shut in M5. Check which came first before assuming the plan is still
live.

## 4. What it costs, and how to not pay it

### 4.1 The prose is the majority of the corpus

**4,495 of the 8,533 lines are comments — 53%.** These migrations are not DDL with notes; they are
decision records that happen to execute. `V4`'s `nullif(…, '')` header is the only written account of
the two spellings of an unset GUC. `V41` §1 names the four sites that must stay baseline-specific or
a role becomes unissuable. `V51` and `V52` carry the reasoning for emptying `OWN` and `PLAN_SCOPE`,
including the argument that expired.

Counted by grep for uppercase `V<n>` tokens (not all are migration citations, but the order of
magnitude is the point):

| Tree | Hits |
|---|---:|
| `docs/` | 2,018 |
| `backend/src/` | 1,471 |
| `frontend/src/` | 114 |
| `packages/client/src/` | 97 |
| `packages/mcp/src/` | 22 |
| `packages/cli/src/` | 1 |

Deleting the files orphans every one of those references.

**The mitigation is to keep the prose and drop only the files.** Make the new `V1` a *sectioned*
file whose headings keep the original version tags:

```sql
-- =============================================================================
-- V52 — the tier line is approvals and configuration, and nothing else
--       (originally V52__the_tier_line_is_approvals_and_configuration.sql)
-- ...the original header, verbatim...
-- =============================================================================
```

Then `V52` still resolves — as a heading rather than a filename — and every existing citation stays
true. `citedFiles.test.ts` is not a constraint here either way: those are text citations, not paths.
A citation that names the *filename* (`{@code V52__…sql}`) **is** a path-shaped citation and must be
swept; a bare `V52` is not.

### 4.2 `pg_dump` is the cross-check, not the source

`pg_dump --schema-only` reproduces the DDL and the `COMMENT ON` strings. It does **not** reproduce
the `--` blocks above each statement, which is where 53% of the value is. So the new `V1` is a
hand-merge of 55 headers, and the dump's job is to prove the merge did not drop or reorder a
statement — diff the dump of a database migrated the old way against a dump of one migrated from the
new `V1`, and require them identical.

### 4.3 What makes this safe rather than a gamble

The schema is already heavily asserted, and those assertions do not care how the schema was built:

- `SchemaConventionTest` — `company_id` on every non-exempt table, and the RLS predicate on all 56
  policies.
- `PrivilegeSplitTest` — every property of the two-role split.
- `SeedDataTest` — the seeded matrix against the roles spec, cell by cell, both directions.
- `RepositoryTransactionTest`, `RlsIsolationTest`, `AsyncCompanyContextTest`.
- `hibernate.ddl-auto: validate` — entity drift fails at context startup.

A squash that silently drifts is caught by the suite, not by production. **This is the argument that
makes the squash acceptable at all**, and it holds only if the full `./gradlew build` is run against
the new `V1` — `ci.yml` runs `-x test` and would not see any of it.

## 5. It does not prevent the defect that prompted it

The lab deploy of `87a2614` failed because §217 phase 4 **edited `V53` after the lab had applied
it** — `Migration checksum mismatch for migration version 53`. A squashed `V1` has exactly the same
property the first time a slice edits it after a deploy, and the window is minutes: `ci.yml` deploys
`master` to the lab on every push.

The local gates structurally cannot see this class of defect. Every backend test migrates a **fresh**
container, where the edited file and the original are equally valid — there is no schema history to
disagree with. The first observer of an edited applied migration is a database that has already run
it, which here means the lab, at deploy time, after every gate is green. Same family as the
`out-of-order` rule in `2026-07-31-parallel-execution-rework.md`, and the same blind spot.

**Fewer migrations is a smaller target, not a fixed one.** What would actually fix it is a check that
fails when the content of an already-deployed migration changes — cheapest form: compare
`backend/src/main/resources/db/migration/*.sql` against the last deployed ref, and fail on any
modification to an existing file. Worth having whether or not the squash happens; it is not written.

## 6. The wipe is not `docker compose down -v`

**Postgres is host-native on both deployed boxes** (apt, PGDG repo), not a compose service —
`deploy/compose.prod.yaml`'s header says why, and only the backend and MCP are containerized. Three
consequences:

1. **The wipe is `dropdb` + `createdb` on the host**, not a volume removal. Nothing in the local
   command list does it.
2. **`deploy/postgres/initdb/10-tcms-roles.sql` will not re-run.** It is an *initdb* script, which
   executes only when the data directory is first initialized. Dropping a database on an existing
   cluster leaves `tcms_owner` and `tcms_app` in place — which is what you want, but it means the
   role split is **not** re-created by the wipe, and a `dropdb` that also drops the roles leaves the
   box unable to migrate. Drop the database only.
3. **The blob roots must be wiped in the same operation.** `/var/lib/tcms/evidence`,
   `/var/lib/tcms/reports` and `/var/lib/tcms/raw-reports` are host bind mounts holding files keyed
   by rows that are about to stop existing. Wiping the database alone orphans every one of them, and
   the mismatch is silent.

`baseline-on-migrate: false` must stay set. The new `V1` still has to run on an empty database for
its RLS block to execute; a baselined install ships without isolation, which is the trap the current
`application.yml` comment already names.

## 7. Sequence, when it is scheduled

1. Confirm the window is still open — no production data worth keeping (§3).
2. Generate the merged `V1` by hand, headings preserved (§4.1); keep the old files in git history
   only.
3. `pg_dump --schema-only` both ways and require an identical diff (§4.2).
4. Full `./gradlew build` — not `-x test` — locally and on shanks (§4.3).
5. Sweep filename-shaped citations (`V52__…sql`); leave bare `V52` alone (§4.1).
6. Wipe lab: drop database, drop the three blob roots, redeploy, re-provision, smoke.
7. Wipe production the same way, only after the lab is verified.
8. Update `docs/agent-context/be-java.md` and the ERD spec in the same commit.

## 8. What is NOT being decided here

- No decision to squash **now**. `V55` is the fix for the current deploy failure and stands
  regardless.
- No decision on whether the migration files are deleted or moved to `docs/history/`. §4.1's
  heading-preservation makes the move optional rather than necessary.
- No schedule. This document is the reminder, not the trigger.

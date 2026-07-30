# V1 schema — first execution against real Postgres

**Date:** 2026-07-30
**Context:** `backend/src/main/resources/db/migration/V1__initial_schema.sql` had been statically validated but **never executed** (HANDOFF §6). Docker became available, so it was run for the first time.
**Environment:** `postgres:17` (17.10, aarch64), stock image, no extension gating. Executed via `psql -v ON_ERROR_STOP=1` on a pristine database.

---

## Result: V1 executes cleanly

Fresh-database run exits **0** with no errors and no warnings beyond the three `CREATE EXTENSION IF NOT EXISTS` notices on re-run.

| Assertion | Static claim | Measured | Verdict |
|---|---|---|---|
| Tables created | 63 | **63** | ✅ |
| Indexes (incl. PK/unique-backed) | 55 explicit | **137** total | ✅ consistent |
| RLS policies | one per company-owned table | **56** | ✅ |
| `FORCE ROW LEVEL SECURITY` (not merely `ENABLE`) | required | **56 / 56** | ✅ |
| `ltree`, `pg_trgm`, `citext` availability | unverifiable statically | **all three present in stock `postgres:17`** | ✅ |

The three extensions were the largest unquantified risk (some managed hosts gate them); on the stock image they install without configuration. The `IMMUTABLE`-expression risk on `search_vector` does **not** apply to V1 — the columns are plain `TSVECTOR`, populated by triggers that land in V2.

---

## Trap 2 (unset GUC must yield zero rows) — **PASSES**

Verified as a **non-owner role** (`tcms_app`, `rolbypassrls=false`, `rolsuper=false`), which is the realistic connection identity and the only way `FORCE` is meaningfully exercised.

```
no app.current_company set:
  select count(*) from role_assignment    -> 0
  select count(*) from company_membership -> 0
ground truth as superuser                 -> 2 rows present
```

The `current_setting('app.current_company', true)` + `= NULL` construction behaves as designed: a request that forgot to establish company context sees **nothing**, not everything. This was the highest-severity silent-inversion risk in the schema and it is now empirically closed rather than reasoned about.

---

## Finding 1 (HIGH) — SYSTEM-scope role assignments are unreachable under RLS

`role_assignment.company_id` is **nullable by design**: `NULL` means SYSTEM scope, i.e. an Instance Admin grant (V1 line 269, and the column comment says so explicitly). Its RLS policy is the same bare equality as the other 54 tables:

```sql
company_id = current_setting('app.current_company', true)::uuid
```

`NULL = <anything>` evaluates to NULL, never true. **There is therefore no company context in which a SYSTEM-scope grant is visible** — the row is unreachable from the application permanently.

Measured:

```
2 role_assignment rows present (1 SYSTEM-scope, 1 COMPANY-scope in Acme)
context = Acme  -> 1 row visible (the Acme one)
context = Acme, where company_id is null -> 0
```

**Why this matters more than it looks.** The M0.5 `PermissionEvaluator` establishes "is this user an Instance Admin" by reading SYSTEM-scope `role_assignment` rows, and the second-pass decision to carry grants in the JWT (plan §6b) does not avoid this — login still has to *read* the grants from the database to mint the token. So the observable symptom is that **Instance Admin resolves to zero permissions**, surfacing as an authorization denial that looks like a permissions bug rather than a schema bug.

The dangerous repair is the obvious one: grant `BYPASSRLS` to the app role to "make admin work", which silently destroys isolation for all 56 tables. That makes this worth fixing before the evaluator is written, not after.

### The naive fix regresses trap 2

Widening the policy to `company_id IS NULL OR company_id = …` restores admin visibility and preserves cross-company isolation (Globex context sees no Acme row) — but a **context-less** query then returns the SYSTEM rows:

```
after widening, no context set: select count(*) from role_assignment -> 1   (was 0)
```

That breaks the stated invariant "an unset GUC must yield zero rows" and lets a context-less code path enumerate who the Instance Admins are. So the widened policy is *not* the fix.

**The affected set is exactly one table.** Of 56 RLS-forced tables, `role_assignment` is the only one with a nullable `company_id`; the other 55 share one identical policy expression. `instance_audit_event` also has a nullable `company_id` but is exception-listed and carries no RLS, which is consistent.

Options are recorded in HANDOFF for decision; this is a schema-design choice, not a mechanical patch.

---

## Finding 2 (LOW, documentation) — `role_permission` is an undocumented 8th exception

ERD §0.1 lists **7** deliberate instance-level tables: `company`, `app_user`, `permission`, `role`, `flyway_schema_history`, `instance_setting`, `instance_audit_event`. The DDL has **8** tables with no `company_id`:

```
app_user  company  instance_setting  permission  role  role_permission
```

`role_permission` joins `role` → `permission`, both instance-level reference data (the six built-in roles are immutable and seeded once, per plan §6b "no custom roles in v1"). A `company_id` on it would be meaningless, so **the DDL is correct and the ERD's list is incomplete.**

This is not cosmetic: ERD §0.1 states the list *is* the schema test's exception list. Written as documented, the M0 schema test fails the build on `role_permission` on its first run — and the tempting fix is to add a meaningless `company_id` to it rather than to correct the list.

**Fix:** add `role_permission` to the ERD §0.1 table with the reason "join between two instance-level tables; both sides are instance-wide reference data".

---

## What is still unverified

- **V2 triggers** do not exist yet — the `search_vector` `IMMUTABLE` requirement, the SINGLE-cardinality classification trigger and the team cycle/company-consistency trigger are all unwritten and therefore unrun.
- **No Flyway involvement yet.** V1 was applied with `psql`; it has not been run through Flyway, so checksum/ordering behaviour and `flyway_schema_history` creation are unexercised.
- **Isolation is proven only for the two probes above**, on hand-seeded rows. The paired-fixture harness (identical-looking data in two companies, every read endpoint) requires the application and is the M0 deliverable that makes this durable.
- **No performance characterization.** `company_id`-leading indexes were confirmed structurally, never measured.

## Reproducing

```bash
docker run -d --name tcms-pg -e POSTGRES_PASSWORD=pg -e POSTGRES_DB=tcms -p 55432:5432 postgres:17
docker cp backend/src/main/resources/db/migration/V1__initial_schema.sql tcms-pg:/tmp/v1.sql
docker exec tcms-pg psql -U postgres -d tcms -v ON_ERROR_STOP=1 -f /tmp/v1.sql
```

Test as a non-owner role — as the schema owner, plain `ENABLE` would appear to pass while protecting nothing, and that is the whole point of `FORCE`.
